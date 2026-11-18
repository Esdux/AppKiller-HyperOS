package com.yg.appkiller.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.yg.appkiller.Ctrl
import com.yg.appkiller.ModuleRepo
import com.yg.appkiller.ui.AppSettings
import com.yg.appkiller.ui.theme.MiuixAppTheme
import com.yg.appkiller.ui.util.DshIconSize
import com.yg.appkiller.ui.util.DshSpacing
import com.yg.appkiller.ui.util.PageScaffold
import com.yg.appkiller.ui.util.TimeoutInputDialog
import com.yg.appkiller.ui.util.timeoutText
import com.yg.appkiller.ui.util.firstCard
import com.yg.appkiller.ui.util.nextCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * 应用二级页 —— 点列表里某个应用后进来。
 *
 * 排班顺序照 Cirno 的 AppPage：
 * 1. 身份（图标 + 名称 + 包名），不可操作，只回答「我在配哪个应用」；
 * 2. 名单归属（白/黑名单，直接落盘）；
 * 3. 本应用的清理方式（每应用覆盖全局）；
 * 4. 当前进程（可逐个杀，这是「点了真的能杀掉」的落点）；
 * 5. 系统出口（应用信息）；
 * 6. 危险操作单独置底。
 *
 * 身份区不是可点 Card：它点了没有去处。可点的东西必须有去处，
 * 否则用户会反复点它、怀疑卡住了。
 */
class AppDetailActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_PKG = "pkg"
        private const val EXTRA_NAME = "name"

        fun intent(context: Context, app: AppEntry): Intent =
            intent(context, app.packageName, app.appName)

        /**
         * 只知道包名的入口。
         *
         * 运行列表手里只有 FrozenApp（包名 + 计数），没有 AppEntry；而两个入口必须进同一个页面 ——
         * 用户从哪条路进来，看到的清理方式与进程明细应该完全一样。
         */
        fun intent(context: Context, packageName: String, appName: String): Intent =
            Intent(context, AppDetailActivity::class.java)
                .putExtra(EXTRA_PKG, packageName)
                .putExtra(EXTRA_NAME, appName)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val pkg = intent.getStringExtra(EXTRA_PKG) ?: return finish()
        // 名称为空或与包名相同，都说明调用方没解析出应用名（运行列表、通知栏跳转等）。
        // 这里自己查一次兜底：二级页标题显示包名是最容易被投诉的一类「显示错误」。
        val rawName = intent.getStringExtra(EXTRA_NAME)
        val name = if (rawName.isNullOrBlank() || rawName == pkg) {
            runCatching {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
        } else rawName

        setContent {
            val icon: ImageBitmap? = remember(pkg) {
                runCatching {
                    packageManager.getApplicationIcon(pkg).toBitmap(144, 144).asImageBitmap()
                }.getOrNull()
            }
            val settings = remember { AppSettings.load(this) }
            val themeMode = remember(settings) {
                runCatching { ColorSchemeMode.valueOf(settings.themeMode) }
                    .getOrDefault(ColorSchemeMode.System)
            }
            MiuixAppTheme(themeMode = themeMode) {
                AppDetailContent(
                    pkg = pkg,
                    name = name,
                    icon = icon,
                    isBlurEnabled = settings.isBlurEnabled,
                    onBack = { finish() },
                    onOpenAppInfo = { openAppInfo(pkg) },
                    toast = { msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() },
                )
            }
        }
    }

    private fun openAppInfo(pkg: String) {
        val i = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", pkg, null),
        )
        runCatching { startActivity(i) }
            .onFailure { Toast.makeText(this, "无法打开", Toast.LENGTH_SHORT).show() }
    }
}

/** 每应用清理方式的可选项。索引 0 = 跟随全局，与 daemon 的 perapp.conf 语义一致。 */
private val MODE_LABELS = listOf("跟随全局", "sigkill", "am kill", "force stop")

/**
 * 单进程的可选项，四档，与 perapp 的三档 + skip 对齐。
 *
 * 「跟随应用」是默认：这个进程不做任何覆盖，按它所属应用（或全局）的方式清。
 * 三档清理方式在进程粒度上落在「多强硬」：am kill 先 SIGTERM 留 200ms 收尾，
 * sigkill 直接断，force stop 杀完再对整包跑一次 am force-stop 防系统拉起。
 * 「跳过不清理」给推送/同步这类进程用 —— 杀掉它们应用会立刻重连，白多一轮唤醒。
 */
private val PROC_MODE_LABELS = listOf("跟随应用", "sigkill", "am kill", "force stop", "跳过不清理")

/**
 * 该应用自己的清理时间档位（分钟）。索引 0 = 跟随上级。
 *
 * 与设置页同一套刻度、同一单位（分钟）。内部落盘仍是秒（daemon 的 perapp.conf
 * 写 timeout=<秒>），只在界面上换算一次 —— 单位混用是这类设置最容易输错零的地方。
 * 「跟随」排第一是因为它是默认值：绝大多数应用不该单独设时间，
 * 只有「必须早点杀」和「必须留久点」两类才需要动它。
 */
// 与设置页同一套档位（1 / 5 / 30 分钟、1 / 2 小时），另加「跟随上级」；
// 档位外的时间走末尾的「自定义」。
private val TIMEOUT_STEPS_MIN = listOf(0, 1, 5, 30, 60, 120)
// 显示与全局设置页同一套换算（60 → 1 小时、1440 → 1 天）。
private fun timeoutLabelOf(min: Int): String = timeoutText(min)
/** 秒 → 分钟，仅用于显示与档位匹配；非整分钟的值向上取整，避免显示成 0。 */
private fun secToMin(sec: Int): Int = if (sec <= 0) 0 else (sec + 59) / 60
private fun timeoutToIndex(sec: Int): Int {
    val i = TIMEOUT_STEPS_MIN.indexOf(secToMin(sec))
    // 不在档位里就落到末尾的自定义项，让它把实际值显示出来
    return if (i >= 0) i else TIMEOUT_STEPS_MIN.size
}

/** PROC_MODE_LABELS 下标 → perproc.conf 的 mode 值（"" = 不写配置，跟随应用）。 */
private fun indexToProcMode(index: Int): String = when (index) {
    1 -> "sigkill"
    2 -> "am_kill"
    3 -> "force_stop"
    4 -> "skip"
    else -> ""
}

private fun procModeToIndex(mode: String): Int = when (mode) {
    "sigkill" -> 1
    "am_kill" -> 2
    "force_stop" -> 3
    "skip" -> 4
    else -> 0
}

private fun modeToIndex(mode: String): Int = when (mode) {
    "sigkill" -> 1
    "am_kill" -> 2
    "force_stop" -> 3
    else -> 0
}

// 返回 "-" 而不是空串：控制端口按空格切参数，空串会变成两个连续空格，
// daemon 解析时后续的 scope / timeout 全部错位（表现为选了时间却不落盘）。
private fun indexToMode(index: Int): String = when (index) {
    1 -> "sigkill"
    2 -> "am_kill"
    3 -> "force_stop"
    else -> "-"
}

@Composable
private fun AppDetailContent(
    pkg: String,
    name: String,
    icon: ImageBitmap?,
    isBlurEnabled: Boolean,
    onBack: () -> Unit,
    onOpenAppInfo: () -> Unit,
    toast: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()

    var inWhite by remember { mutableStateOf(false) }
    var inBlack by remember { mutableStateOf(false) }
    var modeIndex by remember { mutableIntStateOf(0) }
    // 该应用自己的清理时间，索引对应 TIMEOUT_STEPS_MIN（分钟），0 = 跟随上级。
    var timeoutIndex by remember { mutableIntStateOf(0) }
    var showTimeout by remember { mutableStateOf(false) }
    // 自定义分钟数。只在选中「自定义」项时才是当前有效值，其余时候仅供显示。
    var customMin by remember { mutableIntStateOf(0) }
    var procs by remember { mutableStateOf<List<Ctrl.ProcEntry>>(emptyList()) }
    // 清单声明的进程名（DECLPROCS）。与 procs 互补：这份是「一共会有哪些进程」，
    // 应用没启动也有；procs 是「此刻活着的」，带 pid/adj/冻结状态。
    var declared by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    // 首轮读名单归属与每应用模式；之后每 2 秒刷一次进程列表。
    // 进程列表是本页唯一会自己变的东西，其余都是用户点出来的。
    LaunchedEffect(pkg) {
        withContext(Dispatchers.IO) {
            val white = ModuleRepo.list("protect.list").toHashSet()
            val black = ModuleRepo.list("blacklist.list").toHashSet()
            val per = Ctrl.getPerApp(pkg)
            withContext(Dispatchers.Main) {
                inWhite = white.contains(pkg)
                inBlack = black.contains(pkg)
                modeIndex = modeToIndex(per.mode)
                timeoutIndex = timeoutToIndex(per.timeoutSec)
                // 自定义值要原样记住：secToMin 向上取整只用于显示与匹配档位，
                // 落盘时用这里存的分钟数，避免 90 秒被写成 120 秒。
                customMin = secToMin(per.timeoutSec)
            }
        }
        // 清单只读一次：它是静态的，应用更新前不会变。解 APK 要读几 MB，不适合放进 2 秒轮询。
        withContext(Dispatchers.IO) { Ctrl.declProcs(pkg) }.let { declared = it }

        while (true) {
            val list = withContext(Dispatchers.IO) { Ctrl.procs(pkg) }
            procs = list
            delay(2_000L)
        }
    }

    // 当前清理时间的秒数：不在档位里（自定义值）时按分钟数换算，不丢精度。
    val currentTimeoutSec = if (timeoutIndex < TIMEOUT_STEPS_MIN.size) {
        TIMEOUT_STEPS_MIN[timeoutIndex] * 60
    } else {
        customMin * 60
    }
    val timeoutItems = TIMEOUT_STEPS_MIN.map { timeoutLabelOf(it) } +
        if (timeoutIndex < TIMEOUT_STEPS_MIN.size) "自定义…"
        else timeoutLabelOf(customMin) + "（自定义）"

    fun setMembership(white: Boolean, black: Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val w = ModuleRepo.list("protect.list").toMutableSet()
                    val b = ModuleRepo.list("blacklist.list").toMutableSet()
                    // 白名单与黑名单互斥：一个包同时在两边会让 daemon 的判定取决于读顺序，
                    // 这是最难查的一类「有时候生效有时候不生效」。
                    if (white) {
                        w.add(pkg)
                        b.remove(pkg)
                    } else {
                        w.remove(pkg)
                    }
                    if (black) {
                        b.add(pkg)
                        w.remove(pkg)
                    } else {
                        b.remove(pkg)
                    }
                    ModuleRepo.writeList("protect.list", "# 白名单", w)
                    ModuleRepo.writeList("blacklist.list", "# 黑名单", b)
                    true
                }.getOrDefault(false)
            }
            inWhite = white
            inBlack = black
            busy = false
            toast(if (ok) "已保存" else "保存失败：没有 root 权限")
        }
    }

    PageScaffold(
        title = name,
        isBlurEnabled = isBlurEnabled,
        onBack = onBack,
    ) {
        item(key = "identity") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // 水平 28dp 对齐 SmallTitle 的文字缩进，垂直 12dp 是块间距
                    .padding(horizontal = 28.dp, vertical = DshSpacing.Block),
                // 图标中心 = 「名称 + 包名」文字块中心。
                //
                // 用 Row 的 CenterVertically 一次达成，不要再给文字列限高：
                // 限高（比如固定 72dp）在两行包名时会把文字挤出框外，视觉上反而更歪。
                verticalAlignment = Alignment.CenterVertically,
            ) {
                icon?.let {
                    Image(
                        bitmap = it,
                        contentDescription = null,
                        modifier = Modifier.size(DshIconSize.DetailIcon),
                    )
                    Spacer(Modifier.width(DshSpacing.CardInside))
                }
                Column {
                    MiuixText(text = name, style = MiuixTheme.textStyles.title3)
                    // 包名一定会很长（com.mfcloudcalculate.networkdisk 这类），
                    // 不截断、允许换行：截断后用户认不出是哪个应用，
                    // 而这一行存在的唯一意义就是让人确认「就是这个包」。
                    MiuixText(
                        text = pkg,
                        style = MiuixTheme.textStyles.footnote1,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }

        item(key = "lists") {
            Column {
                SmallTitle(text = "名单")
                Card(modifier = Modifier.firstCard()) {
                    SwitchPreference(
                        title = "白名单",
                        summary = "白名单内的应用不做任何杀死与清理",
                        checked = inWhite,
                        onCheckedChange = { on ->
                            if (on) setMembership(true, false) else setMembership(false, false)
                        },
                    )
                    SwitchPreference(
                        title = "黑名单",
                        summary = "黑名单内的应用一旦退到后台就立刻清理",
                        checked = inBlack,
                        onCheckedChange = { on ->
                            if (on) setMembership(false, true) else setMembership(false, false)
                        },
                    )
                }
            }
        }

        item(key = "mode") {
            Column {
                SmallTitle(text = "清理方式")
                Card(modifier = Modifier.nextCard()) {
                    // 四个互斥选项用下拉弹层，不用「点一次轮换」：
                    // 轮换看不出有哪几个选项，也回不去上一步，用户会反复点着试。
                    WindowDropdownPreference(
                        items = MODE_LABELS,
                        selectedIndex = modeIndex,
                        title = "此应用的清理方式",
                        summary = "默认跟随全局，可在这里单独覆盖",
                        onSelectedIndexChange = { next ->
                            modeIndex = next
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    Ctrl.setPerApp(pkg, indexToMode(next), "pkg", currentTimeoutSec)
                                }
                                toast(if (ok) "已设为 " + MODE_LABELS[next] else "设置失败：守护进程未运行")
                            }
                        },
                    )
                    // 清理时间与清理方式同一张卡：两者一起决定「什么时候、怎么杀」，
                    // 拆成两组会让用户在两个地方来回确认。它优先级高于全局与黑名单默认值。
                    WindowDropdownPreference(
                        items = timeoutItems,
                        selectedIndex = timeoutIndex,
                        title = "此应用的清理时间",
                        summary = "冻结超过这个时间才清理，优先于全局与黑名单默认值",
                        onSelectedIndexChange = { next ->
                            // 「自定义」不直接落盘，先开输入框；取消则保持原值。
                            if (next >= TIMEOUT_STEPS_MIN.size) {
                                showTimeout = true
                            } else {
                                timeoutIndex = next
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) {
                                        Ctrl.setPerApp(pkg, indexToMode(modeIndex), "pkg", TIMEOUT_STEPS_MIN[next] * 60)
                                    }
                                    toast(if (ok) "清理时间设为 " + timeoutLabelOf(TIMEOUT_STEPS_MIN[next])
                                          else "设置失败：守护进程未运行")
                                }
                            }
                        },
                    )
                }
            }
        }


        item(key = "procs") {
            Column {
                // 合并两路数据：declared 是清单里声明的全部进程名（骨架），
                // procs 是此刻活着的（带 pid/adj/冻结）。骨架保证应用没启动时也列得全，
                // 实时态保证活着的进程能看到具体状态。
                //
                // 顺序：先按清单顺序（主进程在最前），再把清单里没有但正在跑的追加到末尾 ——
                // 后者只会出现在清单没声明全的极端情况，但不该因此被藏起来。
                val runningByCmd = procs.associateBy { it.cmdline }
                val rows = ArrayList<Pair<String, Ctrl.ProcEntry?>>(declared.size + procs.size)
                for (d in declared) rows.add(d to runningByCmd[d])
                for (p in procs) if (!declared.contains(p.cmdline)) rows.add(p.cmdline to p)

                val runningCount = procs.size
                SmallTitle(
                    text = "进程 · " + rows.size +
                        if (runningCount > 0) "（运行中 " + runningCount + "）" else "",
                )
                Card(modifier = Modifier.nextCard()) {
                    if (rows.isEmpty()) {
                        ArrowPreference(
                            title = "没有读到进程信息",
                            summary = "清单解析失败，或守护进程未运行",
                            onClick = {},
                        )
                    } else {
                        rows.forEach { (cmdline, live) ->
                            // 每进程一个下拉：清不清理，细到子进程。
                            //
                            // 标题只写进程名，不写包名：一个应用的所有进程包名部分完全相同，
                            // 每行重复一遍那串长包名，既挤掉真正有区别的信息（push / remote / worker），
                            // 又会让行高被撑成两行。主进程没有冒号，直接标「主进程」。
                            val procName = cmdline.substringAfterLast(':')
                                .takeIf { it.isNotEmpty() && it != cmdline }
                                ?: "主进程"
                            // summary 是状态短语：活着给 pid/adj/冻结，没活着就说没在跑。
                            // 空着不写会让人以为「这行坏了」。
                            val state = if (live != null)
                                "pid " + live.pid + " · adj " + live.adj +
                                    (if (live.frozen) " · 已冻结" else "")
                            else "未运行"
                            WindowDropdownPreference(
                                items = PROC_MODE_LABELS,
                                selectedIndex = procModeToIndex(live?.mode ?: "-"),
                                title = procName,
                                summary = state,
                                onSelectedIndexChange = { idx ->
                                    val mode = indexToProcMode(idx)
                                    scope.launch {
                                        val ok = withContext(Dispatchers.IO) {
                                            Ctrl.setPerProc(cmdline, mode)
                                        }
                                        toast(if (ok) "已保存" else "设置失败：守护进程未运行")
                                        procs = withContext(Dispatchers.IO) { Ctrl.procs(pkg) }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        item(key = "system") {
            Column {
                SmallTitle(text = "系统")
                Card(modifier = Modifier.nextCard()) {
                    ArrowPreference(
                        title = "应用信息",
                        summary = "打开系统的权限、存储、通知页面",
                        onClick = onOpenAppInfo,
                    )
                }
            }
        }

        item(key = "danger") {
            Column {
                SmallTitle(text = "危险操作")
                Card(modifier = Modifier.nextCard()) {
                    ArrowPreference(
                        title = "立即清理此应用",
                        summary = "按当前清理方式执行一次，不等超时",
                        onClick = {
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { Ctrl.killPkg(pkg) }
                                toast(if (r.startsWith("OK")) "已执行" else "执行失败：" + r.take(40))
                            }
                        },
                    )
                }
                // 页尾不加额外间距：最后一块 Card 自己的 nextCard 12dp 就是收尾。
            }
        }
    }

    // 对话框放在 LazyColumn 外面：它是 Window 级弹层，不属于列表项，
    // 放进 item 里会在滚动时被复用/重建，输入到一半就丢焦点。
    TimeoutInputDialog(
        show = showTimeout,
        title = "自定义清理时间",
        initialMinutes = if (timeoutIndex < TIMEOUT_STEPS_MIN.size) TIMEOUT_STEPS_MIN[timeoutIndex] else customMin,
        allowZero = false,
        onDismiss = { showTimeout = false },
        onConfirm = { minutes ->
            showTimeout = false
            customMin = minutes
            timeoutIndex = TIMEOUT_STEPS_MIN.size
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    Ctrl.setPerApp(pkg, indexToMode(modeIndex), "pkg", minutes * 60)
                }
                toast(if (ok) "清理时间设为 " + timeoutLabelOf(minutes)
                      else "设置失败：守护进程未运行")
            }
        },
    )
}
