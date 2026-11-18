# AppKiller — HyperOS 后台冻结超时清理

被系统冻结之后还继续占着 uid、进程槽和内存的后台应用，冻结满阈值就清掉；中途只要被用过就重新计时。
判定直接读 cgroup，不 hook、不注入、不依赖 LSPosed。

## 功能

- **冻结超时清理**：读 `/proc/<pid>/cgroup` 判断冻结（HyperOS 的 greeze 与 AOSP freezer 不是一回事，`State:T` 在 HyperOS 上恒为假），连续冻结超过阈值即清理，中途被使用就重新计时。
- **清理时间三级优先**：应用二级页单独设置 > 黑名单默认 5 分钟 > 全局 30 分钟。
- **三种清理方式**：`sigkill`（直接断进程）、`am kill`（先让应用自己退出再补刀）、`force stop`（杀完还阻止系统重新拉起）。可按应用、按进程单独覆盖。
- **force stop 压制窗口**：`am force-stop` 置的 `FLAG_STOPPED` 挡不住厂商层（joyose、powerkeeper、云控不走 AMS），所以窗口内额外压到最严待机桶 + 禁后台启动权限 + 冒头就再杀，到期自动恢复。
- **先整体暂停再统一杀**：避免先杀主进程导致 `:push` / `:remote` 被立刻拉起，杀完再扫三轮兜底。
- **名单体系**：硬保护 / 黑名单（强制清理）/ 白名单（豁免）/ 输入法 / persistent / 系统应用六层判定，与 Cirno 语义对齐；系统应用判定取 `uid < 10000`、App 生成清单、`pm list packages -s` 三路并集。
- **运行列表**：全部后台应用分两段，上段「即将去死」= 已冻结且正在计时，下段「未冻结」= 系统尚未回收、不参与清理。
- **二级页**：读 APK 二进制清单列出全部声明进程，叠加实时运行状态。
- **名单导入**：Cirno 读 `/data/system/Cirno/ApplicationSettings.json`，A0 读 `/sdcard/Android/A0_Memory/config.conf`。配置存在就直接解析并弹候选框勾选；候选框右上角的文件夹按钮可以换一份配置，配置不存在时也走系统文件选择器。
- **热刷入模块**：设置页选一个模块 zip，应用自己解压覆盖并重启守护进程，不用重启手机，名单与配置保留。

## A0 名单映射

| A0 格式 | 存成什么 |
| --- | --- |
| `KILL <包名>` | `perapp.conf` 里 `mode=force_stop scope=pkg` |
| `KILL <包名>:<子进程>` | `perproc.conf` 里 `mode=force_stop` |
| `WHITE <包名>` | `protect.list`；勾「存为跳过不清理」则额外写 `perproc.conf mode=skip` |

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

1. KernelSU / Magisk 刷入 `dist/appkiller-v1.6.zip`（管理器 APK 在刷入阶段装好，不必等开机）
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
