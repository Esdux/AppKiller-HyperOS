package com.yg.appkiller.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.yg.appkiller.AppRepo
import com.yg.appkiller.ModuleRepo
import com.yg.appkiller.ui.util.DshSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * A0 后台优化（A0_Memory）名单导入的候选框。
 *
 * 读取策略与 Cirno 一致：A0 的配置固定写在 /sdcard/Android/A0_Memory/config.conf，
 * 存在就直接读（省掉「翻文件管理器找路径」这一步）；不存在、或用户想换一份配置时，
 * 点右上角的文件夹按钮走系统文件选择器 —— 选中的文本通过 [pickedText] 传进来。
 *
 * 格式映射（用户指定，仅用于适配）：
 *   KILL <包名>           → per-app，清理方式 force_stop
 *   KILL <包名>:<子进程>  → per-proc，清理方式 force_stop
 *   WHITE <包名>          → 白名单；勾了「存为跳过不清理」则额外写 per-proc skip
 *
 * 三条设计取舍：
 * 1. **默认全选**：用户点进来的意图就是「把 A0 那套搬过来」。
 * 2. **合并 / 覆盖默认合并**：覆盖会丢掉现有名单，不能是默认值。
 * 3. **白名单两种落地必须让用户选**：白名单是「不清理」，跳过不清理是「不清理但也不占名额」，
 *    语义不同，不能替他决定。
 */
@Composable
fun A0ImportDialog(
    show: Boolean,
    /** 用户通过文件选择器选中的配置文本；空串表示「没选过」，此时走默认路径直读 */
    pickedText: String,
    onPickFile: () -> Unit,
    onDismiss: () -> Unit,
    onImported: (String) -> Unit,
) {
    if (!show) return
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var merge by remember { mutableStateOf(true) }
    // 白名单落地方式：false = 写白名单（推荐），true = 写 per-proc 跳过不清理
    var whiteAsSkip by remember { mutableStateOf(false) }
    val apps = remember { mutableStateListOf<String>() }
    val procs = remember { mutableStateListOf<String>() }
    val white = remember { mutableStateListOf<String>() }
    // 勾选集合：前缀区分三类，避免包名与进程名同名时冲突
    val selected = remember { mutableStateListOf<String>() }

    // pickedText 变化（用户重新选了个文件）才重算，避免每次重组都清空勾选。
    // pickedText 为空时读默认路径；默认路径也没有就提示用户点右上角选文件。
    LaunchedEffect(pickedText) {
        loading = true
        error = ""
        apps.clear(); procs.clear(); white.clear(); selected.clear()
        val pair = withContext(Dispatchers.IO) {
            runCatching {
                val text = if (pickedText.isNotEmpty()) pickedText else ModuleRepo.readA0Config()
                if (text.isNullOrBlank()) return@runCatching (null to true)
                val (a, p, w) = ModuleRepo.parseA0Config(text)
                if (a.isEmpty() && p.isEmpty() && w.isEmpty()) return@runCatching (null to false)
                Triple(a, p, w) to false
            }.getOrNull()
        }
        val result = pair?.first
        val noFile = pair?.second ?: false
        if (result == null) {
            error = if (noFile)
                "没找到 /sdcard/Android/A0_Memory/config.conf。点右上角的文件夹按钮选一份配置。"
            else
                "这个文件里没有 KILL / WHITE 条目。点右上角换一份 A0 的 config.conf。"
        } else {
            apps.addAll(result.first)
            procs.addAll(result.second)
            white.addAll(result.third)
            selected.addAll(result.first.map { "a:" + it })
            selected.addAll(result.second.map { "p:" + it })
            selected.addAll(result.third.map { "w:" + it })
        }
        loading = false
    }

    val dismiss = LocalDismissState.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    WindowDialog(
        show = show,
        title = "导入 A0 后台优化名单",
        summary = if (loading) "正在读取…" else if (error.isNotEmpty()) "读取失败" else
            "KILL " + (apps.size + procs.size) + " 项 · WHITE " + white.size + " 项，已勾选 " + selected.size + " 项",
        onDismissRequest = onDismiss,
    ) {
        Column {
            // 右上角换文件的入口。WindowDialog 的标题栏是固定结构、塞不进自定义控件，
            // 所以放在内容区第一行并右对齐 —— 视觉位置同样是「框的右上角」，
            // 且不会因为标题过长被挤掉。
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = DshSpacing.Tight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MiuixText(
                    text = if (pickedText.isNotEmpty()) "已选文件" else "默认路径配置",
                    style = MiuixTheme.textStyles.footnote2,
                    color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onPickFile) {
                    Icon(
                        imageVector = MiuixIcons.Folder,
                        contentDescription = "选择配置文件",
                        tint = colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            if (error.isNotEmpty()) {
                MiuixText(
                    text = error,
                    style = MiuixTheme.textStyles.body2,
                    color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.fillMaxWidth().padding(bottom = DshSpacing.Block),
                )
            } else {
                // 限高 260dp：条目可能上百，不限高会把下面的开关和按钮挤出弹窗
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                    if (apps.isNotEmpty()) {
                        item(key = "h-app") { A0GroupHeader("KILL · 整包（存为 force stop）") }
                        items(apps, key = { "a-" + it }) { pkg ->
                            A0Row(
                                title = pkg,
                                subtitle = "清理方式 → force stop",
                                checked = ("a:" + pkg) in selected,
                                onToggle = { on -> toggle(selected, "a:" + pkg, on) },
                            )
                        }
                    }
                    if (procs.isNotEmpty()) {
                        item(key = "h-proc") { A0GroupHeader("KILL · 子进程（存为 force stop）") }
                        items(procs, key = { "p-" + it }) { proc ->
                            A0Row(
                                title = proc.substringBefore(':'),
                                subtitle = proc.substringAfter(':', "") + " → force stop",
                                checked = ("p:" + proc) in selected,
                                onToggle = { on -> toggle(selected, "p:" + proc, on) },
                            )
                        }
                    }
                    if (white.isNotEmpty()) {
                        item(key = "h-white") { A0GroupHeader("WHITE · 白名单") }
                        items(white, key = { "w-" + it }) { pkg ->
                            A0Row(
                                title = pkg,
                                subtitle = if (whiteAsSkip) "→ 跳过不清理" else "→ 白名单",
                                checked = ("w:" + pkg) in selected,
                                onToggle = { on -> toggle(selected, "w:" + pkg, on) },
                            )
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth().padding(top = DshSpacing.Block)) {
                    MiuixText(
                        text = "白名单存为跳过不清理",
                        style = MiuixTheme.textStyles.body2,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = whiteAsSkip, onCheckedChange = { whiteAsSkip = it })
                }
                Row(modifier = Modifier.fillMaxWidth().padding(top = DshSpacing.Compact)) {
                    MiuixText(
                        text = "覆盖现有名单",
                        style = MiuixTheme.textStyles.body2,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = !merge, onCheckedChange = { merge = !it })
                }
            }

            Row(modifier = Modifier.fillMaxWidth().padding(top = DshSpacing.Block)) {
                TextButton(
                    modifier = Modifier.weight(1f),
                    text = "取消",
                    onClick = { dismiss?.invoke() },
                )
                TextButton(
                    modifier = Modifier.weight(1f),
                    text = "导入",
                    enabled = error.isEmpty() && selected.isNotEmpty(),
                    onClick = {
                        val a = selected.filter { it.startsWith("a:") }.map { it.removePrefix("a:") }
                        val p = selected.filter { it.startsWith("p:") }.map { it.removePrefix("p:") }
                        val w = selected.filter { it.startsWith("w:") }.map { it.removePrefix("w:") }
                        // 写盘要 fork 好几次 su，必须离开主线程，否则弹窗会卡住不动
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                ModuleRepo.importA0Selection(a, p, w, whiteAsSkip, merge)
                            }
                            onImported(
                                "已导入 " + r.first + " 个整包 / " + r.second + " 个进程 / " + r.third + " 个白名单"
                            )
                        }
                    },
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

@Composable
private fun A0GroupHeader(text: String) {
    MiuixText(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color = colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.fillMaxWidth().padding(top = DshSpacing.Compact, bottom = DshSpacing.Tight),
    )
}

@Composable
private fun A0Row(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = DshSpacing.Compact)) {
        Column(modifier = Modifier.weight(1f)) {
            MiuixText(text = title, style = MiuixTheme.textStyles.body2)
            MiuixText(
                text = subtitle,
                style = MiuixTheme.textStyles.footnote2,
                color = colorScheme.onSurfaceVariantSummary,
            )
        }
        Checkbox(
            state = if (checked) ToggleableState.On else ToggleableState.Off,
            onClick = { onToggle(!checked) },
        )
    }
}

private fun toggle(list: MutableList<String>, key: String, on: Boolean) {
    if (on) { if (key !in list) list.add(key) } else list.remove(key)
}