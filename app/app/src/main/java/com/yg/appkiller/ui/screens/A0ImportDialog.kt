package com.yg.appkiller.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yg.appkiller.A0Config
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
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
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
 * 格式映射（用户指定，仅用于适配）—— **带不带冒号决定粒度，不需要再选**：
 *   KILL  <包名>           → per-app，清理方式 force_stop
 *   KILL  <包名>:<子进程>  → per-proc，清理方式 force_stop
 *   WHITE <包名>           → 白名单（protect.list）
 *   WHITE <包名>:<子进程>  → per-proc，清理方式 skip（只跳过这个进程）
 *
 * 四条设计取舍：
 * 1. **按包名分组，一个应用只出现一次**。A0 的名单是按「条目」列的，同一个包常有
 *    整包 KILL 加若干子进程 KILL，平铺出来就是同一个包名重复好几行，看不出这些条目
 *    其实属于同一个应用。分组后一行一个应用，展开才看得到具体条目。
 * 2. **组勾选框是三态**：全选 / 部分选（Indeterminate）/ 未选。点它整组全选或全不选。
 * 3. **显示应用名，包名放副标题**，与 Cirno 候选框保持一致。A0 存的是包名，但用户认的
 *    是桌面上的名字；只给包名他没法判断该不该勾。已卸载的包照常列出并标注。
 * 4. **合并 / 覆盖默认合并**：覆盖会丢掉现有名单，不能是默认值。
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
    val groups = remember { mutableStateListOf<A0Group>() }
    // 展开的包名集合：默认全部折叠，几百条平铺出来没法用
    val expanded = remember { mutableStateListOf<String>() }
    // 勾选集合：前缀区分三类，避免包名与进程名同名时冲突
    val selected = remember { mutableStateListOf<String>() }

    // pickedText 变化（用户重新选了个文件）才重算，避免每次重组都清空勾选。
    // pickedText 为空时读默认路径；默认路径也没有就提示用户点右上角选文件。
    LaunchedEffect(pickedText) {
        loading = true
        error = ""
        groups.clear(); expanded.clear(); selected.clear()
        val pair = withContext(Dispatchers.IO) {
            runCatching {
                val text = if (pickedText.isNotEmpty()) pickedText else ModuleRepo.readA0Config()
                if (text.isNullOrBlank()) return@runCatching (null to true)
                val cfg = ModuleRepo.parseA0Config(text)
                if (cfg.isEmpty) return@runCatching (null to false)
                // 应用名一次算完，别在列表项里逐个查 pm
                val labels = AppRepo.labelMap(context.applicationContext)
                (buildA0Groups(cfg, labels) to false)
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
            groups.addAll(result)
            // 默认全选：用户点进来的意图就是把 A0 那套搬过来
            for (g in result) for (it in g.items) selected.add(it.key)
        }
        loading = false
    }

    val dismiss = LocalDismissState.current
    val scope = rememberCoroutineScope()
    val totalItems = groups.sumOf { it.items.size }

    WindowDialog(
        show = show,
        title = "导入 A0 内存管理配置",
        summary = if (loading) "正在读取…" else if (error.isNotEmpty()) "读取失败" else
            groups.size.toString() + " 个应用 · " + totalItems + " 项，已勾选 " + selected.size + " 项",
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
                // 限高 300dp：条目可能上百，不限高会把下面的开关和按钮挤出弹窗
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                    for (g in groups) {
                        item(key = "g-" + g.pkg) {
                            A0GroupRow(
                                group = g,
                                checked = groupStateOf(g, selected),
                                expanded = g.pkg in expanded,
                                onToggleGroup = {
                                    if (groupStateOf(g, selected) == ToggleableState.On) {
                                        for (it in g.items) selected.remove(it.key)
                                    } else {
                                        for (it in g.items) if (it.key !in selected) selected.add(it.key)
                                    }
                                },
                                onExpand = {
                                    if (!expanded.remove(g.pkg)) expanded.add(g.pkg)
                                },
                            )
                        }
                        if (g.pkg in expanded) {
                            items(g.items, key = { "i-" + it.key }) { it ->
                                A0ItemRow(
                                    item = it,
                                    checked = it.key in selected,
                                    onToggle = {
                                        if (!selected.remove(it.key)) selected.add(it.key)
                                    },
                                )
                            }
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth().padding(top = DshSpacing.Block)) {
                    MiuixText(
                        text = "覆盖现有名单",
                        style = MiuixTheme.textStyles.body2,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = !merge, onCheckedChange = { merge = !it })
                }
            }

            Spacer(Modifier.height(DshSpacing.Block))

            // 按钮排布与设置页的危险操作确认框一致：等宽、中间 12dp、确认项用 primary 色。
            // 少那个 Spacer 时两个按钮会贴在一起，看起来像一个控件被劈成两半。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    modifier = Modifier.weight(1f),
                    text = "取消",
                    onClick = {
                        // 两个都要调：dismiss 收弹层，onDismiss 清外部的 show 状态。
                        // 只调 dismiss 时弹层关了但 showA0 仍是 true，下次点导入会什么都不发生。
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
                        val a = selected.filter { it.startsWith("a:") }.map { it.removePrefix("a:") }
                        val p = selected.filter { it.startsWith("p:") }.map { it.removePrefix("p:") }
                        val w = selected.filter { it.startsWith("w:") }.map { it.removePrefix("w:") }
                        val wp = selected.filter { it.startsWith("x:") }.map { it.removePrefix("x:") }
                        // 写盘要 fork 好几次 su，必须离开主线程，否则弹窗会卡住不动
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                ModuleRepo.importA0Selection(a, p, w, wp, merge)
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

/** A0 里的一条：整包 KILL / 子进程 KILL / 整包白名单 / 子进程跳过 */
private data class A0Item(
    /** a = 整包清理，p = 子进程清理，w = 整包白名单，x = 子进程跳过；同时作为勾选 key 的前缀 */
    val kind: String,
    /** 整包与白名单是包名，子进程是 pkg:proc */
    val value: String,
) {
    val key: String get() = kind + ":" + value
}

/** 按包名聚成的一组：一个应用一行，展开才看得到它名下的条目 */
private data class A0Group(
    val pkg: String,
    val label: String?,
    val items: List<A0Item>,
) {
    val installed: Boolean get() = label != null
}

/**
 * 把平铺的三张表按包名聚成组。
 *
 * 顺序：先整包 KILL，再子进程 KILL（按进程名排），最后白名单 —— 用户看一组条目时
 * 关心的顺序就是「整包要不要杀 → 哪些子进程要杀 → 要不要豁免」，不按这个排会显得乱。
 * 组按包名排序，应用名只做显示。
 */
private fun buildA0Groups(
    cfg: A0Config,
    labels: Map<String, String>,
): List<A0Group> {
    // LinkedHashMap 保留首次出现顺序，后面再统一按包名排序
    val map = LinkedHashMap<String, MutableList<A0Item>>()
    for (pkg in cfg.apps) map.getOrPut(pkg) { mutableListOf() }.add(A0Item("a", pkg))
    for (proc in cfg.procs) {
        val pkg = proc.substringBefore(':')
        if (pkg.isEmpty()) continue
        map.getOrPut(pkg) { mutableListOf() }.add(A0Item("p", proc))
    }
    for (pkg in cfg.white) map.getOrPut(pkg) { mutableListOf() }.add(A0Item("w", pkg))
    for (proc in cfg.whiteProcs) {
        val pkg = proc.substringBefore(':')
        if (pkg.isEmpty()) continue
        map.getOrPut(pkg) { mutableListOf() }.add(A0Item("x", proc))
    }
    return map.entries
        .sortedBy { it.key }
        .map { (pkg, items) ->
            val ordered = items.sortedWith(
                compareBy({ it.kind != "a" }, { it.kind != "p" }, { it.kind != "w" }, { it.value })
            )
            A0Group(pkg, labels[pkg], ordered)
        }
}

/** 组的三态：全选 / 部分选 / 未选 */
private fun groupStateOf(group: A0Group, selected: List<String>): ToggleableState {
    var n = 0
    for (it in group.items) if (it.key in selected) n++
    return when {
        n == 0 -> ToggleableState.Off
        n == group.items.size -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
}

/** 组行：应用名 + 包名 + 条目摘要，右侧三态勾选框与展开箭头 */
@Composable
private fun A0GroupRow(
    group: A0Group,
    checked: ToggleableState,
    expanded: Boolean,
    onToggleGroup: () -> Unit,
    onExpand: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            // 整行可点：勾选框只有 24dp，点文字没反应会被当成卡住
            .clickable(onClick = onToggleGroup)
            .padding(horizontal = DshSpacing.Compact, vertical = DshSpacing.Compact),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            MiuixText(
                text = group.label ?: group.pkg,
                style = MiuixTheme.textStyles.body2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 已安装的显示包名做对照；未安装的直说，避免「勾了却没生效」
            MiuixText(
                text = if (group.installed) group.pkg else group.pkg + "（未安装）",
                style = MiuixTheme.textStyles.footnote2,
                color = colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            MiuixText(
                text = summaryOf(group),
                style = MiuixTheme.textStyles.footnote2,
                color = colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(DshSpacing.Tight))
        Checkbox(state = checked, onClick = onToggleGroup)
        // 展开箭头单独可点，且不触发勾选 —— 想细看条目不该顺手改掉勾选状态。
        // 用 ChevronForward 转 90 度当向下箭头：MiuixIcons 里的 ExpandMore / ExpandLess
        // 是「展开/收起面板」的方框角标，不是箭头，直接用会渲染成怪异的拐角。
        IconButton(onClick = onExpand) {
            Icon(
                imageVector = MiuixIcons.ChevronForward,
                contentDescription = if (expanded) "收起" else "展开",
                tint = colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(20.dp).rotate(if (expanded) 90f else 0f),
            )
        }
    }
}

/** 组摘要：整包清理 / N 个子进程清理 / 白名单 / N 个进程跳过，只列存在的 */
private fun summaryOf(group: A0Group): String {
    val parts = ArrayList<String>(4)
    if (group.items.any { it.kind == "a" }) parts.add("整包清理")
    val killProcs = group.items.count { it.kind == "p" }
    if (killProcs > 0) parts.add(killProcs.toString() + " 个子进程清理")
    if (group.items.any { it.kind == "w" }) parts.add("白名单")
    val skipProcs = group.items.count { it.kind == "x" }
    if (skipProcs > 0) parts.add(skipProcs.toString() + " 个进程跳过")
    return parts.joinToString(" · ")
}

/** 展开后的子条目行：左侧缩进，标题是该条目本身 */
@Composable
private fun A0ItemRow(
    item: A0Item,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    val title: String
    val subtitle: String
    when (item.kind) {
        "a" -> {
            title = "整包清理";
            subtitle = "→ force stop";
        }
        "p" -> {
            title = item.value.substringAfter(':', "");
            subtitle = "子进程 → force stop";
        }
        "w" -> {
            title = "整包白名单";
            subtitle = "→ 不清理";
        }
        else -> {
            title = item.value.substringAfter(':', "");
            subtitle = "子进程 → 跳过不清理";
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle)
            .padding(
                start = DshSpacing.CardInside,
                end = DshSpacing.Compact,
                top = DshSpacing.Tight,
                bottom = DshSpacing.Tight,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            MiuixText(
                text = title,
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            MiuixText(
                text = subtitle,
                style = MiuixTheme.textStyles.footnote2,
                color = colorScheme.onSurfaceVariantSummary,
            )
        }
        Spacer(Modifier.width(DshSpacing.Compact))
        Checkbox(
            state = if (checked) ToggleableState.On else ToggleableState.Off,
            onClick = onToggle,
        )
    }
}
