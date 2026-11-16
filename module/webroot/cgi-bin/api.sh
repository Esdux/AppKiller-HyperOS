#!/system/bin/sh
# KernelSU WebUI CGI 入口
MODDIR=$(cd "$(dirname "$0")/../.." 2>/dev/null && pwd)
[ -d "$MODDIR" ] || MODDIR=/data/adb/modules/appkiller
ACT="${1:-}"
QS="${QUERY_STRING:-}"
case "$QS" in *action=*) ACT=$(echo "$QS" | sed 's/.*action=//; s/&.*//') ;; esac

emit() {  # $1 = mime, $2 = body
    [ -n "${CGI_RAW:-}" ] && { printf '%s' "$2"; return; }
    printf 'Content-Type: %s\r\n\r\n' "$1"
    printf '%s' "$2"
}

case "$ACT" in
    log)
        emit "text/plain; charset=utf-8" "$(tail -n 60 "$MODDIR/logs/daemon.log" 2>/dev/null)"
        ;;
    reload)
        PIDF="$MODDIR/run/appkillerd.pid"
        if [ -f "$PIDF" ] && kill -HUP "$(cat "$PIDF")" 2>/dev/null; then
            emit "text/plain; charset=utf-8" "已发送重载信号"
        else
            emit "text/plain; charset=utf-8" "守护进程未运行"
        fi
        ;;
    *)
        ST="$MODDIR/run/state.json"
        if [ -f "$ST" ]; then
            emit "application/json; charset=utf-8" "$(cat "$ST")"
        else
            emit "application/json; charset=utf-8" '{"error":"daemon 未运行，请用 action.sh start 启动"}'
        fi
        ;;
esac
