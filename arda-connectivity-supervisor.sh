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
CPU_INFO_MIN_NODE="$CPU_POLICY/cpuinfo_min_freq"
CPU_INFO_MAX_NODE="$CPU_POLICY/cpuinfo_max_freq"
CPU_TEMP_NODE="/sys/class/thermal/thermal_zone0/temp"

CPU_RPI4_BASELINE_MHZ=1900
CPU_RPI5_BASELINE_MHZ=2400
CPU_OFFSET_MIN_MHZ=-1000
CPU_OFFSET_MAX_MHZ=1000

CPU_HOT_MILLIC=78000
CPU_COOL_MILLIC=72000

cpu_baseline_mhz=0
cpu_guarded=0
cpu_last_applied=""
cpu_last_requested=""
cpu_last_pending=""
# ARDA_CPU_POLICY_END


# ARDA_AAP_KEY_UPDATE_SHARED_V1
# Shared by Raspberry Pi 4 and Raspberry Pi 5 through arda_connectivity_product.mk.
# Certificate download, validation, activation and rollback stay inside arda-connectivityd.
AAP_KEY_UPDATE_ARGS="\
 --aap-key-update\
 --aap-key-control-url https://aap.opencharge.top/control.php\
 --aap-key-direct-internet\
 --aap-key-check-on-start\
 --aap-key-check-on-direct-ready\
 --aap-key-check-on-proxy-ready\
 --aap-key-check-interval-seconds 86400\
 --aap-key-connect-debounce-seconds 0\
 --aap-key-retry-interval-seconds 60\
 --aap-key-download-timeout-seconds 30\
 --aap-key-max-manifest-bytes 65536\
 --aap-key-max-archive-bytes 8388608"

log_marker() {
    marker="$1"
    echo "$marker" >> "$LOG"
    /system/bin/log -t ARDA_RN3B_SENDERD "$marker" 2>/dev/null || true
}

config_stamp() {
    stat -c '%Y:%s' "$CONFIG" 2>/dev/null || echo missing
}

# ARDA_CPU_POLICY_FUNCTIONS_BEGIN
cpu_detect_baseline_mhz() {
    hardware="$(getprop ro.hardware 2>/dev/null || true)"
    board="$(getprop ro.product.board 2>/dev/null || true)"

    case "$hardware:$board" in
        *rpi5*|*bcm2712*)
            echo "$CPU_RPI5_BASELINE_MHZ"
            return
            ;;
        *rpi4*|*bcm2711*)
            echo "$CPU_RPI4_BASELINE_MHZ"
            return
            ;;
    esac

    info_max_khz="$(cat "$CPU_INFO_MAX_NODE" 2>/dev/null || echo 0)"
    case "$info_max_khz" in
        ''|*[!0-9]*) info_max_khz=0 ;;
    esac

    if [ "$info_max_khz" -gt 0 ]; then
        echo $((info_max_khz / 1000))
    else
        echo "$CPU_RPI5_BASELINE_MHZ"
    fi
}

cpu_read_requested_offset() {
    value="$(/system/bin/settings get global arda_cpu_boost_mhz 2>/dev/null || echo null)"

    case "$value" in
        null|'')
            value=0
            ;;
        -*)
            numeric="${value#-}"
            case "$numeric" in
                ''|*[!0-9]*)
                    log_marker "ARDA_CPU_POLICY_INVALID_REQUEST offset=$value fallback=0"
                    value=0
                    ;;
            esac
            ;;
        *)
            case "$value" in
                *[!0-9]*)
                    log_marker "ARDA_CPU_POLICY_INVALID_REQUEST offset=$value fallback=0"
                    value=0
                    ;;
            esac
            ;;
    esac

    [ "$value" -lt "$CPU_OFFSET_MIN_MHZ" ] && value="$CPU_OFFSET_MIN_MHZ"
    [ "$value" -gt "$CPU_OFFSET_MAX_MHZ" ] && value="$CPU_OFFSET_MAX_MHZ"

    echo "$value"
}

cpu_apply_target_mhz() {
    requested_mhz="$1"

    info_min_khz="$(cat "$CPU_INFO_MIN_NODE" 2>/dev/null || echo 0)"
    info_max_khz="$(cat "$CPU_INFO_MAX_NODE" 2>/dev/null || echo 0)"

    case "$info_min_khz" in
        ''|*[!0-9]*) info_min_khz=0 ;;
    esac
    case "$info_max_khz" in
        ''|*[!0-9]*) info_max_khz=0 ;;
    esac

    info_min_mhz=$((info_min_khz / 1000))
    info_max_mhz=$((info_max_khz / 1000))

    target_mhz="$requested_mhz"
    pending="none"

    if [ "$info_max_mhz" -gt 0 ] && [ "$target_mhz" -gt "$info_max_mhz" ]; then
        pending="overclock_boot_support"
        target_mhz="$info_max_mhz"
    fi

    if [ "$info_min_mhz" -gt 0 ] && [ "$target_mhz" -lt "$info_min_mhz" ]; then
        pending="underclock_boot_support"
        target_mhz="$info_min_mhz"
    fi

    target_khz=$((target_mhz * 1000))
    current_khz="$(cat "$CPU_MAX_NODE" 2>/dev/null || echo 0)"

    if [ "$current_khz" != "$target_khz" ]; then
        if ! echo "$target_khz" > "$CPU_MAX_NODE"; then
            log_marker "ARDA_CPU_POLICY_APPLY_FAIL baseline_mhz=$cpu_baseline_mhz requested_mhz=$requested_mhz target_mhz=$target_mhz current_khz=$current_khz"
            return 1
        fi
    fi

    if [ "$cpu_last_applied" != "$target_mhz" ]; then
        temp="$(cat "$CPU_TEMP_NODE" 2>/dev/null || echo 0)"
        log_marker "ARDA_CPU_POLICY_APPLY baseline_mhz=$cpu_baseline_mhz requested_mhz=$requested_mhz target_mhz=$target_mhz guarded=$cpu_guarded temp_millic=$temp info_min_mhz=$info_min_mhz info_max_mhz=$info_max_mhz"
        cpu_last_applied="$target_mhz"
    fi

    if [ "$cpu_last_pending" != "$pending" ]; then
        log_marker "ARDA_CPU_POLICY_PENDING state=$pending baseline_mhz=$cpu_baseline_mhz requested_mhz=$requested_mhz applied_mhz=$target_mhz info_min_mhz=$info_min_mhz info_max_mhz=$info_max_mhz"
        cpu_last_pending="$pending"
    fi
}

cpu_policy_tick() {
    [ "$cpu_baseline_mhz" -gt 0 ] ||
        cpu_baseline_mhz="$(cpu_detect_baseline_mhz)"

    requested_offset="$(cpu_read_requested_offset)"
    requested_mhz=$((cpu_baseline_mhz + requested_offset))

    temp="$(cat "$CPU_TEMP_NODE" 2>/dev/null || echo 0)"
    case "$temp" in
        ''|*[!0-9]*) temp=0 ;;
    esac

    if [ "$cpu_guarded" -eq 0 ] && [ "$temp" -ge "$CPU_HOT_MILLIC" ]; then
        cpu_guarded=1
        log_marker "ARDA_CPU_POLICY_THERMAL_GUARD_ENTER temp_millic=$temp baseline_mhz=$cpu_baseline_mhz requested_mhz=$requested_mhz"
    elif [ "$cpu_guarded" -eq 1 ] && [ "$temp" -le "$CPU_COOL_MILLIC" ]; then
        cpu_guarded=0
        log_marker "ARDA_CPU_POLICY_THERMAL_GUARD_EXIT temp_millic=$temp baseline_mhz=$cpu_baseline_mhz requested_mhz=$requested_mhz"
    fi

    effective_mhz="$requested_mhz"

    if [ "$cpu_guarded" -eq 1 ] && [ "$effective_mhz" -gt "$cpu_baseline_mhz" ]; then
        effective_mhz="$cpu_baseline_mhz"
    fi

    cpu_apply_target_mhz "$effective_mhz"

    if [ "$cpu_last_requested" != "$requested_offset" ]; then
        log_marker "ARDA_CPU_POLICY_REQUEST offset_mhz=$requested_offset baseline_mhz=$cpu_baseline_mhz requested_mhz=$requested_mhz"
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

# Offset 0 means this board's ARDA baseline. The cpufreq governor is not
# changed; only scaling_max_freq is adjusted inside the current OPP range.
cpu_baseline_mhz="$(cpu_detect_baseline_mhz)"
log_marker "ARDA_CPU_POLICY_BASELINE baseline_mhz=$cpu_baseline_mhz hardware=$(getprop ro.hardware 2>/dev/null || true) board=$(getprop ro.product.board 2>/dev/null || true)"
cpu_policy_tick

while true; do
    while [ ! -s "$CONFIG" ]; do
        cpu_policy_tick
        log_marker "ARDA_RN3B_SENDERD_CONFIG_WAIT=YES"
        sleep "$POLL_SECONDS"
    done

    stamp="$(config_stamp)"
    log_marker "ARDA_RN3B_SENDERD_CONFIG_READY=PASS stamp=$stamp"

    "$SENDERD" bt-bootstrapd --config "$CONFIG" $AAP_KEY_UPDATE_ARGS >> "$LOG" 2>&1 &
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
