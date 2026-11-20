# AppKiller — HyperOS 后台冻结超时清理

被系统冻结之后还继续占着 uid、进程槽和内存的后台应用，冻结满阈值就清掉；中途只要被用过就重新计时。
判定直接读 cgroup，不 hook、不注入、不依赖 LSPosed。

> **兼容性：只面向 HyperOS / MIUI。** 冻结判定读的是 `/proc/<pid>/cgroup` 里的 `frozen` 路径，
> 这是小米 greeze 的落点；AOSP 原生 freezer 走 cgroup v2 的 `cgroup.freeze`，路径不同，
> 在原生 Android、LineageOS、Pixel 等系统上本模块判定不到冻结状态，等于不工作。
> 三星 / OPPO / vivo 等厂商的冻结实现也各不相同，未适配。管理器 App 与名单体系本身与系统无关，
> 但脱离 HyperOS 就没有可清理的对象。

## 功能

- **冻结超时清理**：读 `/proc/<pid>/cgroup` 判断冻结（HyperOS 的 greeze 与 AOSP freezer 不是一回事，`State:T` 在 HyperOS 上恒为假），连续冻结超过阈值即清理，中途被使用就重新计时。
- **清理时间三级优先**：应用二级页单独设置 > 黑名单默认 5 分钟 > 全局 30 分钟。
- **自定义清理时间**：全局与二级页的清理时间下拉末尾都有「自定义…」，点开输入分钟数即可，单位统一按分钟显示。
- **控制项补全**：清理时间、清理方式、单次清理上限、清理冷却、演练模式都能在「设置 → 清理规则」直接改，改完热重载生效。
- **三种清理方式**：`sigkill`（直接断进程）、`am kill`（先让应用自己退出再补刀）、`force stop`（杀完还阻止系统重新拉起）。可按应用、按进程单独覆盖。
- **force stop 压制窗口**：`am force-stop` 置的 `FLAG_STOPPED` 挡不住厂商层（joyose、powerkeeper、云控不走 AMS），所以窗口内额外压到最严待机桶 + 禁后台启动权限 + 冒头就再杀，到期自动恢复。
- **先整体暂停再统一杀**：避免先杀主进程导致 `:push` / `:remote` 被立刻拉起，杀完再扫三轮兜底。
- **名单体系**：硬保护 / 黑名单（强制清理）/ 白名单（豁免）/ 输入法 / persistent / 系统应用六层判定，与 Cirno 语义对齐；系统应用判定取 `uid < 10000`、App 生成清单、`pm list packages -s` 三路并集。
- **运行列表**：可见性与 Cirno 对齐 —— 只要该应用有进程在跑就列出来，前台应用也在，不再因为「正在被使用」就整条隐藏（那种行标「前台」，说明它此刻不会被清）。
  分两段：上段「即将去死」= 会被清理的（已冻结、正在计时，到点就清）；下段「不处理」= 其余全部。
  不被清理的行内直接写原因，从「最不可改变」排到「最可改变」：系统核心 > 前台 > 系统应用 > 白名单 > 常驻 > 不处理，
  例如「已冻结 2 分钟 · 前台」「未冻结 · 系统应用」「未冻结 · 常驻」。
  硬保护包不进列表：桌面、系统 UI、电话、蓝牙、管理器自身，以及一批常驻系统守护（`com.miui.powerinsight`、`com.xiaomi.xmsfkeeper`、`com.android.se`、`android.ext.services` 等）。
  另外凡是**整包 adj < 0** 的应用都自动等同硬保护 —— 系统既不会冻结也不会回收它们，清理没有意义，列出来只是噪音。
  只看整包：包内只要还有一个 adj ≥ 0 的进程就照常列出，那类包会被系统冻结、也会被我们清，藏起来反而看不到它在占内存。
- **二级页**：读 APK 二进制清单列出全部声明进程，叠加实时运行状态。
- **名单导入**：Cirno 读 `/data/system/Cirno/ApplicationSettings.json`，A0 读 `/sdcard/Android/A0_Memory/config.conf`。配置存在就直接解析并弹候选框勾选；候选框右上角的文件夹按钮可以换一份配置，配置不存在时也走系统文件选择器。
- **候选框**：按包名分组，一个应用一行（应用名 + 包名 + 条目摘要），整行可勾选整组、展开箭头逐条细选；默认合并到现有名单，底部「覆盖现有名单」开关打开才清空重写。
- **WHITE 语义**：`WHITE 包名` 进白名单整包豁免；`WHITE 包名:进程` 只把该进程设为跳过不清理，不豁免整个包。
- **热刷入模块**：设置页选一个模块 zip，应用自己解压覆盖并重启守护进程，不用重启手机，名单与配置保留。临时解压目录放在 App 自己的缓存里（不能放 `/data/adb/modules` 下，否则会被当成第二个模块）；`pm install` 会结束 App 进程，装完重新打开 App、打开「启用守护」即可，那一步会顺手清掉热刷残留。

## A0 名单映射

| A0 格式 | 存成什么 |
| --- | --- |
| `KILL <包名>` | `perapp.conf` 里 `mode=force_stop scope=pkg` |
| `KILL <包名>:<子进程>` | `perproc.conf` 里 `mode=force_stop` |
| `WHITE <包名>` | `protect.list`；勾「存为跳过不清理」则额外写 `perproc.conf mode=skip` |

候选框按包名分组：一行一个应用（显示应用名 + 包名 + 该包名下的条目摘要），同一个包的整包 KILL、子进程 KILL、白名单都收在同一行里，不再重复出现。左侧勾选框是三态 —— 点它整组全选/全不选，右侧箭头展开后才看得到逐条明细、可单独勾选。

## 配置项

`module/config.conf`，改完热重载生效，也可在管理器「设置 → 重启守护」立刻生效。

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `ENABLE` | 1 | 总开关 |
| `TIMEOUT_MIN` | 30 | 冻结多少分钟后清理，可写小数 |
| `BLACKLIST_TIMEOUT_MIN` | 5 | 黑名单应用的清理时间 |
| `INTERVAL_MS` | 2000 | 扫描间隔（最小 200） |
| `KILL_MODE` | sigkill | 全局默认清理方式，可被 per-app / per-proc 覆盖 |
| `KILL_SCOPE` | pkg | `pkg` 整个应用 / `frozen` 只清被冻结的进程 |
| `FORCE_STOP_HOLD_MIN` | 5 | force stop 压制窗口，0 = 关闭 |
| `DRY_RUN` | 0 | 1 = 只记日志不杀 |
| `ACTIVE_ADJ` | 200 | 低于此 adj 视为可见，立刻重置计时 |
| `MAX_KILL_PER_ROUND` | 3 | 每轮最多清理几个 |
| `KILL_COOLDOWN_SEC` | 180 | 同一应用两次清理最短间隔 |
| `SYSTEM_DEFAULT` | protect | 系统应用默认策略 |
| `USER_DEFAULT` | manage | 普通应用默认策略 |
| `CTL_PORT` | 27183 | 本地控制端口，0 = 关闭 |

per-app 覆盖写在 `perapp.conf`（`<包名> mode=<> scope=<> timeout=<秒>`），per-proc 写在 `perproc.conf`（`<进程名> mode=<>`）。
mode 取 `sigkill` / `am_kill` / `force_stop`，per-proc 另多一个 `skip`；空或 `follow` 表示跟随上一级。

## 安装

1. KernelSU / Magisk 刷入 `dist/appkiller-v1.0.zip`（管理器 APK 在刷入阶段装好，不必等开机）
2. 重启（或等 `service.sh` 拉起守护进程）
3. 首次进 App 会弹一次 KernelSU 授权；不授权时走本地控制端口，功能仍在

上手建议先 `DRY_RUN=1` 跑一天，确认判定符合预期再关掉。

## 已知限制

- 只处理「已冻结」的应用；系统压根不冻的常驻进程不在范围内（那属于保活治理）
- Cirno 的运行时豁免（播放音频、定位、录音、相机、悬浮窗、VPN、无障碍、自动填充、等待推送响应）依赖 hook，本模块只能覆盖「前台可见 + 输入法 + persistent + 系统应用」
- `force stop` 模式下被强停的应用在压制窗口内无法自拉起，依赖推送的应用可能收不到消息（`FORCE_STOP_HOLD_MIN=0` 可关掉压制只做一次性强停）
- 管理器 App 的 minSdk 为 33，Android 12 及以下装不上；守护进程本身不受影响
- 界面模糊依赖 AGSL `RuntimeShader`，不支持时自动降级为纯色卡片

## 许可

GPL-3.0。名单策略与冻结判定语义参考 [Cirno](https://github.com/Adkimsm/Cirno)（GPL-3.0），故本项目同样以 GPL-3.0 发布。

## 参考来源

- [Cirno](https://github.com/Adkimsm/Cirno) — 名单语义、豁免判定顺序、界面结构、cgroup 冻结后端
- [A0 后台优化](https://www.coolapk.com/) — A0 名单格式与「乖巧模式」实现（CoolApk @EasonT）
- [NoActive](https://github.com/cn.myflv.noactive) — 冻结/解冻触发时机
- [Thanox](https://github.com/Tornaco/Thanox) — 场景与后台治理思路
- [Miuix](https://github.com/compose-miuix-ui/miuix) — Compose 风格的 HyperOS 组件库
