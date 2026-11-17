/*
 * declprocs.h - 从 APK 的 AndroidManifest.xml 里读出「声明过的进程名」
 *
 * 为什么需要这个东西：
 *   Cirno 的 getProcessesForApp() 走 PackageManager.getPackageInfoAsUser(pkg,
 *   GET_ACTIVITIES|GET_SERVICES|GET_RECEIVERS|GET_PROVIDERS, userId)，把
 *   ApplicationInfo.processName 与四类组件各自的 processName 收进一个集合。
 *   这份集合是「清单里声明过的进程名」，与进程当前是否在运行无关 ——
 *   所以应用没启动也能看到完整的进程表。
 *
 *   我们是 native root 进程，调不到 PackageManager。等价做法是直接解析 APK 里的
 *   二进制 AndroidManifest.xml（AXML）：读 ZIP 中央目录找到该条目、按需 inflate、
 *   再按 AXML 的 chunk 结构取 android:process 属性。产物与 Cirno 完全一致。
 */
#ifndef APPKILLERD_DECLPROCS_H
#define APPKILLERD_DECLPROCS_H

#include <stddef.h>

/* 返回该包在清单里声明的进程名个数；名字写进 out（每个槽位 name_len 字节），
 * 主进程（即包名本身）排在第一个。返回 -1 表示读不到清单（APK 路径找不到、
 * 解压失败等），调用方应当退回到「只列运行中的进程」。 */
int decl_procs_for_pkg(const char *pkg, char *out, int name_len, int max);

#endif