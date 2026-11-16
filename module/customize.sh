#!/system/bin/sh
SKIPMOUNT=true
PROPFILE=false
POSTFSDATA=false
LATESTARTSERVICE=true

ui_print "- AppKiller：HyperOS/MIUI 后台冻结超时清理"
ui_print "- 冻结判定直接读 /proc/<pid>/cgroup，不依赖 hook"

OLD=/data/adb/modules/appkiller
# 升级时保留用户配置与名单
if [ -f "$OLD/config.conf" ]; then
    ui_print "- 保留已有 config.conf"
    cp -f "$OLD/config.conf" "$MODPATH/config.conf"
fi
if [ -f "$OLD/protect.list" ]; then
    ui_print "- 保留已有 protect.list"
    cp -f "$OLD/protect.list" "$MODPATH/protect.list"
fi
if [ -f "$OLD/blacklist.list" ]; then
    ui_print "- 保留已有 blacklist.list"
    cp -f "$OLD/blacklist.list" "$MODPATH/blacklist.list"
fi

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/bin/appkillerd" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
set_perm "$MODPATH/customize.sh" 0 0 0755

# webroot 里的 CGI 必须可执行
[ -d "$MODPATH/webroot/cgi-bin" ] && set_perm_recursive "$MODPATH/webroot/cgi-bin" 0 0 0755 0755

ui_print "- 安装完成。先看日志："
ui_print "    sh /data/adb/modules/appkiller/action.sh log"
ui_print "- 调阈值： /data/adb/modules/appkiller/config.conf  (TIMEOUT_MIN=30)"
