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
6. **系统应用** → 按 `SYSTEM_DEFAULT`（默认 `protect`，即**整体豁免**，与 Cirno 的 `FreezeExemption.SYSTEM` 一致）。判定三路取并集，任一路命中即算系统应用：① 运行时 `uid < 10000`（等价 Cirno 的 `ApplicationInfo.uid < Process.FIRST_APPLICATION_UID`，直接读 `/proc/<pid>` 属主 uid，**不依赖任何清单文件**）；② 命中 App 生成的 `system_apps.list`；③ 命中 daemon 启动时 `pm list packages -s` 的兜底结果
7. **普通应用** → 按 `USER_DEFAULT`（默认 `manage`，即默认纳入清理）

`system_apps.list` 与 `immortal.list` 由管理器 App 在冷启动时于 IO 线程生成并触发 RELOAD。系统应用的判定不能只看 `getApplicationInfo(pkg).flags`：HyperOS 4 上包可见性受限时它返回 null，flags 与 sourceDir 全为空，系统应用会被误判成普通应用 —— 一旦按误判结果写进清单，daemon 的系统豁免就形同虚设。所以 App 侧也取三路并集：`pm list packages -s` 的直接输出、`ApplicationInfo.uid < 10000`、`FLAG_SYSTEM` 或安装路径不在 `/data` 下。

清单为空时 daemon 仍会自己跑一次 `pm list packages -s` 兜底（HyperOS 4 上 `QUERY_ALL_PACKAGES` 并不保证 PackageManager 能枚举全量，必须以 pm 输出为准）。

想清理某个被豁免的系统应用，把它写进 `blacklist.list`：黑名单优先级高于系统应用豁免。

## 目录结构

```
appkiller-hyperos/
├── src/appkillerd.c       守护进程（单文件）
├── src/appkillerd.h       配置结构与函数声明
├── module/                KernelSU / Magisk 模块
│   ├── module.prop        v1.3 / versionCode 13
│   ├── config.conf        全部可调参数（热重载）
│   ├── protect.list       Cirno 默认白名单
│   ├── blacklist.list     黑名单
│   ├── service.sh         开机启动（管理器 APK 在刷入时已装）
│   ├── action.sh          模块管理器「执行」按钮入口
│   ├── webroot/           KernelSU WebUI 页面
│   └── bin/               appkillerd + appkiller.apk
├── app/                   管理器 App（Kotlin + Compose + Miuix，按 miuix-compose-ui skill 模板搭建）
│   └── app/src/main/java/com/yg/appkiller/   四页 + ui/（壳、页面、数据层、工具）
├── out/appkillerd         daemon 编译产物
└── dist/                  模块 zip
└── LICENSE                GPL-3.0
```

## 管理器 App

界面层按 miuix-compose-ui skill 的模板从零搭建，组件来自 [Miuix](https://github.com/compose-miuix-ui/miuix) 0.9.4；
页面结构与间距/字号阶梯沿用 skill 的 DesignTokens（2/4/8/12/16dp 五档间距、MiuixTheme.textStyles 全部字号），
卡片分组语义照 [Cirno](https://github.com/Adkimsm/Cirno)，底栏两态（贴底 NavigationBar / 悬浮 FloatingNavigationBar + 纹理模糊）。

四个标签：

| 标签 | 内容 |
| --- | --- |
| **主页** | 状态卡三档配色（守护中 / 未运行 / 无 root）+ 守护状态、已运行、已杀死、白名单数、黑名单数五条数据 + 设备型号 / 系统版本 |
| **应用** | 447 项全量应用列表：58dp 图标 / 应用名 / 包名 / 系统标签；搜索 + 右上角筛选弹层（分类单选 + 白名单/黑名单多选并集），点进二级页 |
| **运行列表** | 全部后台应用，分两段。上段「即将去死」= 已冻结且正在计时：图标 / 名称 / 已冻结时长 + 剩余时间 / 包名 / `已冻结/总` 进程标签；下段「未冻结」= 系统尚未回收、不参与清理，也一并列出（否则「为什么还这么卡」没有答案） |
| **设置** | 守护进程（状态 / 启用开关 / 重启）、清理规则（后台超时 / 清理方式 / 仅演练）、界面（主题与外观）、配置备份（导出 / 导入 / 导入 Cirno 名单）、日志、危险操作 |

### 二级页

`AppDetailActivity` 六组：身份行（72dp 图标 + 名称 + 包名）→ 名单（白 / 黑 SwitchPreference，互斥写盘）→
清理方式（per-app 下拉：跟随全局 / sigkill / am kill / force stop）→ 当前进程（每 2s 刷新，逐进程可单独设
跟随应用 / sigkill / am kill / force stop / 跳过不清理，点击立即按该档清理）→ 系统（跳系统应用信息）→ 危险操作（立即清理）。

### 代码结构（`app/app/src/main/java/com/yg/appkiller/`）

| 位置 | 说明 |
| --- | --- |
| `ui/AppShellActivity.kt` / `AppShell.kt` | 入口与四标签壳：`HorizontalPager`（`beyondViewportPageCount = 1`）+ 两态底栏 |
| `ui/util/PageScaffold.kt` | 页面骨架：`Scaffold` + `contentWindowInsets`（只取左右）+ `BlurredBar` 顶栏 + `LazyColumn` |
| `ui/util/BlurUtils.kt` | 渐进模糊顶栏（`progressiveTextureBlur` + `TopBarBlurConfig`）与 `rememberBlurBackdrop` |
| `ui/util/DesignTokens.kt` / `CardSpacing.kt` | 间距阶梯与卡片边距（`firstCard()` / `nextCard()`） |
| `ui/theme/MiuixAppTheme.kt` | 主题、动态取色、深浅色判定 |
| `ui/data/DaemonStateViewModel.kt` | 1s 轮询的守护状态流（`flowOn(Dispatchers.IO)` + `stateIn(WhileSubscribed)`） |
| `ui/screens/HomePage.kt` / `AppListPage.kt` / `RunningPage.kt` / `SettingsPage.kt` | 四页 |
| `ui/screens/AppListViewModel.kt` | 双流派生（全量 + 搜索词 + 分类 + 白/黑两布尔取并集），图标解码走 LRU（400 项） |
| `ui/screens/AppDetailActivity.kt` / `ThemeActivity.kt` / `CirnoImportDialog.kt` | 二级页、主题页、Cirno 名单候选框 |
| `Ctrl.kt` / `Repos.kt` | 数据通道：控制端口协议封装 + root 直读文件 |

minSdk 为 **33**（`miuix-blur-android:0.9.4` 的 manifest 要求）。

数据通道双路：

- **本地控制通道（首选）**：daemon 监听 `127.0.0.1:27183`，单行协议 `AK1 appkiller <CMD>`，支持 `PING / STATUS / CFG / LOG / LIST / SETLIST / SETKV / RELOAD / KILLPKG / PROCS / KILLPID / GETPERAPP / SETPERAPP / GETPERPROC / SETPERPROC`
- **root 通道（兜底）**：一次 `su` 批量读 `run/state.json` + `config.conf` + 四份名单；写文件用 `base64 -d` 绕开 shell 转义

控制端口优先的原因：每 2s 轮询都 fork 一次 `su` 读 5 个文件（含 330 行 `system_apps.list`）会拖卡主线程，实测是列表卡顿主因。
## 配置项

改完不用重启（下一轮热重载），也可 `sh /data/adb/modules/appkiller/action.sh reload`。

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `ENABLE` | 1 | 总开关 |
| `TIMEOUT_MIN` | 30 | 冻结多少分钟后清理，可写小数（0.5 = 30 秒） |
| `INTERVAL_MS` | 2000 | 扫描间隔（最小 200） |
| `KILL_MODE` | sigkill | 全局默认清理方式：`sigkill` / `am_kill` / `force_stop`，可被 per-app / per-proc 覆盖 |
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

per-app 覆盖写在 `perapp.conf`（`<包名> <mode> <scope>`），per-proc 覆盖写在 `perproc.conf`（`<进程名> <mode>`，进程名即 `cmdline`）。
两处 mode 都取 `sigkill` / `am_kill` / `force_stop`，per-proc 另多一个 `skip`（跳过不清理）；空或 `follow` 表示跟随上一级。

## 安装

1. KernelSU / Magisk 管理器刷入 `dist/appkiller-v1.3.zip`（管理器 APK 在刷入阶段就装好，不必等开机）
2. 重启（或 `sh /data/adb/modules/appkiller/action.sh start`）
3. 若刷入时 `pm` 不可用（recovery 环境），标记会在下次开机由 `service.sh` 补装一次；也可从 KernelSU 打开 WebUI
4. 首次进 App 会弹一次 KernelSU 授权，允许后名单与状态才能直读；不授权时走本地控制端口，功能仍在

上手建议先 `DRY_RUN=1` 跑一天，确认判定符合预期再关掉。

## 构建

```sh
# 守护进程
CC=/path/to/ndk/aarch64-linux-android26-clang \
  "$CC" -O2 -Wall -Wextra -Wno-unused-parameter -o out/appkillerd src/appkillerd.c

# 管理器 APK（release 开 R8，首次约 14 分钟）
cd app
JAVA_HOME=/path/to/jdk21 ANDROID_HOME=/path/to/android-sdk \
  gradle :app:assembleRelease
```

release 开了 `isMinifyEnabled` + `isShrinkResources` + `abiFilters arm64-v8a`，APK 从 81.5 MB 压到 **6.6 MB**。
`proguard-rules.pro` 必须保留 `@Composable` 方法、`androidx.compose.runtime.**`、`top.yukonga.miuix.kmp.icon.**`
与 blur/shader 的 `<fields>`（AGSL uniform 按字符串索引），否则界面会空白或模糊失效。

主要依赖：`compose-bom 2026.08.00`、`activity-compose 1.13.0`、`top.yukonga.miuix.kmp:{miuix-ui, miuix-icons, miuix-preference, miuix-blur, miuix-shader}:0.9.4`、
`io.github.kyant0:backdrop:1.0.6`、`androidx.compose.material3:{material3, material3-window-size-class}`。
**minSdk 33**（`miuix-blur-android:0.9.4` 强制），compileSdk/targetSdk 37，AGP 9.4.0，Kotlin 2.4.10。

依赖来源：androidx 走阿里云镜像 / Maven Central；miuix 默认从 Maven Central 解析（`top.yukonga.miuix.kmp:*`）。
注意 `miuix-navigation3-ui` **最高只有 0.9.3**，本工程不引用它（导航壳自写）；`isRenderEffectSupported` 在 0.9.4 位于 `miuix-shader`。

如果本机有 miuix 的离线仓库，可用环境变量注入，构建脚本会在仓库列表里追加它：

```sh
export MIUIX_REPO=/path/to/android-compose-libs
```

不设置时完全走远程仓库，因此**仓库里不含任何机器专属路径**。

## 已知限制

- 只处理「已冻结」的应用；系统压根不冻的常驻进程不在范围内（那属于保活治理）
- Cirno 的运行时豁免（播放音频、定位、录音、相机、悬浮窗、VPN、网速、无障碍、自动填充、通行密钥、等待推送响应）依赖 hook，本模块只能覆盖「前台可见 + 输入法 + persistent + 系统应用」这几类
- `force_stop` 模式下被强停的应用无法自拉起，依赖推送的应用可能收不到消息
- 管理器 App 的 minSdk 为 33（`miuix-blur-android:0.9.4` 的 manifest 要求），Android 12 及以下装不上；守护进程本身不受影响，低版本系统仍可用模块脚本 + WebUI
- 界面模糊依赖 AGSL `RuntimeShader`，设备不支持时会自动降级为纯色卡片（功能不变）
- 硬保护名单内的包即使用户拉黑也不会被处理

## 许可

GPL-3.0。名单策略与冻结判定语义参考 [Cirno](https://github.com/Adkimsm/Cirno)（GPL-3.0），故本项目同样以 GPL-3.0 发布。

## 参考项目

- [Cirno](https://github.com/Adkimsm/Cirno) — 名单语义、豁免判定顺序、界面结构、cgroup 冻结后端
- [NoActive](https://github.com/cn.myflv.noactive) — 冻结/解冻触发时机
- [Thanox](https://github.com/Tornaco/Thanox) — 场景与后台治理思路
- [Miuix](https://github.com/compose-miuix-ui/miuix) — Compose 风格的 HyperOS 组件库
