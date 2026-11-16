#!/system/bin/sh
MODDIR=${0%/*}
PIDF="$MODDIR/run/appkillerd.pid"
if [ -f "$PIDF" ]; then
    p=$(cat "$PIDF" 2>/dev/null)
    [ -n "$p" ] && kill "$p" 2>/dev/null
    rm -f "$PIDF"
fi
