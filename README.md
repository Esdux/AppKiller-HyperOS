# AppKiller — HyperOS 后台冻结超时清理

被系统冻结之后还继续占着 uid、进程槽和内存的后台应用，冻结满阈值就清掉；中途只要被用过就重新计时。

判定直接读 cgroup，不 hook、不注入、不依赖 LSPosed。策略语义与 [Cirno](https://github.com/Adkimsm/Cirno) 对齐。

## 为什么必须读 cgroup

HyperOS（澎湃 OS）的 greeze 与 AOSP 的 freezer 不是一回事：

| 位置 | 含义 |
| --- | --- |
| `/sys/fs/cgroup/apps/uid_<uid>/pid_<pid>` | 普通后台进程 |
| `/sys/fs/cgroup/system/...` | 系统进程 |
| `/sys/fs/cgroup/frozen` | **被冻结的进程整体迁移到这里** |
| `/sys/fs/cgroup/frozen/cgroup.freeze` | 全局冻结开关，冻结时为 1 |

本机实测（Redmi K50 / Android 17 / OS4.0.0.14）：被冻结的 `com.netease.cloudmusic` 的 `/proc/<pid>/cgroup` 显示 `0::/frozen`，utime/stime 三秒内完全停滞（973124 → 973124），但 `/proc/<pid>/stat` 的状态字符仍然是 `S`。

所以「按 State:T 判断冻结」在 HyperOS 上恒为假。daemon 保留三路判定：

1. cgroup 路径前缀 `/frozen`（HyperOS greeze，主路径）
2. 同路径下 `cgroup.events` 含 `frozen 1`（cgroup v2 标准 freezer）
3. `/proc/<pid>/stat` 状态为 `T`/`t`（SIGSTOP 式冻结）

## 计时语义

- 包内出现可见进程（`oom_score_adj < ACTIVE_ADJ`，默认 200）→ 计时清零
- 包内进程被解冻 → 计时清零
- 全部进程在后台且至少一个处于冻结 → 开始计时
- 连续冻结时长 ≥ `TIMEOUT_MIN` → 执行清理

即「中间用过就重置」。实测证据：观察窗口内 `com.tencent.mm` 的剩余时间从 27s 变回 63s，说明回到前台确实重置了计时。

## 名单策略（与 Cirno 一致）

判定优先级，从高到低：

1. **硬保护**（代码内置，任何配置不可覆盖）：`android`、`system_server`、`com.android.systemui`、`com.android.phone`、`com.android.server.telecom`、`com.android.bluetooth`、`com.android.permissioncontroller`、`com.miui.home`、`com.yg.appkiller`（管理器自身）
2. **黑名单** `blacklist.list` → 强制参与清理（可覆盖下面的所有豁免）
3. **白名单** `protect.list` → 豁免。**整份沿用 Cirno 的 `CommonConstants.whiteApps`**（约 90 条，含中文注释：小米/OPPO/一加/真我/三星/谷歌系与各家 Magisk 管理器）
4. **当前输入法** → 豁免（Cirno 的 `FreezeExemption.INPUT`；杀掉正在用的输入法会让用户当场打不出字）。daemon 用 `settings get secure default_input_method` 取包名，随配置热重载刷新
5. **persistent 应用** `immortal.list` → 豁免（`android:persistent=true`，杀了系统会立刻拉起，清理只是白费 cpu）
6. **系统应用** `system_apps.list` → 按 `SYSTEM_DEFAULT`（默认 `protect`，即**整体豁免**，与 Cirno 的 `FreezeExemption.SYSTEM` 一致）
7. **普通应用** → 按 `USER_DEFAULT`（默认 `manage`，即默认纳入清理）

`system_apps.list` 与 `immortal.list` 由管理器 App 用 `pm list packages` + `PackageManager` 生成；清单为空时 daemon 会自己跑一次 `pm list packages -s` 兜底（HyperOS 4 上 `QUERY_ALL_PACKAGES` 并不保证 PackageManager 能枚举全量，必须以 pm 输出为准）。

想清理某个被豁免的系统应用，把它写进 `blacklist.list`：黑名单优先级高于系统应用豁免。

## 目录结构

```
appkiller-hyperos/
├── src/appkillerd.c       守护进程（单文件）
├── src/appkillerd.h       配置结构与函数声明
├── module/                KernelSU / Magisk 模块
│   ├── module.prop        v1.1 / versionCode 2
│   ├── config.conf        全部可调参数（热重载）
│   ├── protect.list       Cirno 默认白名单
│   ├── blacklist.list     黑名单
│   ├── service.sh         开机启动 + 首次自动装管理器 APK
│   ├── action.sh          模块管理器「执行」按钮入口
│   ├── webroot/           KernelSU WebUI 页面
│   └── bin/               appkillerd + appkiller.apk
├── app/                   管理器 App（Kotlin + Compose + Miuix）
├── build.sh               编译 daemon + 打包模块 zip
└── LICENSE                GPL-3.0
```

## 管理器 App

Miuix（`top.yukonga.miuix.kmp:miuix-ui`）四页界面，布局与 [Cirno](https://github.com/Adkimsm/Cirno) 一致：

| 页 | 内容 |
| --- | --- |
| **主界面** | 守护进程状态（绿/红点 + pid）、清理阈值、清理方式与范围、冻结判定方式、运行时长、启动/重启与停止按钮、统计（正在计时数、被冻结进程数、最久冻结、累计清理） |
| **运行列表** | 只列当前真正「被冻结且正在计时」的应用：应用名 / 包名 / 剩余秒数 / 冻结进度条 / 冻结 x/y 进程 · 已冻结秒数 / 已清理次数，可搜索 |
| **应用列表** | 单开关列表：**开关开启 = 白名单，关闭 = 参与清理**（Cirno 的 `white_app` 语义）。顶部三档（全部 / 白名单 / 系统），可搜索，底部「保存并重载」写盘并触发 daemon 重载 |
| **设置** | 总开关、观察模式、调试日志、阈值、扫描间隔、清理方式与范围、默认策略、节流参数、日志尾部 |

开关语义速记：**应用列表里打开开关，就是把该应用加入白名单，它永远不会被清理；关掉就按黑名单 / persistent / 系统应用 / 默认策略继续判定。**

数据通道双路：

- **root 通道（首选）**：一次 `su` 批量读完 `run/state.json` + `config.conf` + 三份名单；写文件用 `base64 -d` 绕开 shell 转义
- **本地控制通道（兜底）**：daemon 监听 `127.0.0.1:27183`，单行协议 `AK1 appkiller <CMD>`，支持 `PING / STATUS / CFG / LOG / LIST / SETLIST / SETKV / RELOAD / KILLPKG`。没拿到 root 时自动走这条

## 配置项

改完不用重启（下一轮热重载），也可 `sh /data/adb/modules/appkiller/action.sh reload`。

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `ENABLE` | 1 | 总开关 |
| `TIMEOUT_MIN` | 30 | 冻结多少分钟后清理，可写小数（0.5 = 30 秒） |
| `INTERVAL_MS` | 2000 | 扫描间隔（最小 200） |
| `KILL_MODE` | sigkill | `sigkill` / `am_kill` / `force_stop` |
| `KILL_SCOPE` | pkg | `pkg` 整个应用 / `frozen` 只清被冻结的进程 |
| `DRY_RUN` | 0 | 1 = 只记日志不杀 |
| `ACTIVE_ADJ` | 200 | 低于此 adj 视为可见，立刻重置计时 |
| `MAX_KILL_PER_ROUND` | 3 | 每轮最多清理几个 |
| `KILL_COOLDOWN_SEC` | 180 | 同一应用两次清理最短间隔 |
| `PROTECT_FILE` | protect.list | 白名单 |
| `BLACKLIST_FILE` | blacklist.list | 黑名单 |
| `SYSTEM_LIST_FILE` | system_apps.list | 系统应用清单 |
| `IMMORTAL_FILE` | immortal.list | persistent 应用清单 |
| `SYSTEM_DEFAULT` | protect | 系统应用默认策略（Cirno 语义：豁免） |
| `USER_DEFAULT` | manage | 普通应用默认策略 |
| `CTL_PORT` | 27183 | 本地控制端口，0 = 关闭 |

## 安装

1. KernelSU / Magisk 管理器刷入 `dist/appkiller-v1.1.zip`
2. 重启（或 `sh /data/adb/modules/appkiller/action.sh start`）
3. 开机后模块自动安装管理器 App；也可从 KernelSU 打开 WebUI
4. 首次进 App 会弹一次 KernelSU 授权，允许后名单与状态才能直读；不授权时走本地控制端口，功能仍在

上手建议先 `DRY_RUN=1` 跑一天，确认判定符合预期再关掉。

## 构建

```sh
./build.sh                      # 守护进程 + 模块 zip

cd app
JAVA_HOME=/path/to/jdk21 ANDROID_HOME=/path/to/android-sdk \
  gradle :app:assembleDebug     # 管理器 APK
```

依赖来源：androidx 走阿里云镜像 / Maven Central；miuix 默认从 Maven Central 解析（`top.yukonga.miuix.kmp:miuix-ui` 等）。

如果本机有 miuix 的离线仓库，可用环境变量注入，构建脚本会在仓库列表里追加它：

```sh
export MIUIX_REPO=/path/to/android-compose-libs
```

不设置时完全走远程仓库，因此**仓库里不含任何机器专属路径**。

## 已知限制

- 只处理「已冻结」的应用；系统压根不冻的常驻进程不在范围内（那属于保活治理）
- Cirno 的运行时豁免（播放音频、定位、录音、相机、悬浮窗、VPN、网速、无障碍、自动填充、通行密钥、等待推送响应）依赖 hook，本模块只能覆盖「前台可见 + 输入法 + persistent + 系统应用」这几类
- `force_stop` 模式下被强停的应用无法自拉起，依赖推送的应用可能收不到消息
- 硬保护名单内的包即使用户拉黑也不会被处理

## 许可

GPL-3.0。名单策略与冻结判定语义参考 [Cirno](https://github.com/Adkimsm/Cirno)（GPL-3.0），故本项目同样以 GPL-3.0 发布。

## 参考项目

- [Cirno](https://github.com/Adkimsm/Cirno) — 名单语义、豁免判定顺序、界面结构、cgroup 冻结后端
- [NoActive](https://github.com/cn.myflv.noactive) — 冻结/解冻触发时机
- [Thanox](https://github.com/Tornaco/Thanox) — 场景与后台治理思路
- [Miuix](https://github.com/compose-miuix-ui/miuix) — Compose 风格的 HyperOS 组件库
