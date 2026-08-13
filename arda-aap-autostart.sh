#!/system/bin/sh
set -u

BINARY="/system_ext/bin/arda-connectivityd"
CONFIG="/product/etc/arda/arda-aap.toml"
PLATFORM_PACKAGE="com.github.deadknight.ardaplatform.debug"
VIDEO_OVERRIDE_CONFIG="/data/user_de/0/$PLATFORM_PACKAGE/files/arda-aap-video.toml"
DEFAULT_VIDEO_POLICY="balanced"
DEFAULT_VIDEO_CODEC="c2.v4l2.avc.encoder"
FALLBACK_VIDEO_CODEC="c2.android.avc.encoder"
CUSTOM_MAPPER="/vendor/lib64/egl/arda/libminigbm_gralloc_gbm_mesa.so"
ACTIVE_MAPPER="/apex/com.android.hardware.graphics.allocator.minigbm_gbm_mesa/lib64/libminigbm_gralloc_gbm_mesa.so"
CERT_DIR="/data/local/tmp/arda-certs"
USB_NODE="/dev/usb_accessory"
LOG="/data/local/tmp/arda-aap-auto.log"
PID_FILE="/data/local/tmp/arda-aap-auto.pid"
BOOT_SETTLE_MARKER="/dev/.arda-aap-boot-settled-v1"
BOOT_SETTLE_TIMEOUT_SECONDS=60
BOOT_SETTLE_STABLE_SECONDS=5

mkdir -p /data/local/tmp
umask 077

log_marker() {
    marker="$1"
    echo "$marker" >> "$LOG"
    /system/bin/log -t ARDA_AAP_AUTOSTART "$marker" 2>/dev/null || true
}

wait_for_boot_settle() {
    if [ -e "$BOOT_SETTLE_MARKER" ]; then
        log_marker "ARDA_AAP_BOOT_SETTLE=SKIP_ALREADY_SETTLED marker=$BOOT_SETTLE_MARKER"
        return 0
    fi

    elapsed=0
    ready_streak=0

    log_marker "ARDA_AAP_BOOT_SETTLE_BEGIN timeout_s=$BOOT_SETTLE_TIMEOUT_SECONDS stable_s=$BOOT_SETTLE_STABLE_SECONDS"

    while [ "$elapsed" -lt "$BOOT_SETTLE_TIMEOUT_SECONDS" ]; do
        boot_completed="$(getprop sys.boot_completed 2>/dev/null || true)"
        current_user="$(am get-current-user 2>/dev/null | tail -n 1 | tr -d '\r' || true)"
        systemui_pid="$(pidof com.android.systemui 2>/dev/null || true)"
        carlauncher_pid="$(pidof com.android.car.carlauncher 2>/dev/null || true)"

        activity_ready=0
        window_ready=0
        service check activity 2>/dev/null | grep -q "found" && activity_ready=1
        service check window 2>/dev/null | grep -q "found" && window_ready=1

        if [ "$boot_completed" = "1" ] \
            && [ "$activity_ready" = "1" ] \
            && [ "$window_ready" = "1" ] \
            && [ -n "$systemui_pid" ] \
            && [ -n "$carlauncher_pid" ]; then
            ready_streak=$((ready_streak + 1))
        else
            ready_streak=0
        fi

        if [ "$ready_streak" -ge "$BOOT_SETTLE_STABLE_SECONDS" ]; then
            touch "$BOOT_SETTLE_MARKER"
            chmod 0600 "$BOOT_SETTLE_MARKER" 2>/dev/null || true
            log_marker "ARDA_AAP_BOOT_SETTLE_READY elapsed_s=$elapsed stable_s=$ready_streak user=$current_user systemui=$systemui_pid carlauncher=$carlauncher_pid"
            return 0
        fi

        if [ "$elapsed" -eq 0 ] || [ $((elapsed % 5)) -eq 0 ]; then
            log_marker "ARDA_AAP_BOOT_SETTLE_WAIT elapsed_s=$elapsed boot_completed=$boot_completed user=$current_user activity=$activity_ready window=$window_ready systemui=${systemui_pid:-none} carlauncher=${carlauncher_pid:-none} streak=$ready_streak"
        fi

        sleep 1
        elapsed=$((elapsed + 1))
    done

    touch "$BOOT_SETTLE_MARKER"
    chmod 0600 "$BOOT_SETTLE_MARKER" 2>/dev/null || true
    log_marker "ARDA_AAP_BOOT_SETTLE_TIMEOUT_CONTINUE elapsed_s=$elapsed"
    return 0
}

cleanup() {
    rm -f "$PID_FILE"
}

trap cleanup EXIT

if [ -s "$PID_FILE" ]; then
    old_pid="$(cat "$PID_FILE" 2>/dev/null || true)"
    if [ -n "$old_pid" ] && kill -0 "$old_pid" 2>/dev/null; then
        log_marker "ARDA_AAP_AUTOSTART=SKIP_ALREADY_RUNNING pid=$old_pid"
        exit 0
    fi
fi

echo "$$" > "$PID_FILE"

# Hold only the first AAP session of each boot until the foreground AAOS UI stack
# is stable. /dev is tmpfs, so the marker naturally disappears on reboot.
wait_for_boot_settle

session_id="$(date +%s 2>/dev/null || echo unknown)"
log_marker "ARDA_AAP_AUTOSTART_BEGIN session=$session_id pid=$$ usb_state=$(getprop sys.usb.state) usb_config=$(getprop sys.usb.config)"

if [ ! -x "$BINARY" ]; then
    log_marker "ARDA_AAP_AUTOSTART=FAIL_BINARY_MISSING path=$BINARY"
    exit 2
fi

if [ ! -s "$CONFIG" ]; then
    log_marker "ARDA_AAP_AUTOSTART=FAIL_CONFIG_MISSING path=$CONFIG"
    exit 2
fi

if [ ! -s "$CERT_DIR/md_cert.pem" ] || [ ! -s "$CERT_DIR/md_key.pem" ]; then
    log_marker "ARDA_AAP_AUTOSTART=FAIL_TLS_FILES_MISSING dir=$CERT_DIR"
    exit 2
fi

# AOAP property notification and /dev/usb_accessory creation can be a few
# hundred milliseconds apart. Wait for the node rather than racing it.
wait_seconds=0
while [ ! -e "$USB_NODE" ] && [ "$wait_seconds" -lt 10 ]; do
    sleep 1
    wait_seconds=$((wait_seconds + 1))
done

if [ ! -e "$USB_NODE" ]; then
    log_marker "ARDA_AAP_AUTOSTART=FAIL_USB_NODE_TIMEOUT path=$USB_NODE wait_seconds=$wait_seconds"
    exit 2
fi

video_policy="$DEFAULT_VIDEO_POLICY"
forced_resolution=""
if [ -s "$VIDEO_OVERRIDE_CONFIG" ]; then
    forced_resolution="$(
        sed -n 's/^[[:space:]]*force_resolution[[:space:]]*=[[:space:]]*"\([^"]*\)"[[:space:]]*$/\1/p' \
            "$VIDEO_OVERRIDE_CONFIG" | tail -n 1
    )"
    auto_policy="$(
        sed -n 's/^[[:space:]]*auto_policy[[:space:]]*=[[:space:]]*"\([^"]*\)"[[:space:]]*$/\1/p' \
            "$VIDEO_OVERRIDE_CONFIG" | tail -n 1
    )"
    if [ -n "$auto_policy" ]; then
        video_policy="$auto_policy"
    fi
fi
if [ -n "$forced_resolution" ]; then
    video_policy="resolution:$forced_resolution"
fi

video_codec_reason="EXPLICIT_ENV"
if [ -n "${ARDA_SCREENRECORD_CODEC:-}" ]; then
    video_codec="$ARDA_SCREENRECORD_CODEC"
else
    video_codec="$FALLBACK_VIDEO_CODEC"
    video_codec_reason="CUSTOM_MAPPER_NOT_ACTIVE"
    if [ -s "$CUSTOM_MAPPER" ] && [ -s "$ACTIVE_MAPPER" ]; then
        custom_mapper_hash="$(sha256sum "$CUSTOM_MAPPER" | awk '{print $1}')"
        active_mapper_hash="$(sha256sum "$ACTIVE_MAPPER" | awk '{print $1}')"
        if [ "$custom_mapper_hash" = "$active_mapper_hash" ]; then
            video_codec="$DEFAULT_VIDEO_CODEC"
            video_codec_reason="CUSTOM_MAPPER_ACTIVE"
        fi
    fi
fi
log_marker "ARDA_AAP_AUTOSTART_LAUNCH=YES config=$CONFIG video_override=$VIDEO_OVERRIDE_CONFIG video_policy=$video_policy video_codec=$video_codec video_codec_reason=$video_codec_reason usb_node=$USB_NODE wait_seconds=$wait_seconds"

ARDA_PLATFORM_PACKAGE="$PLATFORM_PACKAGE" \
ARDA_SCREENRECORD_CODEC="$video_codec" \
"$BINARY" aap-run --video-config-policy "$video_policy" >> "$LOG" 2>&1
rc=$?

log_marker "ARDA_AAP_AUTOSTART_END session=$session_id rc=$rc usb_state=$(getprop sys.usb.state) usb_config=$(getprop sys.usb.config)"
exit "$rc"
