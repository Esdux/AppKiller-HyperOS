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

# 刷入时安装管理器 APK（仅刷入时一次；开机不再补装）
APKFILE="$MODPATH/bin/appkiller.apk"
if [ -f "$APKFILE" ]; then
    ui_print "- 刷入时安装管理器 App: com.yg.appkiller"
    if pm install -r -g "$APKFILE" >/dev/null 2>&1; then
        ui_print "- App 安装/更新完成"
    else
        # 恢复环境等无 pm 的场合：留一次性标记，首次开机由 service.sh 补装后删除
        ui_print "! 刷入环境无 pm 或安装失败，标记为首开补装一次"
        touch "$MODPATH/.pending_apk"
    fi
else
    ui_print "- 模块包未含管理器 APK，跳过 App 安装"
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
