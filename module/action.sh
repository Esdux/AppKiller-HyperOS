#!/system/bin/sh
# 模块管理器"执行"按钮入口
MODDIR=${0%/*}
[ -d "$MODDIR" ] || MODDIR=/data/adb/modules/appkiller
BIN="$MODDIR/bin/appkillerd"
PIDF="$MODDIR/run/appkillerd.pid"

alive() {
    [ -f "$PIDF" ] || return 1
    p=$(cat "$PIDF" 2>/dev/null)
    [ -n "$p" ] || return 1
    kill -0 "$p" 2>/dev/null
}

case "$1" in
    reload)
        if alive; then
            kill -HUP "$(cat "$PIDF")" && echo "已发送配置重载信号"
        else
            echo "守护进程未运行"
        fi
        ;;
    start)
        if alive; then echo "已在运行 (pid $(cat "$PIDF"))"; exit 0; fi
        mkdir -p "$MODDIR/logs" "$MODDIR/run"
        nohup "$BIN" daemon -m "$MODDIR" >> "$MODDIR/logs/stdout.log" 2>&1 &
        sleep 1
        echo "已启动 (pid $(cat "$PIDF" 2>/dev/null))"
        ;;
    stop)
        if alive; then kill "$(cat "$PIDF")" && echo "已停止"; else echo "未运行"; fi
        ;;
    restart)
        "$0" stop; sleep 1; "$0" start
        ;;
    once)
        "$BIN" once -m "$MODDIR"
        ;;
    watch)
        "$BIN" status -m "$MODDIR"
        ;;
    log)
        tail -n 40 "$MODDIR/logs/daemon.log" 2>/dev/null
        ;;
    *)
        "$BIN" status -m "$MODDIR"
        echo ""
        echo "参数： status | watch | once | log | start | stop | restart | reload"
        ;;
esac
