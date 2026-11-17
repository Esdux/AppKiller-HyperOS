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
 * 运行列表 = 全部后台应用，已冻结的在上、未冻结的在下。
 *
 * 数据来自应用壳每秒刷新的 [DaemonState]（一份 flow，四个页面共享），本页不再自己开轮询。
 *
 * 为什么分两段：只有「已冻结」一段时，用户看不出那些后台活着但没被系统冻结的应用 ——
 * 它们不会被清理，但确实占着内存，是「为什么还这么卡」的答案。
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
    val all = state.background
    val frozen = remember(all) { all.filter { it.frozen } }
    val others = remember(all) { all.filter { !it.frozen } }

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
                    frozen = frozen,
                    others = others,
                    labels = rememberAppLabels(all),
                    bottomPadding = bottomPadding,
                    onEntryClick = onEntryClick,
                )
            }
        }
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
    frozen: List<BgApp>,
    others: List<BgApp>,
    labels: Map<String, String>,
    bottomPadding: Dp,
    onEntryClick: (String, String) -> Unit,
) {
    AnimatedVisibility(visible = loaded, enter = fadeIn(), exit = fadeOut()) {
        if (frozen.isEmpty() && others.isEmpty()) {
            RunningEmptyHint(running = running, bottomPadding = bottomPadding)
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = bottomPadding + DshSpacing.Block),
            ) {
                if (frozen.isNotEmpty()) {
                    item(key = "h-frozen") {
                        SmallTitle(text = "即将去死 · " + frozen.size)
                    }
                    items(frozen.size, key = { "f-" + frozen[it].pkg }) { i ->
                        val app = frozen[i]
                        val label = labels[app.pkg] ?: app.pkg
                        BgRow(
                            app = app,
                            label = label,
                            isFirst = i == 0,
                            isLast = i == frozen.lastIndex,
                            isOnly = frozen.size == 1,
                            onClick = { onEntryClick(app.pkg, label) },
                        )
                    }
                }
                if (others.isNotEmpty()) {
                    item(key = "h-others") {
                        SmallTitle(text = "未冻结 · " + others.size)
                    }
                    items(others.size, key = { "o-" + others[it].pkg }) { i ->
                        val app = others[i]
                        val label = labels[app.pkg] ?: app.pkg
                        BgRow(
                            app = app,
                            label = label,
                            isFirst = i == 0,
                            isLast = i == others.lastIndex,
                            isOnly = others.size == 1,
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
            // subtitle 是状态短语：冻结了多久、还剩多久；未冻结的直接说明为什么不会被清
            MiuixText(
                text = if (app.frozen)
                    "已冻结 " + durationText(app.frozenSec) + " · 剩余 " + durationText(app.remainSec)
                else "未冻结 · 系统尚未回收，不参与清理",
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
                background = if (app.frozen) Color(60, 179, 113) else Color(0xFF9E9E9E),
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
