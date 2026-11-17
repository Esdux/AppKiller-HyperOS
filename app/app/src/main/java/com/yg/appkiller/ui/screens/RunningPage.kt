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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.yg.appkiller.Ctrl
import com.yg.appkiller.FrozenApp
import com.yg.appkiller.ui.data.DaemonState
import com.yg.appkiller.ui.util.DshIconSize
import com.yg.appkiller.ui.util.DshSpacing
import com.yg.appkiller.ui.util.PageScaffoldColumn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 * 运行列表 = 守护进程正在冻结的应用。
 *
 * 数据来自应用壳每秒刷新的 [DaemonState]（一份 flow，四个页面共享），
 * 本页不再自己开轮询 —— 早先每页各开一个 tick 协程，四个页面同时跑，
 * 就是「App 界面很卡」的直接原因之一。
 *
 * 行内容五槽位（与 skill 的列表项规范一致）：
 * 图标 → 应用名 → 状态短语（冻结时长/剩余）→ 标识（包名）→ 右侧标签（进程数）。
 * 应用名必须用真实 label，不能显示包名尾巴 —— 用户认不出 com.netease.cloudmusic。
 */
@Composable
fun RunningPage(
    isBlurEnabled: Boolean,
    bottomPadding: Dp,
    state: DaemonState,
    onEntryClick: (FrozenApp, String) -> Unit,
) {
    val apps = state.watching
    PageScaffoldColumn(title = "运行列表", isBlurEnabled = isBlurEnabled) { innerPadding ->
        // 顶部 inset 在这里吃一次；列表的 contentPadding 不再重复算，否则上方会空一大块
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding()),
        ) {
            SmallTitle(
                text = if (apps.isEmpty()) "准备去死 · 0"
                else "准备去死 · " + apps.size,
            )
            Box(modifier = Modifier.weight(1f)) {
                RunningBody(
                    loaded = state.loaded,
                    running = state.running,
                    apps = apps,
                    labels = rememberAppLabels(apps),
                    topPadding = 0.dp,
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
 * 用 remember(apps) 而不是每次行内查：一轮 20 个应用就是 20 次 pm 调用，
 * 每秒刷一次的话每秒 20 次，主线程直接卡死（这是「运行列表显示错误」那次的教训）。
 */
@Composable
private fun rememberAppLabels(apps: List<FrozenApp>): Map<String, String> {
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
    apps: List<FrozenApp>,
    labels: Map<String, String>,
    topPadding: Dp,
    bottomPadding: Dp,
    onEntryClick: (FrozenApp, String) -> Unit,
) {
    AnimatedVisibility(visible = loaded, enter = fadeIn(), exit = fadeOut()) {
        if (apps.isEmpty()) {
            RunningEmptyHint(running = running, bottomPadding = bottomPadding)
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    top = topPadding,
                    bottom = bottomPadding + DshSpacing.Block,
                ),
            ) {
                itemsIndexed(
                    items = apps,
                    key = { _, app -> app.pkg },
                ) { index, app ->
                    val label = labels[app.pkg] ?: app.pkg
                    RunningRow(
                        app = app,
                        label = label,
                        isFirst = index == 0,
                        isLast = index == apps.lastIndex,
                        isOnly = apps.size == 1,
                        // 名称一起传出去：二级页要显示「微信」而不是「com.tencent.mm」，
                        // 而它自己只有一个包名，查名要再走一次 pm。
                        onClick = { onEntryClick(app, label) },
                    )
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
private fun RunningRow(
    app: FrozenApp,
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
            RunningRowContent(app, label)
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
        RunningRowContent(app, label)
    }
}

@Composable
private fun RunningRowContent(app: FrozenApp, label: String) {
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
            // subtitle 是状态短语，一句话说清「现在怎么样」：
            // 冻结了多久、还剩多久。这两个数每秒都在变，所以它必须是主行下面第一行。
            MiuixText(
                text = "已冻结 " + durationText(app.frozenSec) +
                    " · 剩余 " + durationText(app.remainSec),
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
                background = if (app.frozenProcs > 0) Color(60, 179, 113) else Color(0xFF9E9E9E),
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
 * 空态分三种：还没读到 / 守护没在跑 / 跑了但没有需要冻结的应用。
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
                text = if (running) "暂时没有需要杀死的应用" else "守护进程未运行",
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.size(DshSpacing.Compact))
            MiuixText(
                text = if (running) "应用退到后台超过设定时间后会出现在这里"
                else "去设置页点「启动守护」",
                fontSize = MiuixTheme.textStyles.footnote1.fontSize,
                color = colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}
