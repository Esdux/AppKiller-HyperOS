#!/system/bin/sh
# KernelSU / Magisk late_start service
MODDIR=${0%/*}
[ -d "$MODDIR" ] || MODDIR=/data/adb/modules/appkiller

# shellcheck disable=SC1090
[ -f "$MODDIR/config.conf" ] && . "$MODDIR/config.conf" 2>/dev/null

[ "${ENABLE:-1}" = "1" ] || exit 0
[ -x "$MODDIR/bin/appkillerd" ] || exit 0

mkdir -p "$MODDIR/logs" "$MODDIR/run"

# 已有实例则不重复启动
if [ -f "$MODDIR/run/appkillerd.pid" ]; then
    oldpid=$(cat "$MODDIR/run/appkillerd.pid" 2>/dev/null)
    if [ -n "$oldpid" ] && kill -0 "$oldpid" 2>/dev/null; then
        exit 0
    fi
fi

# 刷入时 pm 不可用的一次性补装：只认 .pending_apk 标记，装完即删，之后开机不再碰 App
if [ -f "$MODDIR/.pending_apk" ]; then
    APKFILE="$MODDIR/bin/appkiller.apk"
    if [ -f "$APKFILE" ]; then
        ( sleep 8; pm install -r -g "$APKFILE" >/dev/null 2>&1 && rm -f "$MODDIR/.pending_apk" ) &
    else
        rm -f "$MODDIR/.pending_apk"
    fi
fi

# 等系统稳定，避免开机瞬间把正常冷启动的应用误判成冻结
sleep "${START_DELAY_SEC:-20}"

nohup "$MODDIR/bin/appkillerd" daemon -m "$MODDIR" >> "$MODDIR/logs/stdout.log" 2>&1 &
