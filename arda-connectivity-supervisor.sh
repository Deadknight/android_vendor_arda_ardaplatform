#!/system/bin/sh
set -u

SENDERD="/system_ext/bin/arda-connectivityd"
CONFIG="/data/user_de/0/com.github.deadknight.ardaplatform.debug/files/arda-connectivity.toml"
LOG="/data/local/tmp/arda-connectivityd.log"
PID_FILE="/data/local/tmp/arda-connectivityd.pid"
POLL_SECONDS=1

# ARDA_CPU_POLICY_BEGIN
CPU_POLICY="/sys/devices/system/cpu/cpufreq/policy0"
CPU_MAX_NODE="$CPU_POLICY/scaling_max_freq"
CPU_INFO_MAX_NODE="$CPU_POLICY/cpuinfo_max_freq"
CPU_TEMP_NODE="/sys/class/thermal/thermal_zone0/temp"
CPU_STOCK_MHZ=1500
CPU_HARD_CEILING_MHZ=1900
CPU_HOT_MILLIC=78000
CPU_COOL_MILLIC=72000
cpu_guarded=0
cpu_last_applied=""
cpu_last_requested=""
# ARDA_CPU_POLICY_END

log_marker() {
    marker="$1"
    echo "$marker" >> "$LOG"
    /system/bin/log -t ARDA_RN3B_SENDERD "$marker" 2>/dev/null || true
}

config_stamp() {
    stat -c '%Y:%s' "$CONFIG" 2>/dev/null || echo missing
}

# ARDA_CPU_POLICY_FUNCTIONS_BEGIN
cpu_offset_allowed() {
    case "$1" in
        -200|-150|-100|-50|0|50|100|150|200|250|300|350|400) return 0 ;;
        *) return 1 ;;
    esac
}

cpu_read_requested_offset() {
    value="$(/system/bin/settings get global arda_cpu_boost_mhz 2>/dev/null || echo null)"
    case "$value" in
        null|"") value=0 ;;
    esac

    if ! cpu_offset_allowed "$value"; then
        log_marker "ARDA_CPU_POLICY_INVALID_REQUEST offset=$value fallback=0"
        value=0
    fi

    echo "$value"
}

cpu_apply_target_mhz() {
    requested_mhz="$1"

    info_max_khz="$(cat "$CPU_INFO_MAX_NODE" 2>/dev/null || echo 1500000)"
    case "$info_max_khz" in
        ''|*[!0-9]*) info_max_khz=1500000 ;;
    esac

    info_max_mhz=$((info_max_khz / 1000))
    target_mhz="$requested_mhz"

    [ "$target_mhz" -gt "$CPU_HARD_CEILING_MHZ" ] &&
        target_mhz="$CPU_HARD_CEILING_MHZ"
    [ "$target_mhz" -gt "$info_max_mhz" ] &&
        target_mhz="$info_max_mhz"
    [ "$target_mhz" -lt 600 ] &&
        target_mhz=600

    target_khz=$((target_mhz * 1000))
    current_khz="$(cat "$CPU_MAX_NODE" 2>/dev/null || echo 0)"

    if [ "$current_khz" != "$target_khz" ]; then
        if ! echo "$target_khz" > "$CPU_MAX_NODE"; then
            log_marker "ARDA_CPU_POLICY_APPLY_FAIL target_mhz=$target_mhz current_khz=$current_khz"
            return 1
        fi
    fi

    if [ "$cpu_last_applied" != "$target_mhz" ]; then
        temp="$(cat "$CPU_TEMP_NODE" 2>/dev/null || echo 0)"
        log_marker "ARDA_CPU_POLICY_APPLY target_mhz=$target_mhz requested_mhz=$requested_mhz guarded=$cpu_guarded temp_millic=$temp ceiling_mhz=$info_max_mhz"
        cpu_last_applied="$target_mhz"
    fi

    return 0
}

cpu_policy_tick() {
    requested_offset="$(cpu_read_requested_offset)"
    requested_mhz=$((CPU_STOCK_MHZ + requested_offset))

    temp="$(cat "$CPU_TEMP_NODE" 2>/dev/null || echo 0)"
    case "$temp" in
        ''|*[!0-9]*) temp=0 ;;
    esac

    if [ "$cpu_guarded" -eq 0 ] && [ "$temp" -ge "$CPU_HOT_MILLIC" ]; then
        cpu_guarded=1
        log_marker "ARDA_CPU_POLICY_THERMAL_GUARD_ENTER temp_millic=$temp requested_mhz=$requested_mhz"
    elif [ "$cpu_guarded" -eq 1 ] && [ "$temp" -le "$CPU_COOL_MILLIC" ]; then
        cpu_guarded=0
        log_marker "ARDA_CPU_POLICY_THERMAL_GUARD_EXIT temp_millic=$temp requested_mhz=$requested_mhz"
    fi

    effective_mhz="$requested_mhz"
    if [ "$cpu_guarded" -eq 1 ] && [ "$effective_mhz" -gt "$CPU_STOCK_MHZ" ]; then
        effective_mhz="$CPU_STOCK_MHZ"
    fi

    cpu_apply_target_mhz "$effective_mhz"

    if [ "$cpu_last_requested" != "$requested_offset" ]; then
        log_marker "ARDA_CPU_POLICY_REQUEST offset_mhz=$requested_offset target_mhz=$requested_mhz"
        cpu_last_requested="$requested_offset"
    fi
}
# ARDA_CPU_POLICY_FUNCTIONS_END

child_pid=""

stop_child() {
    if [ -n "$child_pid" ]; then
        kill "$child_pid" 2>/dev/null || true
        wait "$child_pid" 2>/dev/null || true
        child_pid=""
        rm -f "$PID_FILE"
    fi
}

trap 'stop_child; exit 0' TERM INT HUP

mkdir -p /data/local/tmp
: > "$LOG"
chmod 0600 "$LOG"
log_marker "ARDA_RN3B_SENDERD_SUPERVISOR=START"
log_marker "ARDA_RN3B_SENDERD_CONFIG_PATH=$CONFIG"

# Safety-first runtime default. The firmware exposes up to 1900 MHz, but an
# absent Settings.Global request means stock 1500 MHz.
cpu_apply_target_mhz "$CPU_STOCK_MHZ"
cpu_policy_tick

while true; do
    while [ ! -s "$CONFIG" ]; do
        cpu_policy_tick
        log_marker "ARDA_RN3B_SENDERD_CONFIG_WAIT=YES"
        sleep "$POLL_SECONDS"
    done

    stamp="$(config_stamp)"
    log_marker "ARDA_RN3B_SENDERD_CONFIG_READY=PASS stamp=$stamp"

    "$SENDERD" bt-bootstrapd --config "$CONFIG" >> "$LOG" 2>&1 &
    child_pid=$!
    echo "$child_pid" > "$PID_FILE"
    log_marker "ARDA_RN3B_SENDERD_CHILD_STARTED=PASS pid=$child_pid"

    while kill -0 "$child_pid" 2>/dev/null; do
        sleep "$POLL_SECONDS"
        cpu_policy_tick
        new_stamp="$(config_stamp)"
        if [ "$new_stamp" != "$stamp" ]; then
            log_marker "ARDA_RN3B_SENDERD_CONFIG_CHANGED=RESTART old=$stamp new=$new_stamp"
            stop_child
            break
        fi
    done

    if [ -n "$child_pid" ]; then
        wait "$child_pid" 2>/dev/null || true
        child_pid=""
        rm -f "$PID_FILE"
        log_marker "ARDA_RN3B_SENDERD_CHILD_EXITED=RESTART"
    fi

    cpu_policy_tick
    sleep "$POLL_SECONDS"
done
