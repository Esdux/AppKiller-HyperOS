package com.yg.appkiller.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yg.appkiller.Ctrl
import com.yg.appkiller.ModuleRepo
import com.yg.appkiller.ui.data.DaemonState
import com.yg.appkiller.ui.util.DshSpacing
import com.yg.appkiller.ui.util.PageScaffold
import com.yg.appkiller.ui.util.firstCard
import com.yg.appkiller.ui.util.nextCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.window.WindowDialog

/** 全局清理方式的选项顺序，与 config.conf 的 KILL_MODE 取值一一对应。 */
private val KILL_MODES = listOf("sigkill", "am_kill", "force_stop")
// 下拉项只放名字本身。括号里的「强杀，最彻底」这类后缀在窄屏上会被截断，
// 而且它解释的是效果不是名字 —— 那类说明放在控件下方的 FootNote 里统一讲。
private val KILL_MODE_LABELS = listOf("sigkill", "am kill", "force stop")

// 三档的实际差别（写在控件下方的小字）：
//   先说「能不能挡住系统重启」，再说「应用有没有机会收尾」，最后才是副作用。
//   用户选这一项时真正关心的是前两句。
private val KILL_MODE_HINT = "sigkill 直接断进程，最快；am kill 先让应用自己退出再补刀；" +
    "force stop 杀完还会阻止系统重新拉起它，推送类应用可能收不到消息。"

/** 超时档位：分钟。给固定档位而不是滑杆，是因为这个值只有「几分钟」和「半小时以上」两档体感。 */
private val TIMEOUT_STEPS = listOf(1, 2, 5, 10, 15, 30, 60, 120)
private fun timeoutLabel(min: Int): String = if (min >= 60) (min / 60).toString() + " 小时" else min.toString() + " 分钟"

/**
 * 设置页的排班规则（三条，都是踩过坑的）：
 *
 * 1. **开关在上、跳转在中、危险操作置底且单独分组。** 用户扫设置页时先找「能开的开关」，
 *    再找「能进去的页面」，最后才是「会毁东西的按钮」。把卸载放在第一组，是设计事故。
 *
 * 2. **会重绘整个 App 的设置单独开一个 Activity。** 主题这种设置一改，页面当场重排，
 *    用户的手指还停在开关上，下面几行会从指头底下消失。放到二级页，回来时用
 *    onResume 让 settingsEpoch 自增重读（见 AppShell.kt）。
 *
 * 3. **每组不超过 6 行。** 超过就说明这一组其实混了两类事，拆开。
 *
 * 本页与模板的差异：模板里「导入/导出设置」的 onClick 是空的（模板不引 SAF 是为了让
 * 空工程零权限能跑）。这里全部接上真实实现 —— 一个点了没反应的菜单项比没有这一项更糟。
 */
@Composable
fun SettingsPage(
    isBlurEnabled: Boolean,
    extraBottomPadding: Dp = 0.dp,
    state: DaemonState,
    onRefresh: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var confirmStop by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var tip by remember { mutableStateOf("") }
    var showLog by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf("") }

    // 名单数量与超时档位从 state 派生，不在本页另开轮询 —— 一份 flow 供四个页面共用。
    val timeoutIndex = remember(state.timeoutMin) {
        TIMEOUT_STEPS.indexOfFirst { it >= state.timeoutMin }.let { if (it < 0) 6 else it }
    }
    val killModeIndex = remember(state.killMode) {
        KILL_MODES.indexOf(state.killMode).let { if (it < 0) 0 else it }
    }

    fun run(action: suspend () -> String) {
        if (busy) return
        busy = true
        scope.launch {
            val msg = withContext(Dispatchers.IO) { runCatching { action() }.getOrElse { "失败：" + it.message } }
            busy = false
            tip = msg
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            onRefresh()
        }
    }

    // 导入导出走 SAF：不需要存储权限，用户自己挑文件，路径由系统给。
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        run {
            val map = ModuleRepo.exportAll()
            if (map.isEmpty()) return@run "导出失败：没有 root 权限"
            val text = ModuleRepo.toBackupText(map)
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(text.toByteArray(Charsets.UTF_8))
                } != null
            }.getOrDefault(false)
            if (ok) "已导出 " + map.size + " 个文件" else "写入失败"
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        run {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (text.isNullOrBlank()) return@run "读取失败"
            // 先认文件头再动手：选错文件时不应该把模块配置写坏
            if (!text.startsWith("# AppKiller 备份")) return@run "这不是 AppKiller 的备份文件"
            val n = ModuleRepo.importAll(text)
            if (n > 0) "已导入 " + n + " 个文件" else "导入失败：没有 root 权限"
        }
    }

    // 热刷入：用户挑一个模块 zip，应用自己解压覆盖 + 重启守护进程，不重启整机。
    // 选 zip 要拿原始文件路径（unzip 在 root shell 里跑，content:// URI 它读不了），
    // 所以先把选中的 zip 复制到应用缓存目录，再把绝对路径交给 root。
    val hotInstallLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        run {
            val zip = runCatching {
                val f = java.io.File(context.cacheDir, "hot-install.zip")
                context.contentResolver.openInputStream(uri)?.use { ins ->
                    f.outputStream().use { outs -> ins.copyTo(outs) }
                }
                f
            }.getOrNull()
            if (zip == null || !zip.exists() || zip.length() < 1024) return@run "读取 zip 失败"
            val (ok, msg) = ModuleRepo.hotInstall(zip.absolutePath, context.cacheDir.absolutePath)
            zip.delete()
            if (ok) msg else "热刷入失败：" + msg
        }
    }

    // Cirno / A0 名单导入：默认路径存在就直接读（省掉翻文件管理器这一步），
    // 不存在或用户想换一份配置时，点候选框右上角的文件夹按钮走系统选择器。
    var showCirno by remember { mutableStateOf(false) }
    var cirnoPicked by remember { mutableStateOf("") }
    val cirnoPickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (text.isNullOrBlank()) {
            tip = "读取失败：文件为空或无法读取"
        } else {
            cirnoPicked = text
            showCirno = true
        }
    }
    var showA0 by remember { mutableStateOf(false) }
    var a0Picked by remember { mutableStateOf("") }
    val a0PickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (text.isNullOrBlank()) {
            tip = "读取失败：文件为空或无法读取"
        } else {
            a0Picked = text
            showA0 = true
        }
    }


    PageScaffold(
        title = "设置",
        isBlurEnabled = isBlurEnabled,
        extraBottomPadding = extraBottomPadding,
    ) {
        item(key = "daemon") {
            Column {
                SmallTitle(text = "守护进程")
                Card(modifier = Modifier.firstCard()) {
                    // 状态行不可点：它只回答「现在怎么样」，点了没有去处
                    ArrowPreference(
                        title = "当前状态",
                        summary = when {
                            !state.loaded -> "读取中"
                            state.running -> "运行中 · pid " + state.pid + " · 已运行 " + state.uptimeText
                            else -> "未运行"
                        },
                        onClick = {},
                    )
                    SwitchPreference(
                        title = "启用守护",
                        summary = "关闭后所有应用都不再被杀死与清理",
                        checked = state.running,
                        onCheckedChange = { on ->
                            if (!on) confirmStop = true
                            else run {
                                ModuleRepo.startDaemon(context)
                                "已发送启动指令"
                            }
                        },
                    )
                    ArrowPreference(
                        title = "重启守护",
                        summary = "改了配置但没生效时用这个",
                        onClick = { run { ModuleRepo.restartDaemon(); "已重启" } },
                    )
                }
            }
        }

        item(key = "rules") {
            Column {
                SmallTitle(text = "清理规则")
                Card(modifier = Modifier.nextCard()) {
                    WindowDropdownPreference(
                        items = TIMEOUT_STEPS.map { timeoutLabel(it) },
                        selectedIndex = timeoutIndex,
                        title = "清理时间",
                        summary = "退到后台冻结超过这个时间才清理",
                        onSelectedIndexChange = { idx ->
                            run {
                                ModuleRepo.setConfigs(mapOf("TIMEOUT_MIN" to TIMEOUT_STEPS[idx].toString()))
                                "超时设为 " + timeoutLabel(TIMEOUT_STEPS[idx])
                            }
                        },
                    )
                    WindowDropdownPreference(
                        items = KILL_MODE_LABELS,
                        selectedIndex = killModeIndex,
                        title = "清理方式",
                        summary = "全局默认，可在应用二级页单独覆盖",
                        onSelectedIndexChange = { idx ->
                            run {
                                ModuleRepo.setConfigs(mapOf("KILL_MODE" to KILL_MODES[idx]))
                                "清理方式设为 " + KILL_MODES[idx]
                            }
                        },
                    )
                    // 控件下方的小字说明：下拉项里放不下，也不该塞进去。
                    // 用 Text 而不是 BasicComponent：后者是两行布局（标题+摘要），
                    // 标题留空会白占一行高度。
                    MiuixText(
                        text = KILL_MODE_HINT,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(start = DshSpacing.CardInside, end = DshSpacing.CardInside, bottom = DshSpacing.Block),
                    )
                    SwitchPreference(
                        title = "仅演练不执行",
                        summary = "打开后只写日志，不真的杀进程",
                        checked = state.dryRun,
                        onCheckedChange = { on ->
                            run {
                                ModuleRepo.setConfigs(mapOf("DRY_RUN" to if (on) "1" else "0"))
                                if (on) "已开启演练模式" else "已关闭演练模式"
                            }
                        },
                    )
                }
            }
        }

        item(key = "appearance") {
            Column {
                SmallTitle(text = "界面")
                Card(modifier = Modifier.nextCard()) {
                    ArrowPreference(
                        title = "主题与外观",
                        summary = "深浅色、动态取色、底栏形态、背景模糊",
                        onClick = { context.startActivity(Intent(context, ThemeActivity::class.java)) },
                    )
                }
            }
        }

        item(key = "data") {
            Column {
                SmallTitle(text = "配置备份")
                Card(modifier = Modifier.nextCard()) {
                    ArrowPreference(
                        title = "导出配置",
                        summary = "把配置与四份名单存成一个文本文件",
                        onClick = { exportLauncher.launch("appkiller-backup.txt") },
                    )
                    ArrowPreference(
                        title = "导入配置",
                        summary = "从备份文件恢复，会覆盖同名文件",
                        onClick = { importLauncher.launch(arrayOf("text/plain", "*/*")) },
                    )
                    ArrowPreference(
                        title = "导入 Cirno 名单",
                        summary = "存在就直接读，也可在候选框右上角换文件",
                        onClick = { cirnoPicked = ""; showCirno = true },
                    )
                    ArrowPreference(
                        title = "导入 A0 后台优化名单",
                        summary = "存在就直接读，也可在候选框右上角换文件；KILL 存为 force stop，WHITE 存为白名单或跳过不清理",
                        onClick = { a0Picked = ""; showA0 = true },
                    )
                }
                if (tip.isNotEmpty()) {
                    // 提示行放在卡片外：它属于「刚才那次操作的结果」，不属于任何一组设置
                    MiuixText(
                        text = tip,
                        style = MiuixTheme.textStyles.footnote1,
                        color = colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(start = 28.dp, top = 8.dp, end = 28.dp),
                    )
                }
            }
        }

        item(key = "log") {
            Column {
                SmallTitle(text = "日志")
                Card(modifier = Modifier.nextCard()) {
                    ArrowPreference(
                        title = "查看守护日志",
                        summary = "最近 80 行，看它到底做了什么",
                        onClick = {
                            logText = "读取中…"
                            showLog = true
                            scope.launch {
                                logText = withContext(Dispatchers.IO) {
                                    ModuleRepo.logTail(80).ifBlank { "没有日志" }
                                }
                            }
                        },
                    )
                }
            }
        }

        item(key = "module") {
            Column {
                SmallTitle(text = "模块")
                Card(modifier = Modifier.nextCard()) {
                    // 热刷入放在「模块」组而不是「配置备份」：它换的是程序本体（二进制 + APK），
                    // 不是用户配置。放错组会让人以为它只影响名单。
                    ArrowPreference(
                        title = "热刷入模块",
                        summary = "选一个模块 zip，覆盖程序并重启守护，不用重启手机；名单与配置保留",
                        onClick = { hotInstallLauncher.launch(arrayOf("*/*")) },
                    )
                }
            }
        }

        item(key = "danger") {
            Column {
                SmallTitle(text = "危险操作")
                Card(modifier = Modifier.nextCard()) {
                    ArrowPreference(
                        title = "重载全部配置",
                        summary = "让守护重新读一遍名单与配置",
                        onClick = { run { if (ModuleRepo.reload()) "已重载" else "重载失败" } },
                    )
                    ArrowPreference(
                        title = "停止守护",
                        summary = "停止后不再杀死任何应用",
                        onClick = { confirmStop = true },
                    )
                }
            }
        }
    }

    // 危险操作必须二次确认：WindowDialog 不依赖 Scaffold 的 popupHost，在任何 Composition 里都能用。
    //
    // 按钮排布照 HyperMusicCover：横排、等宽、中间 12dp、确认项用 primary 色。
    // 竖排堆叠是 Material 的写法，在 HyperOS 里会显得没做完 —— 系统弹窗一律是两个按钮并排。
    WindowDialog(
        show = confirmStop,
        title = "停止守护进程？",
        summary = "停止后所有应用都不会再被杀死，直到你重新启动",
        onDismissRequest = { confirmStop = false },
    ) {
        val dismiss = LocalDismissState.current
        Row(horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                modifier = Modifier.weight(1f),
                text = "取消",
                onClick = { dismiss?.invoke() },
            )
            Spacer(Modifier.width(12.dp))
            TextButton(
                modifier = Modifier.weight(1f),
                text = "停止",
                onClick = {
                    dismiss?.invoke()
                    run { ModuleRepo.stopDaemon(); "已停止" }
                },
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }

    CirnoImportDialog(
        show = showCirno,
        pickedText = cirnoPicked,
        onPickFile = { cirnoPickLauncher.launch(arrayOf("text/plain", "*/*")) },
        onDismiss = { showCirno = false },
        onImported = { msg ->
            showCirno = false
            tip = msg
            // 名单变了，主页与设置页的计数要立刻跟着变，不然用户以为没导进去
            onRefresh()
        },
    )

    A0ImportDialog(
        show = showA0,
        pickedText = a0Picked,
        onPickFile = { a0PickLauncher.launch(arrayOf("text/plain", "*/*")) },
        onDismiss = { showA0 = false },
        onImported = { msg ->
            showA0 = false
            tip = msg
            onRefresh()
        },
    )

    WindowDialog(
        show = showLog,
        title = "守护日志",
        summary = "最近 80 行",
        onDismissRequest = { showLog = false },
    ) {
        val dismiss = LocalDismissState.current
        Column {
            MiuixText(
                text = logText,
                style = MiuixTheme.textStyles.footnote2,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            )
            Row(horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    modifier = Modifier.weight(1f),
                    text = "关闭",
                    onClick = { dismiss?.invoke() },
                )
            }
        }
    }
}
