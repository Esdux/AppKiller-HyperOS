/*
 * appkillerd.c - HyperOS/MIUI 后台冻结超时清理守护进程
 *
 * 冻结判定三路（任一命中即视为冻结）：
 *   1) /proc/<pid>/cgroup 路径为 /frozen          <- HyperOS greeze（本机实测路径）
 *   2) 同路径下 cgroup.events 含 "frozen 1"        <- cgroup v2 标准 freezer / AOSP userspace freezer
 *   3) /proc/<pid>/stat 状态字符为 T/t            <- SIGSTOP 式冻结
 *
 * 计时语义：
 *   某包全部进程都在后台（adj >= ACTIVE_ADJ）且至少有进程被冻结 -> 开始/继续计时
 *   某包出现可见进程（adj < ACTIVE_ADJ）或被解冻            -> 计时清零（= 中间用过）
 *   冻结持续时间 >= TIMEOUT -> 清理
 */
#define _GNU_SOURCE
#include "appkillerd.h"

#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <poll.h>
#include <limits.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

config_t g_cfg;
char     g_moddir[PATH_LEN];

static char  g_cfgpath[PATH_LEN];
static char  g_logpath[PATH_LEN];
static char  g_statepath[PATH_LEN];
static char  g_watchpath[PATH_LEN];
static char  g_pidpath[PATH_LEN];
static FILE *g_log = NULL;

static char g_protect[MAX_PROTECT][PKG_LEN];
static int  g_protect_n = 0;
static char g_blacklist[MAX_PROTECT][PKG_LEN];
static int  g_blacklist_n = 0;
static char g_systemlist[MAX_PROTECT][PKG_LEN];
static int  g_systemlist_n = 0;
static char g_immortal[MAX_PROTECT][PKG_LEN];
static int  g_immortal_n = 0;

static perapp_t  g_perapp[MAX_PERAPP];
static int       g_perapp_n = 0;
static perproc_t g_perproc[MAX_PERPROC];
static int       g_perproc_n = 0;

static pkgent_t g_pkgs[MAX_PKGS];
static int      g_pkg_n = 0;

static hist_t g_hist[MAX_HISTORY];
static int    g_hist_n = 0;

static long long g_start_mono = 0;
static volatile sig_atomic_t g_stop = 0;
static volatile sig_atomic_t g_reload = 0;

/* ============================ 基础工具 ============================ */

static char *trim(char *s) {
    while (*s && isspace((unsigned char)*s)) s++;
    char *e = s + strlen(s);
    while (e > s && isspace((unsigned char)e[-1])) *--e = '\0';
    return s;
}

long long mono_sec(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec;
}

static void mkdirs_for(const char *file) {
    char tmp[PATH_LEN];
    snprintf(tmp, sizeof(tmp), "%s", file);
    char *slash = strrchr(tmp, '/');
    if (!slash || slash == tmp) return;
    *slash = '\0';
    for (char *p = tmp + 1; *p; p++) {
        if (*p == '/') { *p = '\0'; mkdir(tmp, 0755); *p = '/'; }
    }
    mkdir(tmp, 0755);
}

static void ensure_dirs(void) {
    char p[PATH_LEN];
    snprintf(p, sizeof(p), "%s", g_moddir);            mkdir(p, 0755);
    snprintf(p, sizeof(p), "%s/logs", g_moddir);       mkdir(p, 0755);
    snprintf(p, sizeof(p), "%s/run", g_moddir);        mkdir(p, 0755);
    mkdirs_for(g_logpath);
    mkdirs_for(g_statepath);
}

static int file_age_sec(const char *path) {
    struct stat st;
    if (stat(path, &st) != 0) return -1;
    time_t now = time(NULL);
    if (now < st.st_mtime) return 0;
    return (int)(now - st.st_mtime);
}

/* ============================ 日志 ============================ */

void log_open(void) {
    if (g_log) return;
    mkdirs_for(g_logpath);
    g_log = fopen(g_logpath, "a");
    if (g_log) setvbuf(g_log, NULL, _IOLBF, 0);
}

void log_close(void) {
    if (g_log) { fclose(g_log); g_log = NULL; }
}

static void log_rotate(void) {
    if (!g_log) return;
    long maxb = (long)(g_cfg.log_max_kb > 0 ? g_cfg.log_max_kb : 512) * 1024L;
    long pos = ftell(g_log);
    if (pos < 0 || pos < maxb) return;
    fclose(g_log);
    g_log = NULL;
    char old[PATH_LEN + 8];
    snprintf(old, sizeof(old), "%s.old", g_logpath);
    rename(g_logpath, old);
    g_log = fopen(g_logpath, "a");
    if (g_log) setvbuf(g_log, NULL, _IOLBF, 0);
}

void log_msg(const char *fmt, ...) {
    if (!g_log) return;
    log_rotate();
    if (!g_log) return;
    time_t t = time(NULL);
    struct tm tmv;
    localtime_r(&t, &tmv);
    char tb[32];
    strftime(tb, sizeof(tb), "%Y-%m-%d %H:%M:%S", &tmv);
    fprintf(g_log, "[%s] ", tb);
    va_list ap;
    va_start(ap, fmt);
    vfprintf(g_log, fmt, ap);
    va_end(ap);
    fputc('\n', g_log);
    fflush(g_log);
}

/* ============================ 配置 ============================ */

void cfg_defaults(void) {
    memset(&g_cfg, 0, sizeof(g_cfg));
    g_cfg.enable                = 1;
    g_cfg.timeout_sec           = 30 * 60;
    g_cfg.interval_ms           = 2000;
    g_cfg.active_adj            = 200;
    g_cfg.min_uid               = 1000;
    g_cfg.max_kill_per_round    = 3;
    g_cfg.kill_cooldown_sec     = 180;
    g_cfg.detect_cgroup_events  = 1;
    g_cfg.detect_state_t        = 1;
    g_cfg.dry_run               = 0;
    g_cfg.debug_log             = 0;
    g_cfg.log_max_kb            = 512;
    g_cfg.state_interval_sec    = 5;
    g_cfg.ctl_port              = 27183;
    snprintf(g_cfg.kill_mode,  sizeof(g_cfg.kill_mode),  "%s", "sigkill");
    snprintf(g_cfg.kill_scope, sizeof(g_cfg.kill_scope), "%s", "pkg");
    snprintf(g_cfg.protect_file, sizeof(g_cfg.protect_file), "%s", "protect.list");
    snprintf(g_cfg.blacklist_file, sizeof(g_cfg.blacklist_file), "%s", "blacklist.list");
    snprintf(g_cfg.system_list_file, sizeof(g_cfg.system_list_file), "%s", "system_apps.list");
    snprintf(g_cfg.immortal_file, sizeof(g_cfg.immortal_file), "%s", "immortal.list");
    snprintf(g_cfg.perapp_file, sizeof(g_cfg.perapp_file), "%s", "perapp.conf");
    snprintf(g_cfg.perproc_file, sizeof(g_cfg.perproc_file), "%s", "perproc.conf");
    /* 沿用 Cirno：系统应用默认整体豁免，只有黑名单可以覆盖 */
    snprintf(g_cfg.system_default, sizeof(g_cfg.system_default), "%s", "protect");
    snprintf(g_cfg.user_default, sizeof(g_cfg.user_default), "%s", "manage");
}

static int ci_eq(const char *a, const char *b) { return strcasecmp(a, b) == 0; }

static void cfg_assign(const char *k, const char *v) {
    if (ci_eq(k, "ENABLE"))                     g_cfg.enable = atoi(v) != 0;
    else if (ci_eq(k, "TIMEOUT_MIN"))           g_cfg.timeout_sec = (int)(atof(v) * 60.0);
    else if (ci_eq(k, "TIMEOUT_SEC"))           g_cfg.timeout_sec = atoi(v);
    else if (ci_eq(k, "INTERVAL_MS"))           g_cfg.interval_ms = atoi(v);
    else if (ci_eq(k, "ACTIVE_ADJ"))            g_cfg.active_adj = atoi(v);
    else if (ci_eq(k, "MIN_UID"))               g_cfg.min_uid = atoi(v);
    else if (ci_eq(k, "MAX_KILL_PER_ROUND"))    g_cfg.max_kill_per_round = atoi(v);
    else if (ci_eq(k, "KILL_COOLDOWN_SEC"))     g_cfg.kill_cooldown_sec = atoi(v);
    else if (ci_eq(k, "DETECT_CGROUP_EVENTS"))  g_cfg.detect_cgroup_events = atoi(v) != 0;
    else if (ci_eq(k, "DETECT_STATE_T"))        g_cfg.detect_state_t = atoi(v) != 0;
    else if (ci_eq(k, "DRY_RUN"))               g_cfg.dry_run = atoi(v) != 0;
    else if (ci_eq(k, "DEBUG_LOG"))             g_cfg.debug_log = atoi(v) != 0;
    else if (ci_eq(k, "LOG_MAX_KB"))            g_cfg.log_max_kb = atoi(v);
    else if (ci_eq(k, "STATE_INTERVAL_SEC"))    g_cfg.state_interval_sec = atoi(v);
    else if (ci_eq(k, "CTL_PORT"))              g_cfg.ctl_port = atoi(v);
    else if (ci_eq(k, "KILL_MODE"))             snprintf(g_cfg.kill_mode,  sizeof(g_cfg.kill_mode),  "%s", v);
    else if (ci_eq(k, "KILL_SCOPE"))            snprintf(g_cfg.kill_scope, sizeof(g_cfg.kill_scope), "%s", v);
    else if (ci_eq(k, "PROTECT_FILE"))          snprintf(g_cfg.protect_file, sizeof(g_cfg.protect_file), "%s", v);
    else if (ci_eq(k, "BLACKLIST_FILE"))        snprintf(g_cfg.blacklist_file, sizeof(g_cfg.blacklist_file), "%s", v);
    else if (ci_eq(k, "SYSTEM_LIST_FILE"))      snprintf(g_cfg.system_list_file, sizeof(g_cfg.system_list_file), "%s", v);
    else if (ci_eq(k, "IMMORTAL_FILE"))        snprintf(g_cfg.immortal_file, sizeof(g_cfg.immortal_file), "%s", v);
    else if (ci_eq(k, "PERAPP_FILE"))           snprintf(g_cfg.perapp_file, sizeof(g_cfg.perapp_file), "%s", v);
    else if (ci_eq(k, "PERPROC_FILE"))          snprintf(g_cfg.perproc_file, sizeof(g_cfg.perproc_file), "%s", v);
    else if (ci_eq(k, "SYSTEM_DEFAULT"))       snprintf(g_cfg.system_default, sizeof(g_cfg.system_default), "%s", v);
    else if (ci_eq(k, "USER_DEFAULT"))         snprintf(g_cfg.user_default, sizeof(g_cfg.user_default), "%s", v);
}

int cfg_load(const char *moddir) {
    cfg_defaults();
    snprintf(g_cfgpath, sizeof(g_cfgpath), "%s/config.conf", moddir);

    FILE *f = fopen(g_cfgpath, "r");
    if (!f) return -1;

    char line[MAX_LINE];
    while (fgets(line, sizeof(line), f)) {
        char *s = trim(line);
        if (!*s || *s == '#' || *s == ';') continue;
        char *eq = strchr(s, '=');
        if (!eq) continue;
        *eq = '\0';
        char *k = trim(s);
        char *v = trim(eq + 1);
        size_t vl = strlen(v);
        if (vl >= 2 && ((v[0] == '"' && v[vl - 1] == '"') || (v[0] == '\'' && v[vl - 1] == '\''))) {
            v[vl - 1] = '\0';
            v++;
        }
        cfg_assign(k, v);
    }
    fclose(f);

    /* 数值兜底，避免手改配置写出危险值 */
    if (g_cfg.interval_ms < 200)          g_cfg.interval_ms = 200;
    if (g_cfg.interval_ms > 600000)       g_cfg.interval_ms = 600000;
    if (g_cfg.timeout_sec < 0)            g_cfg.timeout_sec = 0;
    if (g_cfg.active_adj < 0)             g_cfg.active_adj = 0;
    if (g_cfg.active_adj > 1000)          g_cfg.active_adj = 1000;
    if (g_cfg.min_uid < 1000)             g_cfg.min_uid = 1000;
    if (strcmp(g_cfg.system_default, "manage") && strcmp(g_cfg.system_default, "protect"))
        snprintf(g_cfg.system_default, sizeof(g_cfg.system_default), "%s", "protect");
    if (strcmp(g_cfg.user_default, "manage") && strcmp(g_cfg.user_default, "protect"))
        snprintf(g_cfg.user_default, sizeof(g_cfg.user_default), "%s", "manage");
    if (g_cfg.max_kill_per_round < 0)     g_cfg.max_kill_per_round = 0;
    if (g_cfg.max_kill_per_round > 50)    g_cfg.max_kill_per_round = 50;
    if (g_cfg.kill_cooldown_sec < 0)      g_cfg.kill_cooldown_sec = 0;
    if (g_cfg.log_max_kb < 16)            g_cfg.log_max_kb = 16;
    if (g_cfg.state_interval_sec < 1)     g_cfg.state_interval_sec = 1;
    if (g_cfg.ctl_port < 0)               g_cfg.ctl_port = 0;
    if (g_cfg.ctl_port > 65535)           g_cfg.ctl_port = 27183;
    if (strcmp(g_cfg.kill_mode, "sigkill") &&
        strcmp(g_cfg.kill_mode, "am_kill") &&
        strcmp(g_cfg.kill_mode, "force_stop"))
        snprintf(g_cfg.kill_mode, sizeof(g_cfg.kill_mode), "%s", "sigkill");
    if (strcmp(g_cfg.kill_scope, "pkg") && strcmp(g_cfg.kill_scope, "frozen"))
        snprintf(g_cfg.kill_scope, sizeof(g_cfg.kill_scope), "%s", "pkg");
    return 0;
}

int protect_load(const char *moddir) {
    g_protect_n = 0;
    char name[PATH_LEN];
    if (g_cfg.protect_file[0] == '/')
        snprintf(name, sizeof(name), "%s", g_cfg.protect_file);
    else
        snprintf(name, sizeof(name), "%s/%s", moddir, g_cfg.protect_file);

    FILE *f = fopen(name, "r");
    if (!f) return -1;

    char line[MAX_LINE];
    while (fgets(line, sizeof(line), f) && g_protect_n < MAX_PROTECT) {
        char *s = trim(line);
        if (!*s || *s == '#') continue;
        char *hash = strchr(s, '#');
        if (hash && hash > s && hash[-1] == ' ') { *hash = '\0'; s = trim(s); }
        if (!*s) continue;
        snprintf(g_protect[g_protect_n], PKG_LEN, "%s", s);
        g_protect_n++;
    }
    fclose(f);
    return g_protect_n;
}

/* ---- 名单：白名单 / 黑名单 / 系统应用表 ---- */

/* 名单命中（策略用）：精确 / '*' 通配 / 子进程前缀
 * cmdline 的冒号进程 com.a.b:xx 经 normalize 为 com.a.b.xx，策略上归属主包 com.a.b */
static int pkg_in_list2(char (*list)[PKG_LEN], int n, const char *pkg) {
    if (!pkg || !*pkg) return 0;
    for (int i = 0; i < n; i++) {
        size_t len = strlen(list[i]);
        if (len == 0) continue;
        if (list[i][len - 1] == '*') {
            if (strncmp(pkg, list[i], len - 1) == 0) return 1;
        } else if (strcmp(pkg, list[i]) == 0) {
            return 1;
        } else if (strncmp(pkg, list[i], len) == 0 && pkg[len] == '.') {
            return 1;
        }
    }
    return 0;
}

/* 目标包与进程归一名的匹配：精确或互为子进程前缀（杀/复查用） */
static int pkg_proc_match(const char *target, const char *proc_pkg) {
    if (!target || !*target || !proc_pkg || !*proc_pkg) return 0;
    if (strcmp(target, proc_pkg) == 0) return 1;
    size_t lt = strlen(target), lp = strlen(proc_pkg);
    if (lt < lp && strncmp(target, proc_pkg, lt) == 0 && proc_pkg[lt] == '.') return 1;
    if (lp < lt && strncmp(proc_pkg, target, lp) == 0 && target[lp] == '.') return 1;
    return 0;
}

static int list_load(const char *path, char (*list)[PKG_LEN], int *count, int max) {
    *count = 0;
    FILE *f = fopen(path, "r");
    if (!f) return -1;
    char line[MAX_LINE];
    while (fgets(line, sizeof(line), f) && *count < max) {
        char *s = trim(line);
        if (!*s || *s == '#') continue;
        char *hash = strchr(s, '#');
        if (hash && hash > s && hash[-1] == ' ') { *hash = '\0'; s = trim(s); }
        if (!*s) continue;
        snprintf(list[*count], PKG_LEN, "%s", s);
        (*count)++;
    }
    fclose(f);
    return *count;
}

static void list_path(char *out, size_t n, const char *name) {
    if (name[0] == '/') snprintf(out, n, "%s", name);
    else                snprintf(out, n, "%s/%s", g_moddir, name);
}

int blacklist_load(const char *moddir) {
    char p[PATH_LEN];
    (void)moddir;
    list_path(p, sizeof(p), g_cfg.blacklist_file);
    return list_load(p, g_blacklist, &g_blacklist_n, MAX_PROTECT);
}

/* App 生成的清单可能为空（没 root 授权、或 PackageManager 可见性受限），
 * 此时直接问 pm：pm list packages -s 就是系统应用全集。 */
static int system_list_from_pm(void) {
    FILE *p = popen("pm list packages -s 2>/dev/null", "r");
    if (!p) return -1;
    char line[MAX_LINE];
    g_systemlist_n = 0;
    while (fgets(line, sizeof(line), p) && g_systemlist_n < MAX_PROTECT) {
        char *s = trim(line);
        if (strncmp(s, "package:", 8) != 0) continue;
        snprintf(g_systemlist[g_systemlist_n], PKG_LEN, "%s", s + 8);
        g_systemlist_n++;
    }
    pclose(p);
    return g_systemlist_n;
}

/* ---- 每应用 / 每进程覆盖配置 ----
 * perapp.conf 行格式:  <包名> mode=<sigkill|am_kill|force_stop> scope=<pkg|frozen>
 * perproc.conf 行格式: <进程名> mode=<sigkill|skip>
 */
static int valid_mode(const char *m) {
    return !strcmp(m, "sigkill") || !strcmp(m, "am_kill") || !strcmp(m, "force_stop");
}

int perapp_load(const char *moddir) {
    g_perapp_n = 0;
    char p[PATH_LEN];
    list_path(p, sizeof(p), g_cfg.perapp_file);
    FILE *f = fopen(p, "r");
    if (!f) return 0;
    char line[MAX_LINE];
    while (fgets(line, sizeof(line), f) && g_perapp_n < MAX_PERAPP) {
        char *t = trim(line);
        if (!*t || *t == '#') continue;
        char pkg[PKG_LEN] = ""; char mode[SMALL_LEN] = ""; char scope[SMALL_LEN] = "";
        /* 解析: pkg [mode=X] [scope=Y] */
        char *sp = t;
        char *tok = strsep(&sp, " \t");
        if (!tok) continue;
        snprintf(pkg, sizeof(pkg), "%s", tok);
        while (sp) {
            tok = strsep(&sp, " \t");
            if (!tok || !*tok) continue;
            if (!strncasecmp(tok, "mode=", 5))  snprintf(mode, sizeof(mode), "%s", tok + 5);
            else if (!strncasecmp(tok, "scope=", 6)) snprintf(scope, sizeof(scope), "%s", tok + 6);
        }
        if (!pkg[0]) continue;
        if (mode[0] && !valid_mode(mode)) mode[0] = '\0';
        if (scope[0] && strcmp(scope, "pkg") && strcmp(scope, "frozen")) scope[0] = '\0';
        if (!mode[0] && !scope[0]) continue;
        perapp_t *e = &g_perapp[g_perapp_n++];
        snprintf(e->pkg, PKG_LEN, "%s", pkg);
        snprintf(e->mode, SMALL_LEN, "%s", mode);
        snprintf(e->scope, SMALL_LEN, "%s", scope);
    }
    fclose(f);
    return g_perapp_n;
}

int perproc_load(const char *moddir) {
    g_perproc_n = 0;
    char p[PATH_LEN];
    list_path(p, sizeof(p), g_cfg.perproc_file);
    FILE *f = fopen(p, "r");
    if (!f) return 0;
    char line[MAX_LINE];
    while (fgets(line, sizeof(line), f) && g_perproc_n < MAX_PERPROC) {
        char *t = trim(line);
        if (!*t || *t == '#') continue;
        char proc[PKG_LEN] = ""; char mode[SMALL_LEN] = "";
        char *sp = t;
        char *tok = strsep(&sp, " \t");
        if (!tok) continue;
        snprintf(proc, sizeof(proc), "%s", tok);
        while (sp) {
            tok = strsep(&sp, " \t");
            if (!tok || !*tok) continue;
            if (!strncasecmp(tok, "mode=", 5)) snprintf(mode, sizeof(mode), "%s", tok + 5);
        }
        if (!proc[0] || !mode[0]) continue;
        if (strcmp(mode, "sigkill") && strcmp(mode, "skip")) continue;
        perproc_t *e = &g_perproc[g_perproc_n++];
        snprintf(e->proc, PKG_LEN, "%s", proc);
        snprintf(e->mode, SMALL_LEN, "%s", mode);
    }
    fclose(f);
    return g_perproc_n;
}

perapp_t *perapp_lookup(const char *pkg) {
    for (int i = 0; i < g_perapp_n; i++)
        if (strcmp(g_perapp[i].pkg, pkg) == 0) return &g_perapp[i];
    return NULL;
}

perproc_t *perproc_lookup(const char *procname) {
    for (int i = 0; i < g_perproc_n; i++)
        if (strcmp(g_perproc[i].proc, procname) == 0) return &g_perproc[i];
    return NULL;
}

/* 整份重写保存（条目少，直接全量写最简单可靠） */
static int perapp_write_all(void) {
    char p[PATH_LEN];
    list_path(p, sizeof(p), g_cfg.perapp_file);
    FILE *f = fopen(p, "w");
    if (!f) return -1;
    fprintf(f, "# 每应用覆盖配置：<包名> mode=<sigkill|am_kill|force_stop> scope=<pkg|frozen>\n");
    for (int i = 0; i < g_perapp_n; i++) {
        perapp_t *e = &g_perapp[i];
        fprintf(f, "%s", e->pkg);
        if (e->mode[0])  fprintf(f, " mode=%s", e->mode);
        if (e->scope[0]) fprintf(f, " scope=%s", e->scope);
        fputc('\n', f);
    }
    fclose(f);
    return 0;
}

static int perproc_write_all(void) {
    char p[PATH_LEN];
    list_path(p, sizeof(p), g_cfg.perproc_file);
    FILE *f = fopen(p, "w");
    if (!f) return -1;
    fprintf(f, "# 每进程覆盖配置：<进程名> mode=<sigkill|skip>\n");
    for (int i = 0; i < g_perproc_n; i++)
        fprintf(f, "%s mode=%s\n", g_perproc[i].proc, g_perproc[i].mode);
    fclose(f);
    return 0;
}

int perapp_save(const char *pkg, const char *mode, const char *scope) {
    if (!pkg || !*pkg) return -1;
    if (mode && mode[0] && !valid_mode(mode)) return -1;
    if (scope && scope[0] && strcmp(scope, "pkg") && strcmp(scope, "frozen")) return -1;
    perapp_t *e = perapp_lookup(pkg);
    if ((!mode || !mode[0]) && (!scope || !scope[0])) {
        /* 两项都空 = 删除该包的覆盖 */
        if (e) {
            int i = (int)(e - g_perapp);
            memmove(&g_perapp[i], &g_perapp[i + 1], (g_perapp_n - i - 1) * sizeof(perapp_t));
            g_perapp_n--;
        }
        return perapp_write_all();
    }
    if (!e) {
        if (g_perapp_n >= MAX_PERAPP) return -1;
        e = &g_perapp[g_perapp_n++];
        memset(e, 0, sizeof(*e));
        snprintf(e->pkg, PKG_LEN, "%s", pkg);
    }
    if (mode)  snprintf(e->mode, SMALL_LEN, "%s", mode);
    if (scope) snprintf(e->scope, SMALL_LEN, "%s", scope);
    return perapp_write_all();
}

int perproc_save(const char *procname, const char *mode) {
    if (!procname || !*procname) return -1;
    perproc_t *e = perproc_lookup(procname);
    if (!mode || !mode[0]) {
        if (e) {
            int i = (int)(e - g_perproc);
            memmove(&g_perproc[i], &g_perproc[i + 1], (g_perproc_n - i - 1) * sizeof(perproc_t));
            g_perproc_n--;
        }
        return perproc_write_all();
    }
    if (strcmp(mode, "sigkill") && strcmp(mode, "skip")) return -1;
    if (!e) {
        if (g_perproc_n >= MAX_PERPROC) return -1;
        e = &g_perproc[g_perproc_n++];
        memset(e, 0, sizeof(*e));
        snprintf(e->proc, PKG_LEN, "%s", procname);
    }
    snprintf(e->mode, SMALL_LEN, "%s", mode);
    return perproc_write_all();
}

int system_list_load(const char *moddir) {
    char p[PATH_LEN];
    (void)moddir;
    list_path(p, sizeof(p), g_cfg.system_list_file);
    int n = list_load(p, g_systemlist, &g_systemlist_n, MAX_PROTECT);
    if (n <= 0) {
        n = system_list_from_pm();
        if (n > 0) log_msg("system_apps.list 为空，已用 pm list packages -s 自动补全 %d 条", n);
    }
    return n;
}

/*
 * persistent 应用清单（App 用 FLAG_PERSISTENT 生成）：
 * 这类进程被杀死后系统会立刻重新拉起，清理它们只是白费 cpu，
 * 而且会打乱应用自身的状态。默认豁免，但用户拉黑可以覆盖。
 */
int immortal_load(const char *moddir) {
    char p[PATH_LEN];
    (void)moddir;
    list_path(p, sizeof(p), g_cfg.immortal_file);
    return list_load(p, g_immortal, &g_immortal_n, MAX_PROTECT);
}

/* 当前默认输入法：Cirno 里输入法整体豁免（FreezeExemption.INPUT），
 * 这里同样豁免 —— 杀掉正在使用的输入法会让用户当场打不出字。 */
static char g_ime_pkg[PKG_LEN] = {0};

void ime_load(void) {
    g_ime_pkg[0] = '\0';
    FILE *p = popen("settings get secure default_input_method 2>/dev/null", "r");
    if (!p) return;
    char buf[256] = {0};
    if (fgets(buf, sizeof(buf), p)) {
        char *s = trim(buf);
        char *slash = strchr(s, '/');
        if (slash) *slash = '\0';
        if (*s) snprintf(g_ime_pkg, sizeof(g_ime_pkg), "%s", s);
    }
    pclose(p);
}

/* 硬保护：无论如何都不清理的核心组件（App 里标注为"系统核心，不可清理"） */
static const char *HARD_PROTECT[] = {
    "android",
    "system_server",
    "com.android.systemui",
    "com.android.phone",
    "com.android.server.telecom",
    "com.android.bluetooth",
    "com.android.permissioncontroller",
    "com.miui.home",
    /* 管理器自身：被清理后用户看不到界面也无法改配置 */
    "com.yg.appkiller",
    NULL
};

int pkg_is_hard_protected(const char *pkg) {
    for (int i = 0; HARD_PROTECT[i]; i++)
        if (strcmp(pkg, HARD_PROTECT[i]) == 0) return 1;
    return 0;
}

/*
 * 黑白名单策略（Cirno 式）：
 *   1. 硬保护        -> 永不处理
 *   2. 黑名单命中    -> 强制处理（优先级最高，系统应用也可被拉进黑名单）
 *   3. 白名单命中    -> 豁免
 *   4. 系统应用      -> 按 SYSTEM_DEFAULT（默认 protect）
 *   5. 普通应用      -> 按 USER_DEFAULT（默认 manage）
 * 注意：4 依赖 App 生成的 system_apps.list；文件缺失时系统应用也走 5，
 *       此时 protect.list 里的内置系统条目仍然兜底。
 */
int pkg_should_manage(const char *pkg) {
    if (pkg_is_hard_protected(pkg)) return 0;
    if (pkg_in_list2(g_blacklist, g_blacklist_n, pkg)) return 1;
    if (pkg_in_list2(g_protect, g_protect_n, pkg)) return 0;
    /* 当前输入法：杀掉会让用户当场无法输入（Cirno 同样整体豁免） */
    if (g_ime_pkg[0] && strcmp(pkg, g_ime_pkg) == 0) return 0;
    /* persistent 应用：杀不掉，默认豁免（黑名单仍可覆盖） */
    if (pkg_in_list2(g_immortal, g_immortal_n, pkg)) return 0;
    if (pkg_in_list2(g_systemlist, g_systemlist_n, pkg))
        return strcmp(g_cfg.system_default, "manage") == 0;
    return strcmp(g_cfg.user_default, "protect") != 0;
}

int pkg_is_protected(const char *pkg) {
    if (!pkg || !*pkg) return 0;
    for (int i = 0; i < g_protect_n; i++) {
        size_t n = strlen(g_protect[i]);
        if (n == 0) continue;
        if (g_protect[i][n - 1] == '*') {
            if (strncmp(pkg, g_protect[i], n - 1) == 0) return 1;
        } else if (strcmp(pkg, g_protect[i]) == 0) {
            return 1;
        } else if (strncmp(pkg, g_protect[i], n) == 0 && pkg[n] == '.') {
            return 1;
        }
    }
    return 0;
}

/* ============================ /proc 读取 ============================ */

int read_oom_adj(int pid) {
    char p[64];
    snprintf(p, sizeof(p), "/proc/%d/oom_score_adj", pid);
    FILE *f = fopen(p, "r");
    if (!f) return INT_MIN;
    int v = INT_MIN;
    if (fscanf(f, "%d", &v) != 1) v = INT_MIN;
    fclose(f);
    return v;
}

int read_proc_state(int pid) {
    char p[64];
    snprintf(p, sizeof(p), "/proc/%d/stat", pid);
    FILE *f = fopen(p, "r");
    if (!f) return 0;
    char buf[1024];
    size_t r = fread(buf, 1, sizeof(buf) - 1, f);
    fclose(f);
    if (r == 0) return 0;
    buf[r] = '\0';
    char *rp = strrchr(buf, ')');
    if (!rp || !rp[1]) return 0;
    char *s = rp + 1;
    while (*s == ' ') s++;
    return (int)(unsigned char)*s;
}

int read_cmdline(int pid, char *buf, size_t n) {
    char p[64];
    snprintf(p, sizeof(p), "/proc/%d/cmdline", pid);
    int fd = open(p, O_RDONLY);
    if (fd < 0) return -1;
    ssize_t r = read(fd, buf, n - 1);
    close(fd);
    if (r <= 0) return -1;
    buf[r] = '\0';
    if (buf[0] == '\0') return -1;
    return 0;
}

int read_cgroup(int pid, char *buf, size_t n) {
    buf[0] = '\0';
    char p[64];
    snprintf(p, sizeof(p), "/proc/%d/cgroup", pid);
    FILE *f = fopen(p, "r");
    if (!f) return -1;
    char line[512];
    int got = -1;
    while (fgets(line, sizeof(line), f)) {
        char *s = trim(line);
        char *d = strstr(s, "::");
        if (d) {
            snprintf(buf, n, "%s", d + 2);
            got = 0;
            break;
        }
    }
    fclose(f);
    return got;
}

int cgroup_is_frozen(const char *cgpath) {
    if (!cgpath || cgpath[0] != '/') return 0;
    char p[PATH_LEN * 2];
    snprintf(p, sizeof(p), "/sys/fs/cgroup%s/cgroup.events", cgpath);
    int fd = open(p, O_RDONLY);
    if (fd < 0) return 0;
    char buf[256];
    ssize_t r = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (r <= 0) return 0;
    buf[r] = '\0';
    int frozen = 0;
    char *ln = buf;
    while (ln && *ln) {
        char *nl = strchr(ln, '\n');
        if (nl) *nl = '\0';
        if (strncmp(ln, "frozen", 6) == 0 && atoi(ln + 6) != 0) frozen = 1;
        if (!nl) break;
        ln = nl + 1;
    }
    return frozen;
}

/* 读某个 cgroup 目录的 cgroup.freeze 位；-1 = 读不到 */
static int cgroup_freeze_flag(const char *dir) {
    if (!dir || dir[0] != '/') return -1;
    char p[PATH_LEN * 2];
    snprintf(p, sizeof(p), "/sys/fs/cgroup%s/cgroup.freeze", dir);
    int fd = open(p, O_RDONLY);
    if (fd < 0) return -1;
    char b[16];
    ssize_t r = read(fd, b, sizeof(b) - 1);
    close(fd);
    if (r <= 0) return -1;
    b[r] = '\0';
    return atoi(b) != 0;
}

/* /apps/uid_10123/pid_4567 -> /apps/uid_10123 */
static void cgroup_uid_dir(const char *cgpath, char *out, size_t n) {
    out[0] = '\0';
    if (!cgpath || cgpath[0] != '/') return;
    const char *pid = strstr(cgpath, "/pid_");
    if (!pid) return;
    size_t len = (size_t)(pid - cgpath);
    if (len == 0 || len >= n) return;
    memcpy(out, cgpath, len);
    out[len] = '\0';
}

int proc_is_frozen(int pid, const char *cgpath, int state) {
    (void)pid;
    /* 1) HyperOS greeze：整个进程被迁移到 /frozen */
    if (cgpath && cgpath[0] == '/') {
        if (strncmp(cgpath, "/frozen", 7) == 0 && (cgpath[7] == '\0' || cgpath[7] == '/'))
            return 1;
    }
    /* 2) SIGSTOP 式冻结 */
    if (g_cfg.detect_state_t && (state == 'T' || state == 't'))
        return 1;
    /* 3) cgroup v2 freezer 位（cgroup.events 里的 frozen 1） */
    if (g_cfg.detect_cgroup_events && cgroup_is_frozen(cgpath))
        return 1;
    /* 4) per-pid cgroup.freeze=1：Cirno 的 UID 模式、AOSP 原生 freezer 都是这么冻的，
     *    路径形如 /apps/uid_10123/pid_4567 或 /system/uid_1000/pid_88 */
    if (cgpath && cgpath[0] == '/') {
        if (cgroup_freeze_flag(cgpath) == 1) return 1;
        /* 5) 整 uid 冻结：uid 目录本身被置 freeze=1 */
        char udir[PATH_LEN];
        cgroup_uid_dir(cgpath, udir, sizeof(udir));
        if (udir[0] && cgroup_freeze_flag(udir) == 1) return 1;
    }
    return 0;
}

/* 进程名 -> 包名：取 basename，并截掉 :子进程 后缀 */
static void normalize_pkg(const char *raw, char *out, size_t n) {
    const char *base = raw;
    if (raw[0] == '/') {
        const char *s = strrchr(raw, '/');
        if (s) base = s + 1;
    }
    size_t i = 0;
    while (base[i] && base[i] != ':' && i + 1 < n) {
        out[i] = base[i];
        i++;
    }
    out[i] = '\0';
}

static int uid_is_isolated(int uid) {
    return uid >= 90000 && uid < 100000;
}

/* ============================ 扫描 ============================ */

static pkgent_t *find_pkg(pkgent_t *arr, int *n, const char *pkg) {
    for (int i = 0; i < *n; i++)
        if (strcmp(arr[i].pkg, pkg) == 0) return &arr[i];
    return NULL;
}

void scan_round(void) {
    pkgent_t scan[MAX_PKGS];
    int sn = 0;
    memset(scan, 0, sizeof(scan));

    long long t0 = mono_sec();
    DIR *d = opendir("/proc");
    if (!d) return;
    struct dirent *e;
    int procs = 0;

    while ((e = readdir(d)) != NULL && procs < MAX_PROCS) {
        if (e->d_name[0] < '0' || e->d_name[0] > '9') continue;
        int pid = atoi(e->d_name);
        if (pid <= 0) continue;
        procs++;

        char dpath[64];
        snprintf(dpath, sizeof(dpath), "/proc/%d", pid);
        struct stat st;
        if (stat(dpath, &st) != 0) continue;
        int uid = (int)st.st_uid;
        if (uid < g_cfg.min_uid || uid_is_isolated(uid)) continue;

        char raw[512];
        if (read_cmdline(pid, raw, sizeof(raw)) != 0) continue;
        if (raw[0] == '/') continue;              /* native 进程 */
        if (raw[0] == '[') continue;

        char pkg[PKG_LEN];
        normalize_pkg(raw, pkg, sizeof(pkg));
        if (!pkg[0]) continue;

        if (!pkg_should_manage(pkg)) continue;    /* 黑白名单 / 系统应用默认策略 */

        int adj = read_oom_adj(pid);
        if (adj == INT_MIN) continue;
        if (adj < 0) continue;                    /* persistent / system-adj */

        int state = read_proc_state(pid);
        char cg[PATH_LEN];
        if (read_cgroup(pid, cg, sizeof(cg)) != 0) cg[0] = '\0';

        int frz = proc_is_frozen(pid, cg, state);

        int k = -1;
        for (int i = 0; i < sn; i++) {
            if (strcmp(scan[i].pkg, pkg) == 0) { k = i; break; }
        }
        if (k < 0) {
            if (sn >= MAX_PKGS) continue;
            k = sn++;
            snprintf(scan[k].pkg, PKG_LEN, "%s", pkg);
            scan[k].uid = uid;
        }
        scan[k].total++;
        if (frz)                              scan[k].frozen++;
        else if (adj < g_cfg.active_adj)      scan[k].active++;
        else                                  scan[k].background++;
    }
    closedir(d);

    /* 合并到持久表 */
    for (int i = 0; i < g_pkg_n; i++) g_pkgs[i].seen = 0;
    for (int i = 0; i < sn; i++) {
        pkgent_t *pe = find_pkg(g_pkgs, &g_pkg_n, scan[i].pkg);
        int reset = (scan[i].active > 0) || (scan[i].frozen == 0);
        if (reset) {
            if (pe) pe->since = 0;
            continue;
        }
        if (!pe) {
            if (g_pkg_n >= MAX_PKGS) continue;
            pe = &g_pkgs[g_pkg_n++];
            memset(pe, 0, sizeof(*pe));
            snprintf(pe->pkg, PKG_LEN, "%s", scan[i].pkg);
        }
        pe->seen       = 1;
        pe->frozen     = scan[i].frozen;
        pe->background = scan[i].background;
        pe->active     = scan[i].active;
        pe->total      = scan[i].total;
        pe->uid        = scan[i].uid;
        if (pe->since == 0) pe->since = mono_sec();
    }

    /* 清理不再计时的条目 */
    int w = 0;
    for (int i = 0; i < g_pkg_n; i++) {
        if (g_pkgs[i].since == 0 || !g_pkgs[i].seen) continue;
        if (w != i) g_pkgs[w] = g_pkgs[i];
        w++;
    }
    g_pkg_n = w;

    if (g_cfg.debug_log) {
        long long ms = (mono_sec() - t0) * 1000;
        log_msg("scan: procs=%d pkgs=%d watching=%d cost~%lldms", procs, sn, g_pkg_n, ms);
    }
}

/* 该包当前是否存在可见进程（杀之前的最后一次确认） */
int pkg_has_visible(const char *pkg) {
    if (!pkg || !*pkg) return 0;
    DIR *d = opendir("/proc");
    if (!d) return 0;
    struct dirent *e;
    int found = 0;
    while ((e = readdir(d)) != NULL) {
        if (e->d_name[0] < '0' || e->d_name[0] > '9') continue;
        int pid = atoi(e->d_name);
        if (pid <= 0) continue;
        char raw[512];
        if (read_cmdline(pid, raw, sizeof(raw)) != 0) continue;
        if (raw[0] == '/' || raw[0] == '[') continue;
        char pk[PKG_LEN];
        normalize_pkg(raw, pk, sizeof(pk));
        if (!pkg_proc_match(pkg, pk)) continue;
        int adj = read_oom_adj(pid);
        if (adj == INT_MIN || adj < 0) continue;
        /* 冻结中的进程不可能在前台：adj 检查只约束未冻结进程 */
        int st = read_proc_state(pid);
        char cg[PATH_LEN];
        if (read_cgroup(pid, cg, sizeof(cg)) != 0) cg[0] = '\0';
        if (proc_is_frozen(pid, cg, st)) continue;
        if (adj < g_cfg.active_adj) { found = 1; break; }
    }
    closedir(d);
    return found;
}

/* ============================ 执行清理 ============================ */

static int am_command(const char *sub, const char *pkg) {
    pid_t p = fork();
    if (p == 0) {
        int nul = open("/dev/null", O_WRONLY);
        if (nul >= 0) { dup2(nul, STDOUT_FILENO); dup2(nul, STDERR_FILENO); close(nul); }
        execl("/system/bin/am", "am", sub, pkg, (char *)NULL);
        _exit(127);
    }
    if (p < 0) return 0;
    int status = 0, done = 0;
    for (int i = 0; i < 60; i++) {
        pid_t r = waitpid(p, &status, WNOHANG);
        if (r == p) { done = 1; break; }
        if (r < 0) break;
        usleep(100000);
    }
    if (!done) {
        kill(p, SIGKILL);
        waitpid(p, &status, 0);
        return 0;
    }
    return WIFEXITED(status) && WEXITSTATUS(status) == 0;
}

/* 把冻结中的 pid 移出 /frozen 解冻：冻结态下信号不投递，必须先 thaw 再 kill */
static void thaw_pid(int pid) {
    char cg[PATH_LEN];
    if (read_cgroup(pid, cg, sizeof(cg)) != 0) return;
    if (strncmp(cg, "/frozen", 7) != 0) return;
    FILE *f = fopen("/sys/fs/cgroup/unfrozen/cgroup.procs", "w");
    if (!f) return;
    fprintf(f, "%d", pid);
    fclose(f);
}

/* am/force-stop 之后兜底：仍活着的后台进程 thaw + SIGKILL */
static int sweep_survivors(const char *pkg, const char *scope, int *killed,
                           char *pidlist, size_t pidn) {
    DIR *d = opendir("/proc");
    if (!d) return 0;
    struct dirent *e;
    int any = 0;
    while ((e = readdir(d)) != NULL) {
        if (e->d_name[0] < '0' || e->d_name[0] > '9') continue;
        int pid = atoi(e->d_name);
        if (pid <= 1) continue;
        char raw[512];
        if (read_cmdline(pid, raw, sizeof(raw)) != 0) continue;
        if (raw[0] == '/') continue;
        char pk[PKG_LEN];
        normalize_pkg(raw, pk, sizeof(pk));
        if (!pkg_proc_match(pkg, pk)) continue;
        int adj = read_oom_adj(pid);
        if (adj == INT_MIN || adj < 0) continue;
        perproc_t *pp = perproc_lookup(raw);
        if (pp && !strcmp(pp->mode, "skip")) continue;
        int state = read_proc_state(pid);
        char cg[PATH_LEN];
        if (read_cgroup(pid, cg, sizeof(cg)) != 0) cg[0] = '\0';
        int frz = proc_is_frozen(pid, cg, state);
        if (!frz && adj < g_cfg.active_adj) continue;
        if (!strcmp(scope, "frozen") && !frz) continue;
        any = 1;
        if (pidlist && pidn > 1) {
            size_t used = strlen(pidlist);
            char num[12];
            snprintf(num, sizeof(num), "%d", pid);
            if (used + strlen(num) + 2 < pidn) {
                if (used) strncat(pidlist, ",", pidn - used - 1);
                strncat(pidlist, num, pidn - strlen(pidlist) - 1);
            }
        }
        thaw_pid(pid);
        if (kill(pid, SIGKILL) == 0 && killed) (*killed)++;
    }
    closedir(d);
    return any;
}

int kill_package(const char *pkg, const char *scope, const char *mode,
                 int dry_run, int *killed, char *pidlist, size_t pidn) {
    if (killed) *killed = 0;
    if (pidlist && pidn) pidlist[0] = '\0';
    if (!pkg || !*pkg) return 0;

    if (!strcmp(mode, "am_kill") || !strcmp(mode, "force_stop")) {
        if (dry_run) return 1;
        int ok = am_command(!strcmp(mode, "am_kill") ? "kill" : "force-stop", pkg);
        usleep(300000);
        /* 冻结进程收不到 am 的杀信号：兜底 thaw+SIGKILL */
        int left = sweep_survivors(pkg, scope, killed, pidlist, pidn);
        if (left) log_msg("sweep pkg=%s survivors killed=%d", pkg, killed ? *killed : 0);
        return ok || !left;
    }

    DIR *d = opendir("/proc");
    if (!d) return 0;
    struct dirent *e;
    int any = 0;

    while ((e = readdir(d)) != NULL) {
        if (e->d_name[0] < '0' || e->d_name[0] > '9') continue;
        int pid = atoi(e->d_name);
        if (pid <= 0) continue;

        char dpath[64];
        snprintf(dpath, sizeof(dpath), "/proc/%d", pid);
        struct stat st;
        if (stat(dpath, &st) != 0) continue;
        int uid = (int)st.st_uid;
        if (uid < g_cfg.min_uid || uid_is_isolated(uid)) continue;

        char raw[512];
        if (read_cmdline(pid, raw, sizeof(raw)) != 0) continue;
        if (raw[0] == '/') continue;

        char pk[PKG_LEN];
        normalize_pkg(raw, pk, sizeof(pk));
        if (!pkg_proc_match(pkg, pk)) continue;

        int adj = read_oom_adj(pid);
        if (adj == INT_MIN) continue;
        if (adj < 0) continue;

        /* 冻结判定先行：冻结中的进程不可能可见，adj 检查只约束未冻结进程
         * （HyperOS greeze 冻结不改 oom_score_adj，实测 adj=0 仍被冻） */
        int st0 = read_proc_state(pid);
        char cg0[PATH_LEN];
        if (read_cgroup(pid, cg0, sizeof(cg0)) != 0) cg0[0] = '\0';
        int frz0 = proc_is_frozen(pid, cg0, st0);
        if (!frz0 && adj < g_cfg.active_adj) continue;   /* 未冻结且可见：绝不动 */

        /* 每进程覆盖：skip = 跳过该进程 */
        perproc_t *pp = perproc_lookup(raw);
        if (pp && !strcmp(pp->mode, "skip")) continue;

        if (!strcmp(scope, "frozen") && !frz0) continue;

        any = 1;
        if (pidlist && pidn > 1) {
            size_t used = strlen(pidlist);
            char num[12];
            snprintf(num, sizeof(num), "%d", pid);
            if (used + strlen(num) + 2 < pidn) {
                if (used) strncat(pidlist, ",", pidn - used - 1);
                strncat(pidlist, num, pidn - strlen(pidlist) - 1);
            }
        }
        if (!dry_run) {
            thaw_pid(pid);
            if (kill(pid, SIGKILL) == 0 && killed) (*killed)++;
        } else if (killed) {
            (*killed)++;
        }
    }
    closedir(d);
    return any;
}

int decide_round(int dry_run) {
    long long now = mono_sec();
    int idx[MAX_PKGS];
    int n = 0;

    for (int i = 0; i < g_pkg_n; i++) {
        pkgent_t *p = &g_pkgs[i];
        if (p->since == 0) continue;
        long long dur = now - p->since;
        if (dur < g_cfg.timeout_sec) continue;
        if (!pkg_should_manage(p->pkg)) continue;
        if (p->last_kill && (now - p->last_kill) < g_cfg.kill_cooldown_sec) continue;
        idx[n++] = i;
    }
    if (n == 0) return 0;

    /* 冻结最久的先处理 */
    for (int i = 1; i < n; i++) {
        int v = idx[i], j = i - 1;
        while (j >= 0 && (now - g_pkgs[idx[j]].since) < (now - g_pkgs[v].since)) {
            idx[j + 1] = idx[j];
            j--;
        }
        idx[j + 1] = v;
    }

    int handled = 0;
    for (int i = 0; i < n; i++) {
        if (g_cfg.max_kill_per_round > 0 && handled >= g_cfg.max_kill_per_round) break;
        if (g_stop) break;

        pkgent_t *p = &g_pkgs[idx[i]];

        /* 杀之前再确认一次状态：扫描到决策之间可能已过几秒，应用刚被拉回前台就本轮放弃 */
        if (pkg_has_visible(p->pkg)) {
            log_msg("skip pkg=%s：复查发现前台进程，本轮不清理并重置计时", p->pkg);
            p->since = 0;
            continue;
        }

        long long dur = now - p->since;
        char pids[256];
        int cnt = 0;
        /* 每应用覆盖配置优先于全局 */
        const char *k_scope = g_cfg.kill_scope;
        const char *k_mode  = g_cfg.kill_mode;
        perapp_t *pa = perapp_lookup(p->pkg);
        if (pa) {
            if (pa->scope[0]) k_scope = pa->scope;
            if (pa->mode[0])  k_mode  = pa->mode;
        }
        int ok = kill_package(p->pkg, k_scope, k_mode,
                              dry_run, &cnt, pids, sizeof(pids));

        log_msg("%spkg=%s frozen=%llds>=%ds uid=%d procs=%d scope=%s -> %s(%s) pids=[%s]",
                dry_run ? "[dry-run] " : "",
                p->pkg, dur, g_cfg.timeout_sec, p->uid, p->total,
                k_scope, ok ? "match" : "no-match",
                k_mode, pids);

        p->last_kill = now;   /* dry-run 也节流，避免每轮刷同一条日志 */
        if (!dry_run) {
            p->since     = now;
            p->killed_times++;
            hist_t *h = &g_hist[g_hist_n % MAX_HISTORY];
            memset(h, 0, sizeof(*h));
            h->ts    = time(NULL);
            h->procs = p->total;
            h->ok    = ok;
            snprintf(h->pkg, PKG_LEN, "%s", p->pkg);
            snprintf(h->how, SMALL_LEN, "%s", k_mode);
            g_hist_n++;
        }
        handled++;
    }
    return handled;
}

/* ============================ 状态输出 ============================ */

static void write_watching_text(long long now) {
    FILE *f = fopen(g_watchpath, "w");
    if (!f) return;
    fprintf(f, "%-42s %4s %4s %7s %8s %s\n", "包名", "冻结", "总", "已冻结", "剩余", "保护");
    for (int i = 0; i < g_pkg_n; i++) {
        pkgent_t *p = &g_pkgs[i];
        if (p->since == 0) continue;
        long long dur = now - p->since;
        long long rem = (long long)g_cfg.timeout_sec - dur;
        if (rem < 0) rem = 0;
        fprintf(f, "%-42s %4d %4d %6llds %7llds %s\n",
                p->pkg, p->frozen, p->total, dur, rem,
                pkg_is_protected(p->pkg) ? "yes" : "");
    }
    fclose(f);
}

void write_state(void) {
    long long now = mono_sec();
    write_watching_text(now);

    char tmp[PATH_LEN + 8];
    snprintf(tmp, sizeof(tmp), "%s.tmp", g_statepath);
    FILE *f = fopen(tmp, "w");
    if (!f) return;

    fprintf(f, "{\n");
    fprintf(f, "  \"version\": \"%s\",\n", APPKILLERD_VERSION);
    fprintf(f, "  \"ts\": %ld,\n", (long)time(NULL));
    fprintf(f, "  \"uptime_sec\": %lld,\n", now - g_start_mono);
    fprintf(f, "  \"pid\": %d,\n", (int)getpid());
    fprintf(f, "  \"config\": {\"timeout_sec\": %d, \"interval_ms\": %d, \"kill_mode\": \"%s\", "
               "\"kill_scope\": \"%s\", \"dry_run\": %d, \"active_adj\": %d, \"protect_count\": %d},\n",
            g_cfg.timeout_sec, g_cfg.interval_ms, g_cfg.kill_mode,
            g_cfg.kill_scope, g_cfg.dry_run, g_cfg.active_adj, g_protect_n);

    fprintf(f, "  \"watching\": [");
    int first = 1;
    for (int i = 0; i < g_pkg_n; i++) {
        pkgent_t *p = &g_pkgs[i];
        if (p->since == 0) continue;
        long long dur = now - p->since;
        long long rem = (long long)g_cfg.timeout_sec - dur;
        if (rem < 0) rem = 0;
        fprintf(f, "%s\n    {\"pkg\": \"%s\", \"uid\": %d, \"frozen_procs\": %d, \"total_procs\": %d, "
                   "\"frozen_sec\": %lld, \"remain_sec\": %lld, \"killed_times\": %d, \"protected\": %s}",
                first ? "" : ",", p->pkg, p->uid, p->frozen, p->total,
                dur, rem, p->killed_times, pkg_is_protected(p->pkg) ? "true" : "false");
        first = 0;
    }
    fprintf(f, "%s],\n", first ? "" : "\n  ");

    fprintf(f, "  \"history\": [");
    int total = g_hist_n > MAX_HISTORY ? MAX_HISTORY : g_hist_n;
    for (int i = 0; i < total; i++) {
        hist_t *h = &g_hist[(g_hist_n - 1 - i) % MAX_HISTORY];
        fprintf(f, "%s\n    {\"ts\": %ld, \"pkg\": \"%s\", \"how\": \"%s\", \"procs\": %d, \"ok\": %s}",
                i ? "," : "", (long)h->ts, h->pkg, h->how, h->procs, h->ok ? "true" : "false");
    }
    fprintf(f, "%s]\n}\n", total ? "\n  " : "");

    fclose(f);
    rename(tmp, g_statepath);
}

/* ============================ 本地控制端口 ============================ */
/* App 通过 127.0.0.1 控制守护进程，无需 root、无需 su 子进程。
 * 请求格式：单行 "AK1 appkiller <CMD> [args]" ，应答直接写回并关闭连接。
 * 只 bind 回环地址，外部网络不可达。 */

static int ctl_fd = -1;

static void ctl_reply(int c, const char *s) {
    if (s && *s) (void)send(c, s, strlen(s), 0);
}

static void ctl_reply_file(int c, const char *path) {
    FILE *f = fopen(path, "r");
    if (!f) { ctl_reply(c, "ERR nofile\n"); return; }
    char b[4096];
    size_t r;
    while ((r = fread(b, 1, sizeof(b), f)) > 0) {
        if (send(c, b, r, 0) <= 0) break;
    }
    fclose(f);
}

/* 在 config.conf 里就地替换 key=value，保留注释与其它行 */
static int ctl_cfg_set(const char *key, const char *val) {
    static char in[16384];
    static char out[16384];
    FILE *f = fopen(g_cfgpath, "r");
    if (!f) return -1;
    size_t n = fread(in, 1, sizeof(in) - 1, f);
    fclose(f);
    in[n] = '\0';

    size_t o = 0;
    int found = 0;
    size_t kl = strlen(key);
    const char *p = in;
    while (*p) {
        const char *nl = strchr(p, '\n');
        size_t len = nl ? (size_t)(nl - p) : strlen(p);
        if (len > kl && strncmp(p, key, kl) == 0 && p[kl] == '=') {
            o += (size_t)snprintf(out + o, sizeof(out) - o, "%s=%s\n", key, val);
            found = 1;
        } else if (len > 0) {
            if (o + len + 2 >= sizeof(out)) break;
            memcpy(out + o, p, len);
            o += len;
            out[o++] = '\n';
        }
        if (!nl) break;
        p = nl + 1;
    }
    if (!found) o += (size_t)snprintf(out + o, sizeof(out) - o, "%s=%s\n", key, val);

    f = fopen(g_cfgpath, "w");
    if (!f) return -1;
    fwrite(out, 1, o, f);
    fclose(f);
    return 0;
}

static int ctl_list_path(const char *name, char *path, size_t pn) {
    if (!strcmp(name, "white"))       snprintf(path, pn, "%s/%s", g_moddir, g_cfg.protect_file);
    else if (!strcmp(name, "black"))  snprintf(path, pn, "%s/%s", g_moddir, g_cfg.blacklist_file);
    else if (!strcmp(name, "system")) snprintf(path, pn, "%s/%s", g_moddir, g_cfg.system_list_file);
    else return -1;
    return 0;
}

static int ctl_list_set(const char *name, const char *csv) {
    char path[PATH_LEN];
    if (ctl_list_path(name, path, sizeof(path)) != 0) return -1;

    FILE *f = fopen(path, "w");
    if (!f) return -1;
    fprintf(f, "# 由 App 写入：%s 名单\n", name);
    char buf[MAX_LINE * 16];
    snprintf(buf, sizeof(buf), "%s", csv);
    char *save = NULL;
    for (char *t = strtok_r(buf, ",", &save); t; t = strtok_r(NULL, ",", &save)) {
        char *s = trim(t);
        if (*s) fprintf(f, "%s\n", s);
    }
    fclose(f);
    return 0;
}

static void ctl_send_tail(int c, const char *path, int nlines) {
    FILE *f = fopen(path, "r");
    if (!f) { ctl_reply(c, "ERR nofile\n"); return; }
    static char buf[1024 * 1024];
    size_t n = fread(buf, 1, sizeof(buf) - 1, f);
    fclose(f);
    buf[n] = '\0';
    if (n == 0) { ctl_reply(c, "(日志为空)\n"); return; }
    size_t start = 0;
    int seen = 0;
    for (size_t i = n; i > 0; i--) {
        if (buf[i - 1] == '\n') {
            seen++;
            if (seen > nlines) { start = i; break; }
        }
    }
    send(c, buf + start, n - start, 0);
}

static void ctl_handle(int c, char *req) {
    static const char prefix[] = "AK1 appkiller ";
    size_t pl = sizeof(prefix) - 1;
    if (strncmp(req, prefix, pl) != 0) { ctl_reply(c, "ERR auth\n"); return; }
    char *cmd = req + pl;

    if (!strcmp(cmd, "PING")) { ctl_reply(c, "PONG " APPKILLERD_VERSION "\n"); return; }
    if (!strcmp(cmd, "STATUS")) {
        if (access(g_statepath, R_OK) != 0) write_state();
        ctl_reply_file(c, g_statepath);
        return;
    }
    if (!strcmp(cmd, "CFG"))    { ctl_reply_file(c, g_cfgpath); return; }
    if (!strncmp(cmd, "LOG", 3)) {
        int want = 60;
        if (cmd[3] == ' ') want = atoi(cmd + 4);
        if (want < 1) want = 1;
        if (want > 500) want = 500;
        ctl_send_tail(c, g_logpath, want);
        return;
    }
    if (!strcmp(cmd, "RELOAD")) { g_reload = 1; ctl_reply(c, "OK\n"); return; }

    if (!strncmp(cmd, "SETKV ", 6)) {
        char *p = cmd + 6;
        char *sp = strchr(p, ' ');
        if (!sp) { ctl_reply(c, "ERR usage\n"); return; }
        *sp = '\0';
        char *k = trim(p);
        char *v = trim(sp + 1);
        if (ctl_cfg_set(k, v) == 0) { g_reload = 1; ctl_reply(c, "OK\n"); }
        else ctl_reply(c, "ERR write\n");
        return;
    }
    if (!strncmp(cmd, "LIST ", 5)) {
        char path[PATH_LEN];
        char *name = trim(cmd + 5);
        if (ctl_list_path(name, path, sizeof(path)) != 0) { ctl_reply(c, "ERR name\n"); return; }
        ctl_reply_file(c, path);
        return;
    }
    if (!strncmp(cmd, "SETLIST ", 8)) {
        char *p = cmd + 8;
        char *sp = strchr(p, ' ');
        if (!sp) { ctl_reply(c, "ERR usage\n"); return; }
        *sp = '\0';
        char *name = trim(p);
        if (ctl_list_set(name, trim(sp + 1)) == 0) { g_reload = 1; ctl_reply(c, "OK\n"); }
        else ctl_reply(c, "ERR write\n");
        return;
    }
    if (!strncmp(cmd, "PROCS ", 6)) {
        /* 列出指定包名的所有进程：pid cmdline adj frozen perproc_mode */
        char *pkg = trim(cmd + 6);
        char buf[8192];
        int off = 0;
        DIR *d = opendir("/proc");
        if (!d) { ctl_reply(c, "ERR proc\n"); return; }
        struct dirent *de;
        while ((de = readdir(d)) != NULL && off < (int)sizeof(buf) - 256) {
            if (!isdigit((unsigned char)de->d_name[0])) continue;
            int pid = atoi(de->d_name);
            char raw[PKG_LEN];
            if (read_cmdline(pid, raw, sizeof(raw)) != 0) continue;
            char norm[PKG_LEN];
            normalize_pkg(raw, norm, sizeof(norm));
            if (strcmp(norm, pkg) != 0) continue;
            int adj = read_oom_adj(pid);
            int st = read_proc_state(pid);
            char cg[256];
            read_cgroup(pid, cg, sizeof(cg));
            int frz = proc_is_frozen(pid, cg, st);
            perproc_t *pp = perproc_lookup(raw);
            off += snprintf(buf + off, sizeof(buf) - off,
                "%d %s %d %d %s\n", pid, raw, adj, frz, pp ? pp->mode : "-");
        }
        closedir(d);
        if (off == 0) { ctl_reply(c, "OK 0\n"); return; }
        buf[off] = '\0';
        ctl_reply(c, buf);
        return;
    }
    if (!strncmp(cmd, "KILLPID ", 8)) {
        int pid = atoi(cmd + 8);
        if (pid <= 1) { ctl_reply(c, "ERR pid\n"); return; }
        int adj = read_oom_adj(pid);
        if (adj != INT_MIN && adj < g_cfg.active_adj) { ctl_reply(c, "ERR active\n"); return; }
        int r = kill(pid, SIGKILL);
        char b[64];
        snprintf(b, sizeof(b), "%s\n", r == 0 ? "OK" : "ERR kill");
        ctl_reply(c, b);
        return;
    }
    if (!strncmp(cmd, "GETPERAPP ", 10)) {
        char *pkg = trim(cmd + 10);
        perapp_t *e = perapp_lookup(pkg);
        char b[512];
        if (e) snprintf(b, sizeof(b), "OK %s %s\n", e->mode[0] ? e->mode : "-", e->scope[0] ? e->scope : "-");
        else   snprintf(b, sizeof(b), "OK - -\n");
        ctl_reply(c, b);
        return;
    }
    if (!strncmp(cmd, "SETPERAPP ", 10)) {
        /* SETPERAPP <pkg> <mode|-> <scope|-> */
        char *p2 = cmd + 10;
        char *sp1 = strchr(p2, ' ');
        if (!sp1) { ctl_reply(c, "ERR usage\n"); return; }
        *sp1 = '\0';
        char *pkg = trim(p2);
        char *rest = trim(sp1 + 1);
        char *sp2 = strchr(rest, ' ');
        char *mode = rest; char *scope = NULL;
        if (sp2) { *sp2 = '\0'; scope = trim(sp2 + 1); }
        if (mode && strcmp(mode, "-") == 0) mode = "";
        if (scope && strcmp(scope, "-") == 0) scope = "";
        if (perapp_save(pkg, mode, scope) == 0) { g_reload = 1; ctl_reply(c, "OK\n"); }
        else ctl_reply(c, "ERR write\n");
        return;
    }
    if (!strncmp(cmd, "GETPERPROC ", 11)) {
        char *proc = trim(cmd + 11);
        perproc_t *e = perproc_lookup(proc);
        char b[512];
        if (e) snprintf(b, sizeof(b), "OK %s\n", e->mode);
        else   snprintf(b, sizeof(b), "OK -\n");
        ctl_reply(c, b);
        return;
    }
    if (!strncmp(cmd, "SETPERPROC ", 11)) {
        /* SETPERPROC <procname> <mode|-> */
        char *p2 = cmd + 11;
        char *sp1 = strchr(p2, ' ');
        if (!sp1) { ctl_reply(c, "ERR usage\n"); return; }
        *sp1 = '\0';
        char *proc = trim(p2);
        char *mode = trim(sp1 + 1);
        if (strcmp(mode, "-") == 0) mode = "";
        if (perproc_save(proc, mode) == 0) { g_reload = 1; ctl_reply(c, "OK\n"); }
        else ctl_reply(c, "ERR write\n");
        return;
    }
    if (!strncmp(cmd, "KILLPKG ", 8)) {
        char *p = cmd + 8;
        char *sp2 = strchr(p, ' ');
        char *kmode = NULL;
        if (sp2) { *sp2 = '\0'; kmode = trim(sp2 + 1); }
        char *pkg = trim(p);
        const char *km = (kmode && *kmode) ? kmode : g_cfg.kill_mode;
        char pids[512];
        int cnt = 0;
        int ok = kill_package(pkg, g_cfg.kill_scope, km, 0, &cnt, pids, sizeof(pids));
        char b[700];
        snprintf(b, sizeof(b), "OK %d %s %s\n", cnt, ok ? "hit" : "miss", pids);
        ctl_reply(c, b);
        return;
    }
    ctl_reply(c, "ERR cmd\n");
}

static int ctl_init(int port) {
    if (port <= 0) return -1;
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    int on = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &on, sizeof(on));

    struct sockaddr_in a;
    memset(&a, 0, sizeof(a));
    a.sin_family = AF_INET;
    a.sin_port = htons((unsigned short)port);
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);

    if (bind(fd, (struct sockaddr *)&a, sizeof(a)) < 0) { close(fd); return -1; }
    if (listen(fd, 8) < 0) { close(fd); return -1; }
    fcntl(fd, F_SETFL, fcntl(fd, F_GETFL, 0) | O_NONBLOCK);
    return fd;
}

static void ctl_serve_one(void) {
    int c = accept(ctl_fd, NULL, NULL);
    if (c < 0) return;
    struct timeval tv;
    tv.tv_sec = 2;
    tv.tv_usec = 0;
    setsockopt(c, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    setsockopt(c, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));

    char req[8192];
    ssize_t n = recv(c, req, sizeof(req) - 1, 0);
    if (n > 0) {
        req[n] = '\0';
        char *nl = strchr(req, '\n');
        if (nl) *nl = '\0';
        ctl_handle(c, req);
    }
    close(c);
}

/* 用 poll 代替纯 sleep：等满 ms 毫秒，期间随时应答控制请求 */
static void ctl_pump(int ms) {
    if (ctl_fd < 0) {
        for (int s = 0; s < ms && !g_stop; s += 100) usleep(100000);
        return;
    }
    int remain = ms > 0 ? ms : 1;
    while (remain > 0 && !g_stop) {
        int slice = remain > 200 ? 200 : remain;
        struct pollfd pfd;
        pfd.fd = ctl_fd;
        pfd.events = POLLIN;
        pfd.revents = 0;
        int r = poll(&pfd, 1, slice);
        if (r > 0 && (pfd.revents & POLLIN)) {
            ctl_serve_one();
        } else if (r < 0 && errno == EINTR) {
            break;
        }
        remain -= slice;
    }
}

/* ============================ 子命令 ============================ */

static int read_pid(void) {
    FILE *f = fopen(g_pidpath, "r");
    if (!f) return -1;
    int pid = -1;
    if (fscanf(f, "%d", &pid) != 1) pid = -1;
    fclose(f);
    return pid;
}

static void write_pid(void) {
    FILE *f = fopen(g_pidpath, "w");
    if (!f) return;
    fprintf(f, "%d\n", (int)getpid());
    fclose(f);
}

static void remove_pid(void) {
    unlink(g_pidpath);
}

int cmd_version(void) {
    printf("appkillerd %s\n", APPKILLERD_VERSION);
    return 0;
}

int cmd_status(void) {
    cfg_defaults();
    cfg_load(g_moddir);
    protect_load(g_moddir);

    printf("appkillerd %s\n", APPKILLERD_VERSION);
    printf("模块目录 : %s\n", g_moddir);
    printf("配置     : timeout=%ds interval=%dms mode=%s scope=%s dry_run=%d active_adj=%d\n",
           g_cfg.timeout_sec, g_cfg.interval_ms, g_cfg.kill_mode, g_cfg.kill_scope,
           g_cfg.dry_run, g_cfg.active_adj);
    printf("保护条目 : %d\n", g_protect_n);

    int dp = read_pid();
    if (dp > 0 && kill(dp, 0) == 0) printf("守护进程 : 运行中 (pid %d)\n", dp);
    else                            printf("守护进程 : 未运行\n");

    int age = file_age_sec(g_statepath);
    if (age >= 0 && age <= 60) {
        printf("\n--- 守护进程实时视图（%ds 前刷新）---\n", age);
        FILE *f = fopen(g_watchpath, "r");
        if (f) {
            char line[512];
            while (fgets(line, sizeof(line), f)) fputs(line, stdout);
            fclose(f);
        }
        return 0;
    }

    printf("\n--- daemon 未在运行，现场扫描一次 ---\n");
    g_start_mono = mono_sec();
    scan_round();
    long long now = mono_sec();
    printf("%-42s %4s %4s %8s %s\n", "包名", "冻结", "总", "剩余", "保护");
    int n = 0;
    for (int i = 0; i < g_pkg_n; i++) {
        pkgent_t *p = &g_pkgs[i];
        if (p->since == 0) continue;
        printf("%-42s %4d %4d %7ds %s\n",
               p->pkg, p->frozen, p->total, g_cfg.timeout_sec,
               pkg_is_protected(p->pkg) ? "yes" : "");
        n++;
    }
    if (n == 0) printf("（当前没有处于冻结状态的应用）\n");
    (void)now;
    return 0;
}

int cmd_once(int dry_run) {
    cfg_defaults();
    cfg_load(g_moddir);
    protect_load(g_moddir);
    g_start_mono = mono_sec();
    scan_round();

    printf("appkillerd %s  once%s\n", APPKILLERD_VERSION, dry_run ? " (dry-run)" : "");
    printf("阈值 : %ds  间隔 : %dms  scope=%s  mode=%s\n\n",
           g_cfg.timeout_sec, g_cfg.interval_ms, g_cfg.kill_scope, g_cfg.kill_mode);
    printf("%-42s %4s %4s %8s %s\n", "包名", "冻结", "总", "已观测", "保护");

    int n = 0;
    for (int i = 0; i < g_pkg_n; i++) {
        pkgent_t *p = &g_pkgs[i];
        if (p->since == 0) continue;
        printf("%-42s %4d %4d %6llds %s\n", p->pkg, p->frozen, p->total,
               mono_sec() - p->since, pkg_is_protected(p->pkg) ? "yes" : "");
        n++;
    }
    if (n == 0) printf("（当前没有处于冻结状态的应用）\n");
    printf("\n共 %d 个冻结应用\n", n);
    return 0;
}

int cmd_kill(const char *pkg, int dry_run) {
    cfg_defaults();
    cfg_load(g_moddir);
    protect_load(g_moddir);

    if (!pkg || !*pkg) { printf("用法: appkillerd kill <包名> [--dry-run]\n"); return 2; }
    if (pkg_is_protected(pkg)) printf("注意: %s 在 protect.list 中，手动清理照常执行\n", pkg);

    char pids[512];
    int cnt = 0;
    int ok = kill_package(pkg, g_cfg.kill_scope, g_cfg.kill_mode, dry_run, &cnt, pids, sizeof(pids));
    printf("%s %s -> %s, %d 个进程 [%s]\n",
           dry_run ? "[dry-run]" : "[kill]", pkg,
           ok ? "命中" : "未命中", cnt, pids);
    return ok ? 0 : 1;
}

/* ============================ 守护进程 ============================ */

static void on_sig(int sig) {
    (void)sig;
    g_stop = 1;
}

static void on_hup(int sig) {
    (void)sig;
    g_reload = 1;
}

static void resolve_moddir(char *buf, size_t n) {
    if (buf[0]) return;
    char exe[PATH_LEN];
    ssize_t r = readlink("/proc/self/exe", exe, sizeof(exe) - 1);
    if (r <= 0) { snprintf(buf, n, "/data/adb/modules/appkiller"); return; }
    exe[r] = '\0';
    char *slash = strrchr(exe, '/');
    if (slash) *slash = '\0';
    size_t len = strlen(exe);
    if (len > 4 && strcmp(exe + len - 4, "/bin") == 0) exe[len - 4] = '\0';
    snprintf(buf, n, "%s", exe);
}

static int run_daemon(void) {
    cfg_load(g_moddir);
    protect_load(g_moddir);
    blacklist_load(g_moddir);
    system_list_load(g_moddir);
    immortal_load(g_moddir);
    perapp_load(g_moddir);
    perproc_load(g_moddir);
    ime_load();
    ensure_dirs();
    log_open();

    snprintf(g_statepath, sizeof(g_statepath), "%s/run/state.json", g_moddir);
    snprintf(g_watchpath, sizeof(g_watchpath), "%s/run/watching.txt", g_moddir);
    snprintf(g_pidpath,   sizeof(g_pidpath),   "%s/run/appkillerd.pid", g_moddir);
    snprintf(g_logpath,   sizeof(g_logpath),   "%s/logs/daemon.log", g_moddir);

    signal(SIGTERM, on_sig);
    signal(SIGINT,  on_sig);
    signal(SIGHUP,  on_hup);
    signal(SIGPIPE, SIG_IGN);

    g_start_mono = mono_sec();
    write_pid();

    ctl_fd = ctl_init(g_cfg.ctl_port);

    log_msg("start v%s timeout=%ds interval=%dms mode=%s scope=%s dry_run=%d active_adj=%d protect=%d ctl=%d",
            APPKILLERD_VERSION, g_cfg.timeout_sec, g_cfg.interval_ms, g_cfg.kill_mode,
            g_cfg.kill_scope, g_cfg.dry_run, g_cfg.active_adj, g_protect_n,
            ctl_fd >= 0 ? g_cfg.ctl_port : 0);

    long long last_state = 0;
    while (!g_stop) {
        if (g_reload) {
            g_reload = 0;
            cfg_load(g_moddir);
            protect_load(g_moddir);
            blacklist_load(g_moddir);
            system_list_load(g_moddir);
            immortal_load(g_moddir);
            perapp_load(g_moddir);
            perproc_load(g_moddir);
            ime_load();
            log_msg("reload config: timeout=%ds interval=%dms mode=%s scope=%s dry_run=%d protect=%d black=%d system=%d immortal=%d perapp=%d perproc=%d ime=%s",
                    g_cfg.timeout_sec, g_cfg.interval_ms, g_cfg.kill_mode, g_cfg.kill_scope,
                    g_cfg.dry_run, g_protect_n, g_blacklist_n, g_systemlist_n, g_immortal_n,
                    g_perapp_n, g_perproc_n, g_ime_pkg);
        }

        if (g_cfg.enable) {
            scan_round();
            decide_round(g_cfg.dry_run);
        }

        long long now = mono_sec();
        int si = g_cfg.state_interval_sec > 0 ? g_cfg.state_interval_sec : 5;
        if (now - last_state >= si) {
            write_state();
            last_state = now;
        }

        int ms = g_cfg.interval_ms > 0 ? g_cfg.interval_ms : 2000;
        ctl_pump(ms);
    }

    if (ctl_fd >= 0) close(ctl_fd);
    log_msg("stop");
    remove_pid();
    log_close();
    return 0;
}

int main(int argc, char **argv) {
    const char *cmd = NULL;
    const char *pkgarg = NULL;
    char moddir[PATH_LEN] = "";
    int  dry = 0;

    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "-m") && i + 1 < argc) {
            snprintf(moddir, sizeof(moddir), "%s", argv[++i]);
        } else if (!strcmp(argv[i], "--dry-run")) {
            dry = 1;
        } else if (!strcmp(argv[i], "-v") || !strcmp(argv[i], "--version")) {
            cmd = "version";
        } else if (argv[i][0] != '-') {
            if (!strcmp(argv[i], "daemon") || !strcmp(argv[i], "status") ||
                !strcmp(argv[i], "once")   || !strcmp(argv[i], "kill")   ||
                !strcmp(argv[i], "version")) {
                cmd = argv[i];
            } else {
                pkgarg = argv[i];
            }
        }
    }
    if (!cmd) cmd = pkgarg ? "kill" : "daemon";

    resolve_moddir(moddir, sizeof(moddir));
    snprintf(g_moddir, sizeof(g_moddir), "%s", moddir);
    snprintf(g_cfgpath, sizeof(g_cfgpath), "%s/config.conf", g_moddir);
    snprintf(g_logpath, sizeof(g_logpath), "%s/logs/daemon.log", g_moddir);
    snprintf(g_statepath, sizeof(g_statepath), "%s/run/state.json", g_moddir);
    snprintf(g_watchpath, sizeof(g_watchpath), "%s/run/watching.txt", g_moddir);
    snprintf(g_pidpath, sizeof(g_pidpath), "%s/run/appkillerd.pid", g_moddir);

    if (!strcmp(cmd, "version")) return cmd_version();
    if (!strcmp(cmd, "status"))  return cmd_status();
    if (!strcmp(cmd, "once"))    return cmd_once(dry);
    if (!strcmp(cmd, "kill"))    return cmd_kill(pkgarg, dry);
    return run_daemon();
}
