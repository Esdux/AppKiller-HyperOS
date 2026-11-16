/*
 * appkillerd - HyperOS/MIUI 后台冻结超时清理守护进程
 *
 * 原理：HyperOS 的 greeze 会把判定为可回收的后台进程整体迁移到
 *       /sys/fs/cgroup/frozen 并置 cgroup.freeze=1。
 *       守护进程持续读取 /proc/<pid>/cgroup 判定冻结状态，
 *       冻结持续超过阈值且期间从未回到可见/前台，则主动清理该应用。
 *
 *       任一时刻该应用出现可见进程（oom_score_adj < ACTIVE_ADJ）或
 *       被解冻，计时立即归零 —— 即"中间使用过就重置时间"。
 */
#ifndef APPKILLERD_H
#define APPKILLERD_H

#include <stddef.h>

#define APPKILLERD_VERSION "1.0"

#define MAX_PROCS      4096
#define MAX_PKGS        768
#define MAX_PROTECT    1024
#define MAX_HISTORY      32
#define MAX_LINE        512
#define PKG_LEN          96
#define PATH_LEN        320
#define SMALL_LEN        32

typedef struct {
    int  enable;
    int  timeout_sec;
    int  interval_ms;
    int  active_adj;
    int  min_uid;
    int  max_kill_per_round;
    int  kill_cooldown_sec;
    int  detect_cgroup_events;
    int  detect_state_t;
    int  dry_run;
    int  debug_log;
    int  log_max_kb;
    int  state_interval_sec;
    int  ctl_port;                /* 本地控制端口（127.0.0.1），0 = 关闭 */
    char kill_mode[SMALL_LEN];    /* sigkill | am_kill | force_stop */
    char kill_scope[SMALL_LEN];   /* pkg | frozen */
    char protect_file[PATH_LEN];
    char blacklist_file[PATH_LEN];
    char system_list_file[PATH_LEN];
    char immortal_file[PATH_LEN];    /* persistent 应用清单：系统会自拉起，不清理 */
    char system_default[SMALL_LEN];
    char user_default[SMALL_LEN];
} config_t;

/* 单个包的观测结果 */
typedef struct {
    char      pkg[PKG_LEN];
    int       frozen;      /* 处于冻结状态的进程数 */
    int       active;      /* 可见/前台进程数 (adj < active_adj) */
    int       background;  /* 后台但未冻结的进程数 */
    int       total;
    int       uid;
    long long since;       /* 首次观测到冻结的单调时钟秒；0 = 未计时 */
    long long last_kill;
    int       killed_times;
} pkgent_t;

typedef struct {
    long long ts;
    char      pkg[PKG_LEN];
    char      how[SMALL_LEN];
    int       procs;
    int       ok;
} hist_t;

extern config_t g_cfg;
extern char     g_moddir[PATH_LEN];

/* ---- 日志 ---- */
void log_open(void);
void log_close(void);
void log_msg(const char *fmt, ...);

/* ---- 配置 ---- */
void cfg_defaults(void);
int  cfg_load(const char *moddir);
int  protect_load(const char *moddir);
int  pkg_is_protected(const char *pkg);
int  blacklist_load(const char *moddir);
int  system_list_load(const char *moddir);
int  immortal_load(const char *moddir);
int  pkg_should_manage(const char *pkg);
int  pkg_is_hard_protected(const char *pkg);

/* ---- /proc 读取 ---- */
long long mono_sec(void);
int  read_oom_adj(int pid);
int  read_proc_state(int pid);
int  read_cmdline(int pid, char *buf, size_t n);
int  read_cgroup(int pid, char *buf, size_t n);
int  cgroup_is_frozen(const char *cgpath);
int  proc_is_frozen(int pid, const char *cgpath, int state);

/* ---- 扫描 / 决策 ---- */
void scan_round(void);
int  decide_round(int dry_run);
int  kill_package(const char *pkg, const char *scope, const char *mode,
                  int dry_run, int *killed, char *pidlist, size_t pidn);
void write_state(void);

/* ---- 子命令 ---- */
int  cmd_status(void);
int  cmd_once(int dry_run);
int  cmd_kill(const char *pkg, int dry_run);
int  cmd_version(void);

#endif /* APPKILLERD_H */
