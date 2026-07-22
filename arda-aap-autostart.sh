#!/system/bin/sh
set -u

BINARY="/system_ext/bin/arda-connectivityd"
CONFIG="/product/etc/arda/arda-aap.toml"
CERT_DIR="/data/local/tmp/arda-certs"
LOG="/data/local/tmp/arda-aap-auto.log"

mkdir -p /data/local/tmp
umask 077

session_id="$(date +%s 2>/dev/null || echo unknown)"
marker="ARDA_AAP_PERSISTENT_WAIT_BEGIN session=$session_id pid=$$ usb_state=$(getprop sys.usb.state) usb_config=$(getprop sys.usb.config)"

echo "$marker" >> "$LOG"
/system/bin/log -t ARDA_AAP_AUTOSTART "$marker" 2>/dev/null || true

if [ ! -x "$BINARY" ]; then
    echo "ARDA_AAP_PERSISTENT_WAIT=FAIL_BINARY_MISSING path=$BINARY" >> "$LOG"
    exit 2
fi

if [ ! -s "$CONFIG" ]; then
    echo "ARDA_AAP_PERSISTENT_WAIT=FAIL_CONFIG_MISSING path=$CONFIG" >> "$LOG"
    exit 2
fi

if [ ! -s "$CERT_DIR/md_cert.pem" ] || [ ! -s "$CERT_DIR/md_key.pem" ]; then
    echo "ARDA_AAP_PERSISTENT_WAIT=FAIL_TLS_FILES_MISSING dir=$CERT_DIR" >> "$LOG"
    exit 2
fi

echo "ARDA_AAP_PERSISTENT_WAIT_LAUNCH=YES config=$CONFIG" >> "$LOG"

# Replace the wrapper process with senderd. Init therefore owns the real AAP
# process directly; stop/restart cannot leave a child process behind.
exec "$BINARY" aap-run >> "$LOG" 2>&1
