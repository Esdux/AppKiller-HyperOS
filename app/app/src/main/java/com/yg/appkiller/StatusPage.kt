@file:OptIn(ExperimentalScrollBarApi::class)
package com.yg.appkiller

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ErrorOutline
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yg.appkiller.ui.app.LocalIsWideScreen
import com.yg.appkiller.ui.app.isInDarkTheme
import com.yg.appkiller.ui.utils.AdaptiveTopAppBar
import com.yg.appkiller.ui.utils.BlurredBar
import com.yg.appkiller.ui.utils.CirnoCard
import com.yg.appkiller.ui.utils.LocalImageBackdrop
import com.yg.appkiller.ui.utils.pageContentPadding
import com.yg.appkiller.ui.utils.pageScrollModifiers
import com.yg.appkiller.ui.utils.rememberBlurBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.isDynamicColor
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * 主页：与 Cirno InfoPage 同构 —— 警告卡 + 状态卡（工作中/版本 + 白名单/黑名单计数）
 * + 运行信息卡 + 日志入口卡。
 */
@Composable
fun StatusPage(callback: (Int) -> Unit = {}) {
    val isWideScreen = LocalIsWideScreen.current
    val imageBackdrop = LocalImageBackdrop.current
    val backdrop = if (imageBackdrop != null) null else rememberBlurBackdrop()
    val blurActive = imageBackdrop != null || backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface
    val topAppBarScrollBehavior = MiuixScrollBehavior()

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            BlurredBar(backdrop, blurActive, topAppBarScrollBehavior) {
                AdaptiveTopAppBar(
                    title = "AppKiller",
                    isWideScreen = isWideScreen,
                    scrollBehavior = topAppBarScrollBehavior,
                    color = barColor,
                )
            }
        },
    ) { innerPadding ->
        InfoContent(
            padding = PaddingValues(
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding(),
            ),
            topAppBarScrollBehavior = topAppBarScrollBehavior,
            backdrop = backdrop,
            callback = callback,
        )
    }
}

@Composable
private fun InfoContent(
    padding: PaddingValues,
    topAppBarScrollBehavior: top.yukonga.miuix.kmp.basic.ScrollBehavior,
    backdrop: top.yukonga.miuix.kmp.blur.LayerBackdrop?,
    callback: (Int) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val isWideScreen = LocalIsWideScreen.current
    val lazyListState = rememberLazyListState()
    val contentPadding = pageContentPadding(padding, padding, isWideScreen)

    var running by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(true) }
    var pid by remember { mutableIntStateOf(0) }
    var snap by remember { mutableStateOf(StatusSnapshot()) }
    var cfg by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var whiteCount by remember { mutableIntStateOf(-1) }
    var blackCount by remember { mutableIntStateOf(-1) }
    var tick by remember { mutableIntStateOf(0) }
    var logTail by remember { mutableStateOf("") }
    var showLog by remember { mutableStateOf(false) }

    LaunchedEffect(tick) {
        val d = withContext(Dispatchers.IO) {
            val r = ModuleRepo.running()
            val s = StateRepo.snapshot()
            val c = ModuleRepo.configMap()
            val w = ModuleRepo.list("protect.list").count { it.isNotBlank() && !it.startsWith("#") }
            val b = ModuleRepo.list("blacklist.list").count { it.isNotBlank() && !it.startsWith("#") }
            arrayOf<Any>(r, s, c, w, b)
        }
        running = d[0] as Boolean
        snap = d[1] as StatusSnapshot
        cfg = d[2] as Map<String, String>
        whiteCount = d[3] as Int
        blackCount = d[4] as Int
        pid = snap.pid
        connecting = false
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            tick++
        }
    }

    val timeoutSec = ((cfg["TIMEOUT_MIN"]?.toDoubleOrNull() ?: 30.0) * 60.0).toLong().coerceAtLeast(1)
    val frozenTotal = snap.apps.sumOf { it.frozenProcs }
    val longest = snap.apps.maxOfOrNull { it.frozenSec } ?: 0L

    Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
        LazyColumn(
            state = lazyListState,
            modifier = Modifier.pageScrollModifiers(true, true, topAppBarScrollBehavior),
            contentPadding = contentPadding,
        ) {
            item {
                Column(
                    modifier = Modifier.padding(vertical = 12.dp, horizontal = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (!connecting && !running) {
                        WarningCard("守护进程未运行：后台冻结超时清理不生效，可在下方启动")
                    }
                    StatusCard(
                        active = running,
                        working = running && !connecting,
                        connecting = connecting,
                        version = "v1.2 (pid " + (if (pid > 0) pid.toString() else "—") + ")",
                        whiteCount = whiteCount,
                        blackCount = blackCount,
                        onClickWhitelist = { callback(1) },
                        onClickBlacklist = { callback(1) },
                    )
                    InfoCard(
                        running = running,
                        timeoutSec = timeoutSec,
                        killMode = cfg["KILL_MODE"] ?: "sigkill",
                        killScope = cfg["KILL_SCOPE"] ?: "pkg",
                        dryRun = (cfg["DRY_RUN"] ?: "0") == "1",
                        frozenTotal = frozenTotal,
                        longest = longest,
                        watching = snap.apps.size,
                        onToggle = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    if (running) ModuleRepo.stopDaemon() else ModuleRepo.startDaemon(ctx)
                                }
                                delay(600)
                                tick++
                            }
                        },
                    )
                    LogCard(
                        expanded = showLog,
                        tail = logTail,
                        onExpand = {
                            showLog = !showLog
                            if (showLog && logTail.isEmpty()) {
                                scope.launch {
                                    logTail = withContext(Dispatchers.IO) { ModuleRepo.logTail(40) }
                                }
                            }
                        },
                    )
                }
            }
            item { Spacer(modifier = Modifier.height(96.dp)) }
        }

        VerticalScrollBar(
            adapter = rememberScrollBarAdapter(lazyListState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            trackPadding = contentPadding,
        )
    }

}

@Composable
private fun StatusCard(
    modifier: Modifier = Modifier,
    active: Boolean,
    working: Boolean,
    connecting: Boolean = false,
    version: String,
    whiteCount: Int,
    blackCount: Int,
    onClickWhitelist: () -> Unit = {},
    onClickBlacklist: () -> Unit = {},
    alpha: Boolean = false,
    colors: CardColors = CardDefaults.defaultColors(),
) {
    val isWideScreen = LocalIsWideScreen.current
    val cardHeight = if (isWideScreen) 300.dp else 220.dp
    val statusIconSize = if (isWideScreen) 170.dp else 128.dp
    val statusIconOffsetX = if (isWideScreen) 38.dp else 20.dp
    val statusIconOffsetY = if (isWideScreen) 45.dp else 28.dp

    Row(
        modifier = Modifier.fillMaxWidth().height(cardHeight),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CirnoCard(
            modifier = modifier.weight(1f).fillMaxHeight(),
            colors = CardDefaults.defaultColors(
                color = when {
                    connecting -> if (alpha) colorScheme.surfaceContainer.copy(0.35f) else colorScheme.surfaceContainer
                    isDynamicColor -> if (alpha) colorScheme.secondaryContainer.copy(0.35f) else colorScheme.secondaryContainer
                    isInDarkTheme() -> if (working) (if (alpha) Color(0xFF1A3825).copy(0.35f) else Color(0xFF1A3825)) else (if (alpha) Color(0xFF381A1A).copy(0.35f) else Color(0xFF381A1A))
                    else -> if (working) (if (alpha) Color(0xFFDFFAE4).copy(0.35f) else Color(0xFFDFFAE4)) else (if (alpha) Color(0xFFFADFDF).copy(0.35f) else Color(0xFFFADFDF))
                },
            ),
            showIndication = true,
            pressFeedbackType = if (alpha) PressFeedbackType.None else PressFeedbackType.Tilt,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier.fillMaxSize().offset(statusIconOffsetX, statusIconOffsetY),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    if (connecting) {
                        InfiniteProgressIndicator(modifier = Modifier.size(48.dp))
                    } else {
                        Icon(
                            modifier = Modifier.size(statusIconSize),
                            imageVector = if (working) Icons.Rounded.CheckCircleOutline else Icons.Rounded.ErrorOutline,
                            tint = if (isDynamicColor) {
                                colorScheme.primary.copy(0.8f)
                            } else {
                                if (working) Color(0xFF36D167) else Color(0xFFD13636)
                            },
                            contentDescription = null,
                        )
                    }
                }
                Column(modifier = Modifier.fillMaxSize().padding(all = 16.dp)) {
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        text = when {
                            connecting -> "连接中"
                            working -> "工作中"
                            else -> "未运行"
                        },
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        text = "版本: " + version,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            CirnoCard(
                modifier = modifier.fillMaxWidth().weight(1f),
                insideMargin = PaddingValues(16.dp),
                onClick = { onClickWhitelist() },
                showIndication = true,
                pressFeedbackType = if (alpha) PressFeedbackType.None else PressFeedbackType.Tilt,
                colors = colors,
            ) {
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        text = "白名单",
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        text = if (!active || whiteCount < 0) "N/A" else whiteCount.toString(),
                        fontSize = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colorScheme.onSurface,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            CirnoCard(
                modifier = modifier.fillMaxWidth().weight(1f),
                insideMargin = PaddingValues(16.dp),
                onClick = { onClickBlacklist() },
                showIndication = true,
                pressFeedbackType = if (alpha) PressFeedbackType.None else PressFeedbackType.Tilt,
                colors = colors,
            ) {
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        text = "黑名单",
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        text = if (!active || blackCount < 0) "N/A" else blackCount.toString(),
                        fontSize = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun WarningCard(
    message: String,
    modifier: Modifier = Modifier,
    alpha: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    CirnoCard(
        onClick = { onClick?.invoke() },
        colors = CardDefaults.defaultColors(
            color = when {
                isDynamicColor -> if (alpha) colorScheme.errorContainer.copy(0.35f) else colorScheme.errorContainer
                isInDarkTheme() -> if (alpha) Color(0XFF310808).copy(0.35f) else Color(0XFF310808)
                else -> if (alpha) Color(0XFF310808).copy(0.35f) else Color(0xFFF8E2E2)
            },
        ),
        modifier = modifier,
        showIndication = onClick != null,
        pressFeedbackType = if (alpha) PressFeedbackType.None else PressFeedbackType.Tilt,
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = message,
                color = if (isDynamicColor) colorScheme.onErrorContainer else Color(0xFFF72727),
                fontSize = 14.sp,
            )
        }
    }
}

@Composable
private fun InfoCard(
    running: Boolean,
    timeoutSec: Long,
    killMode: String,
    killScope: String,
    dryRun: Boolean,
    frozenTotal: Int,
    longest: Long,
    watching: Int,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    colors: CardColors = CardDefaults.defaultColors(),
) {
    @Composable
    fun InfoText(title: String, content: String, bottomPadding: Dp = 24.dp, m: Modifier = Modifier) {
        Text(
            text = title,
            fontSize = MiuixTheme.textStyles.headline1.fontSize,
            fontWeight = FontWeight.Medium,
            color = colorScheme.onSurface,
            modifier = m,
        )
        Text(
            text = content,
            fontSize = MiuixTheme.textStyles.body2.fontSize,
            color = colorScheme.onSurfaceVariantSummary,
            modifier = m.padding(top = 2.dp, bottom = bottomPadding),
        )
    }
    CirnoCard(colors = colors, modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            InfoText(
                title = "清理阈值",
                content = (timeoutSec / 60).toString() + " 分 " + (timeoutSec % 60).toString() + " 秒",
            )
            InfoText(
                title = "清理方式",
                content = killMode + " · 范围 " + killScope + (if (dryRun) " · 观察模式" else ""),
            )
            InfoText(
                title = "冻结状态",
                content = watching.toString() + " 个应用计时中 · " + frozenTotal + " 个进程被冻结 · 最久 " + longest + " 秒",
            )
            InfoText(
                title = "守护进程",
                content = if (running) "运行中" else "已停止",
                bottomPadding = 8.dp,
            )
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                text = if (running) "停止守护进程" else "启动守护进程",
                onClick = onToggle,
            )
        }
    }
}

@Composable
private fun LogCard(
    expanded: Boolean,
    tail: String,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
    colors: CardColors = CardDefaults.defaultColors(),
) {
    CirnoCard(modifier = modifier.fillMaxWidth(), colors = colors) {
        Column {
            ArrowPreference(
                title = "守护日志",
                summary = if (expanded) "收起" else "查看最近 40 行",
                onClick = onExpand,
            )
            if (expanded && tail.isNotEmpty()) {
                Text(
                    text = tail,
                    fontSize = 11.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                )
            }
        }
    }
}
