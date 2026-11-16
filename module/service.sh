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

# 模块内若带有管理器 APK 且尚未安装，静默装上（不影响守护进程启动）
APKFILE="$MODDIR/bin/appkiller.apk"
if [ -f "$APKFILE" ]; then
    if ! pm list packages 2>/dev/null | grep -q '^package:com.yg.appkiller$'; then
        ( sleep 8; pm install -r -g "$APKFILE" >/dev/null 2>&1 ) &
    fi
fi

# 等系统稳定，避免开机瞬间把正常冷启动的应用误判成冻结
sleep "${START_DELAY_SEC:-20}"

nohup "$MODDIR/bin/appkillerd" daemon -m "$MODDIR" >> "$MODDIR/logs/stdout.log" 2>&1 &
