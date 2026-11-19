package com.yg.appkiller.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.yg.appkiller.BgApp
import com.yg.appkiller.ui.data.DaemonState
import com.yg.appkiller.ui.util.DshIconSize
import com.yg.appkiller.ui.util.DshSpacing
import com.yg.appkiller.ui.util.PageScaffoldColumn
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

// 分组卡的三种形状：提到文件顶层，避免每次重组 new 一个 RoundedCornerShape
private val GroupTopShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
private val GroupBottomShape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)

/**
 * 运行列表 = 全部后台应用，分「即将去死」与「不处理」两段。
 *
 * 数据来自应用壳每秒刷新的 [DaemonState]（一份 flow，四个页面共享），本页不再自己开轮询。
 *
 * 分段依据是「会不会被清理」，不是「系统有没有冻结」：
 *   - 即将去死 = 已冻结且不在白名单，计时到了就清理；
 *   - 不处理   = 其余全部，含未冻结的（系统还没回收，我们不插手）与白名单的（冻结了也不清理）。
 * 之前按 frozen 切，白名单应用一旦被系统冻结就会落到「即将去死」段里 —— 名字与事实相反。
 *
 * 段名用「即将去死」而不是「已冻结」：这一段的用户问题是"接下来会发生什么"，
 * 不是"系统现在把它标成了什么"。冻结是手段，清理才是结果。
 */
@Composable
fun RunningPage(
    isBlurEnabled: Boolean,
    bottomPadding: Dp,
    state: DaemonState,
    onEntryClick: (String, String) -> Unit,
) {
    // 先剔掉 PackageManager 解析不到的条目：daemon 是按 /proc/<pid>/cmdline 归包的，
    // android.process.acore 这类是进程名而不是包名，列出来既没有图标也点不进去。
    val all = resolveBackground(state.background)
    // 会不会被清理，由「冻结 + 不在白名单」共同决定；白名单应用即使被系统冻结也不会被清
    val dying = remember(all) { all.filter { it.frozen && !it.protected } }
    val skipped = remember(all) { all.filter { !(it.frozen && !it.protected) } }

    PageScaffoldColumn(title = "运行列表", isBlurEnabled = isBlurEnabled) { innerPadding ->
        // 顶部 inset 在这里吃一次；列表的 contentPadding 不再重复算，否则上方会空一大块
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding()),
        ) {
            SmallTitle(text = "后台应用 · " + all.size)
            Box(modifier = Modifier.weight(1f)) {
                RunningBody(
                    loaded = state.loaded,
                    running = state.running,
                    dying = dying,
                    skipped = skipped,
                    labels = rememberAppLabels(all),
                    bottomPadding = bottomPadding,
                    onEntryClick = onEntryClick,
                )
            }
        }
    }
}

/**
 * 只留下能在 PackageManager 里解析成应用的条目。
 *
 * 一次算完并 remember：每行单独查 pm 会在一轮几十个应用时把主线程拖死。
 */
@Composable
private fun resolveBackground(apps: List<BgApp>): List<BgApp> {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    return remember(apps) {
        val pm = ctx.packageManager
        apps.filter { runCatching { pm.getApplicationInfo(it.pkg, 0) }.isSuccess }
    }
}

/**
 * 包名 → 应用名的映射，随列表变化重算一次。
 *
 * 用 remember(all) 而不是每次行内查：一轮几十个应用就是几十次 pm 调用，
 * 每秒刷一次的话主线程直接卡死（这是「运行列表显示错误」那次的教训）。
 */
@Composable
private fun rememberAppLabels(apps: List<BgApp>): Map<String, String> {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    return remember(apps) {
        val pm = ctx.packageManager
        val m = HashMap<String, String>(apps.size * 2)
        for (a in apps) {
            m[a.pkg] = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(a.pkg, 0)).toString()
            }.getOrDefault(a.pkg)
        }
        m
    }
}

@Composable
private fun rememberAppIcon(pkg: String): ImageBitmap? {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    return remember(pkg) {
        runCatching {
            ctx.packageManager.getApplicationIcon(pkg).toBitmap(116, 116).asImageBitmap()
        }.getOrNull()
    }
}

@Composable
private fun RunningBody(
    loaded: Boolean,
    running: Boolean,
    dying: List<BgApp>,
    skipped: List<BgApp>,
    labels: Map<String, String>,
    bottomPadding: Dp,
    onEntryClick: (String, String) -> Unit,
) {
    AnimatedVisibility(visible = loaded, enter = fadeIn(), exit = fadeOut()) {
        if (dying.isEmpty() && skipped.isEmpty()) {
            RunningEmptyHint(running = running, bottomPadding = bottomPadding)
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = bottomPadding + DshSpacing.Block),
            ) {
                if (dying.isNotEmpty()) {
                    item(key = "h-dying") {
                        SmallTitle(text = "即将去死 · " + dying.size)
                    }
                    items(dying.size, key = { "d-" + dying[it].pkg }) { i ->
                        val app = dying[i]
                        val label = labels[app.pkg] ?: app.pkg
                        BgRow(
                            app = app,
                            label = label,
                            isFirst = i == 0,
                            isLast = i == dying.lastIndex,
                            isOnly = dying.size == 1,
                            onClick = { onEntryClick(app.pkg, label) },
                        )
                    }
                }
                if (skipped.isNotEmpty()) {
                    item(key = "h-skipped") {
                        SmallTitle(text = "不处理 · " + skipped.size)
                    }
                    items(skipped.size, key = { "s-" + skipped[it].pkg }) { i ->
                        val app = skipped[i]
                        val label = labels[app.pkg] ?: app.pkg
                        BgRow(
                            app = app,
                            label = label,
                            isFirst = i == 0,
                            isLast = i == skipped.lastIndex,
                            isOnly = skipped.size == 1,
                            onClick = { onEntryClick(app.pkg, label) },
                        )
                    }
                }
            }
        }
    }

    AnimatedVisibility(visible = !loaded, enter = fadeIn(), exit = fadeOut()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            InfiniteProgressIndicator()
        }
    }
}

@Composable
private fun BgRow(
    app: BgApp,
    label: String,
    isFirst: Boolean,
    isLast: Boolean,
    isOnly: Boolean,
    onClick: () -> Unit,
) {
    if (isOnly) {
        Card(
            modifier = Modifier.padding(horizontal = DshSpacing.Block),
            cornerRadius = 16.dp,
            colors = CardDefaults.defaultColors(color = colorScheme.surfaceContainer),
            pressFeedbackType = PressFeedbackType.Tilt,
            showIndication = true,
            onClick = onClick,
        ) {
            BgRowContent(app, label)
        }
        return
    }

    val shape = when {
        isFirst -> GroupTopShape
        isLast -> GroupBottomShape
        else -> RectangleShape
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DshSpacing.Block)
            .clip(shape)
            .background(colorScheme.surfaceContainer, shape)
            .clickable(onClick = onClick),
    ) {
        BgRowContent(app, label)
    }
}

@Composable
private fun BgRowContent(app: BgApp, label: String) {
    val icon = rememberAppIcon(app.pkg)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = DshSpacing.Block, vertical = DshSpacing.Compact),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.size(DshIconSize.ListIcon).padding(end = DshSpacing.CardInside),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            MiuixText(
                text = label,
                style = MiuixTheme.textStyles.title4,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // subtitle 是状态短语：会清的说还剩多久，不会清的直接说原因（白名单 / 不处理）。
            // 文案形状统一成「已冻结|未冻结 · 白名单|不处理」，一眼能对上表格式的两列判断。
            MiuixText(
                text = when {
                    app.protected && app.frozen -> "已冻结 · 白名单"
                    app.protected -> "未冻结 · 白名单"
                    app.frozen ->
                        "已冻结 " + durationText(app.frozenSec) + " · 剩余 " + durationText(app.remainSec)
                    else -> "未冻结 · 不处理"
                },
                style = MiuixTheme.textStyles.body2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = colorScheme.onSurfaceVariantSummary,
            )
            // summary 是标识，不是描述
            MiuixText(
                text = app.pkg,
                style = MiuixTheme.textStyles.footnote1,
                color = colorScheme.onSurfaceVariantSummary,
            )
        }
        Spacer(Modifier.width(DshSpacing.Block))
        // 一个列表项最多一个标签：进程数是最想知道的那个数
        StatusTag(
            TagSpec(
                label = app.frozenProcs.toString() + "/" + app.totalProcs + " 进程",
                // 只有「真的会被清」的才给绿标；白名单与未冻结一律灰，避免看起来像在倒计时
                background = if (app.frozen && !app.protected) Color(60, 179, 113) else Color(0xFF9E9E9E),
                contentColor = Color.White,
            ),
        )
    }
}

/** 秒数转人话：120 秒 → 2 分钟，超过 1 小时才用小时。 */
private fun durationText(sec: Long): String = when {
    sec <= 0L -> "0 秒"
    sec < 60 -> sec.toString() + " 秒"
    sec < 3600 -> (sec / 60).toString() + " 分钟"
    else -> (sec / 3600).toString() + " 小时"
}

/**
 * 空态分三种：还没读到 / 守护没在跑 / 跑了但没有后台应用。
 *
 * 三句合成一句「暂无数据」是这类页面最常见的偷懒：用户不知道是该去开守护，
 * 还是该去用几个应用，还是该等一会儿。
 */
@Composable
private fun RunningEmptyHint(running: Boolean, bottomPadding: Dp) {
    Box(
        modifier = Modifier.fillMaxSize().padding(bottom = bottomPadding),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            MiuixText(
                text = if (running) "当前没有后台应用" else "守护进程未运行",
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.size(DshSpacing.Compact))
            MiuixText(
                text = if (running) "应用退到后台后会出现在这里"
                else "去设置页点「启动守护」",
                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                color = colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}
