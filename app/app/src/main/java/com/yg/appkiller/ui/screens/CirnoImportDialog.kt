package com.yg.appkiller.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yg.appkiller.AppRepo
import com.yg.appkiller.ModuleRepo
import com.yg.appkiller.ui.util.DshSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * Cirno 名单导入的候选框。
 *
 * 为什么不再走 SAF 选文件：Cirno 的配置固定写在 /data/system/Cirno/ApplicationSettings.json，
 * 位置不会变、格式不会变。让用户自己去文件管理器里翻出这个文件，等于把「知道路径」
 * 这件事外包给用户 —— 而且普通文件选择器根本进不到 /data/system。
 * 现在直接读，读完把条目摆出来让用户挑。
 *
 * 三条设计取舍：
 * 1. **默认全选**。用户点「导入 Cirno 名单」的意图就是「把 Cirno 那套搬过来」，
 *    默认不勾等于让他重新勾一遍。
 * 2. **合并 / 覆盖必须显式选，默认合并**。覆盖会丢掉现有名单，不能是默认值。
 * 3. **条目显示应用名，包名放第二行**。Cirno 存的是包名，但用户认的是图标下面的名字；
 *    只显示包名他没法判断该不该勾。已卸载的包照常列出并标注，不然会「明明勾了却没生效」。
 */
@Composable
fun CirnoImportDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onImported: (String) -> Unit,
) {
    if (!show) return
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var merge by remember { mutableStateOf(true) }
    // 三个列表都用 SnapshotStateList：勾选要触发重绘，普通 List 改了不会刷新
    val white = remember { mutableStateListOf<CirnoCandidate>() }
    val black = remember { mutableStateListOf<CirnoCandidate>() }
    val selected = remember { mutableStateListOf<String>() }

    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val json = ModuleRepo.readCirnoConfig() ?: return@runCatching null
                val (w, b) = ModuleRepo.parseCirnoApps(json)
                if (w.isEmpty() && b.isEmpty()) return@runCatching null
                // 应用名与「是否已安装」一次算完，别在列表项里查 pm
                val labels = AppRepo.labelMap(context.applicationContext)
                val wl = w.map { CirnoCandidate(it, labels[it], it in labels) }
                val bl = b.map { CirnoCandidate(it, labels[it], it in labels) }
                wl to bl
            }.getOrNull()
        }
        if (result == null) {
            error = "没读到 Cirno 配置。请确认已安装 Cirno 并至少配置过一次名单。"
        } else {
            white.addAll(result.first)
            black.addAll(result.second)
            // 默认全选：用户点进来的意图就是把 Cirno 那套搬过来
            selected.addAll(result.first.map { "w:" + it.pkg })
            selected.addAll(result.second.map { "b:" + it.pkg })
        }
        loading = false
    }

    val dismiss = LocalDismissState.current

    WindowDialog(
        show = show,
        title = "导入 Cirno 名单",
        summary = if (loading) "正在读取…" else if (error.isNotEmpty()) "读取失败" else
            "白名单 " + white.size + " 项 · 黑名单 " + black.size + " 项，已勾选 " + selected.size + " 项",
        onDismissRequest = onDismiss,
    ) {
        Column {
            if (error.isNotEmpty()) {
                MiuixText(
                    text = error,
                    style = MiuixTheme.textStyles.body2,
                    color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.fillMaxWidth().padding(bottom = DshSpacing.Block),
                )
            } else {
                // 列表限高：条目可能有上百条，不限高会把下面的开关和按钮挤出弹窗。
                // 260dp 是「至少能看到 4 条 + 分组头」与「留够开关和按钮」的折中。
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                    if (white.isNotEmpty()) {
                        item(key = "h-white") { CirnoGroupHeader("Cirno 白名单") }
                        items(white, key = { "w-" + it.pkg }) { c ->
                            CirnoCandidateRow(
                                candidate = c,
                                checked = ("w:" + c.pkg) in selected,
                                onToggle = { toggle(selected, "w:" + c.pkg) },
                            )
                        }
                    }
                    if (black.isNotEmpty()) {
                        item(key = "h-black") { CirnoGroupHeader("Cirno 黑名单") }
                        items(black, key = { "b-" + it.pkg }) { c ->
                            CirnoCandidateRow(
                                candidate = c,
                                checked = ("b:" + c.pkg) in selected,
                                onToggle = { toggle(selected, "b:" + c.pkg) },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(DshSpacing.Block))

                // 合并 / 覆盖：覆盖会丢现有名单，所以放在勾选区下面、紧邻按钮，
                // 不放到标题栏里 —— 那里容易被当成装饰。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        MiuixText(
                            text = if (merge) "合并到现有名单" else "覆盖现有名单",
                            style = MiuixTheme.textStyles.body1,
                            fontWeight = FontWeight.Medium,
                        )
                        MiuixText(
                            text = if (merge) "保留现有条目，把勾选项加进去"
                            else "先清空现有白/黑名单，只保留勾选项",
                            style = MiuixTheme.textStyles.footnote2,
                            color = colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    Switch(checked = !merge, onCheckedChange = { merge = !it })
                }
            }

            Spacer(Modifier.height(DshSpacing.Block))

            Row(horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    modifier = Modifier.weight(1f),
                    text = "取消",
                    onClick = {
                        dismiss?.invoke()
                        onDismiss()
                    },
                )
                Spacer(Modifier.width(DshSpacing.Block))
                TextButton(
                    modifier = Modifier.weight(1f),
                    text = "导入",
                    enabled = error.isEmpty() && !loading && selected.isNotEmpty(),
                    onClick = {
                        val w = selected.filter { it.startsWith("w:") }.map { it.removePrefix("w:") }
                        val b = selected.filter { it.startsWith("b:") }.map { it.removePrefix("b:") }
                        val (wn, bn) = ModuleRepo.importCirnoSelection(w, b, merge)
                        dismiss?.invoke()
                        onImported("已导入：白名单 " + wn + " 项 · 黑名单 " + bn + " 项")
                    },
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

/** Cirno 里的一条：包名 + 解析出的应用名 + 是否还装着 */
private data class CirnoCandidate(
    val pkg: String,
    val label: String?,
    val installed: Boolean,
)

private fun toggle(list: MutableList<String>, key: String) {
    if (!list.remove(key)) list.add(key)
}

@Composable
private fun CirnoGroupHeader(text: String) {
    MiuixText(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        fontWeight = FontWeight.Medium,
        color = colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.fillMaxWidth().padding(
            start = DshSpacing.Compact,
            end = DshSpacing.Compact,
            top = DshSpacing.Compact,
            bottom = DshSpacing.Tight,
        ),
    )
}

@Composable
private fun CirnoCandidateRow(
    candidate: CirnoCandidate,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            // 整行可点：勾选框本身只有 24dp，手指点在文字上没反应会被当成卡住
            .clickable(onClick = onToggle)
            .padding(horizontal = DshSpacing.Compact, vertical = DshSpacing.Compact),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            MiuixText(
                text = candidate.label ?: candidate.pkg,
                style = MiuixTheme.textStyles.body2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 已安装的显示包名做对照；未安装的直说，避免「勾了却没生效」
            MiuixText(
                text = if (candidate.installed) candidate.pkg else candidate.pkg + "（未安装）",
                style = MiuixTheme.textStyles.footnote2,
                color = colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(DshSpacing.Compact))
        // Checkbox 自己不带可点区域，点击交给整行
        Checkbox(
            state = if (checked) ToggleableState.On else ToggleableState.Off,
            onClick = onToggle,
        )
    }
}
