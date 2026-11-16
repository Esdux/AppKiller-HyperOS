@file:OptIn(ExperimentalScrollBarApi::class)
package com.yg.appkiller
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yg.appkiller.ui.app.LocalAppState
import com.yg.appkiller.ui.app.LocalIsWideScreen
import com.yg.appkiller.ui.custom.CustomBasicComponent
import com.yg.appkiller.ui.utils.AdaptiveTopAppBar
import com.yg.appkiller.ui.utils.BlurredBar
import com.yg.appkiller.ui.utils.CirnoCard
import com.yg.appkiller.ui.utils.LocalImageBackdrop
import com.yg.appkiller.ui.utils.cirnoCardBackground
import com.yg.appkiller.ui.utils.pageContentPadding
import com.yg.appkiller.ui.utils.pageScrollModifiers
import com.yg.appkiller.ui.utils.rememberBlurBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Search
import top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

private val MonitorListTopShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
private val MonitorListBottomShape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)

/**
 * 运行列表：与 Cirno MonitorPage 同构 —— SearchBar + SmallTitle("冻结信息") +
 * 分片卡片列表（首行圆角顶、尾行圆角底、中间直角）+ VerticalScrollBar。
 * 行内容与 FrozenAppItemCompose 对齐：图标 + 名称 + "N进程 N已冻结 V2" + 右侧剩余/已冻结。
 */
@Composable
fun RunningPage() {
    val ctx = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    val isWideScreen = LocalIsWideScreen.current
    val appState = LocalAppState.current

    var expanded by remember { mutableStateOf(false) }
    var searchValue by remember { mutableStateOf("") }
    var snap by remember { mutableStateOf(StatusSnapshot()) }
    var labels by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var cfg by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var tick by remember { mutableIntStateOf(0) }
    var hasLoadedOnce by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var armedPkg by remember { mutableStateOf("") }
    var armedAt by remember { mutableStateOf(0L) }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(tick) {
        val d = withContext(Dispatchers.IO) {
            arrayOf<Any>(StateRepo.snapshot(), ModuleRepo.configMap())
        }
        snap = d[0] as StatusSnapshot
        cfg = d[1] as Map<String, String>
        hasLoadedOnce = true
    }
    LaunchedEffect(Unit) {
        labels = withContext(Dispatchers.IO) { AppRepo.labelMap(ctx) }
        while (true) {
            delay(1000)
            tick++
        }
    }

    val filteredApps = snap.apps.filter {
        searchValue.isBlank() || it.pkg.contains(searchValue, true) ||
            (labels[it.pkg] ?: "").contains(searchValue, true)
    }
    val timeoutSec = ((cfg["TIMEOUT_MIN"]?.toDoubleOrNull() ?: 30.0) * 60.0).toLong().coerceAtLeast(1)

    val imageBackdrop = LocalImageBackdrop.current
    val backdrop = if (imageBackdrop != null) null else rememberBlurBackdrop()
    val blurActive = imageBackdrop != null || backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
        topBar = {
            BlurredBar(backdrop, blurActive, scrollBehavior) {
                AdaptiveTopAppBar(
                    title = "运行列表",
                    isWideScreen = isWideScreen,
                    scrollBehavior = scrollBehavior,
                    color = barColor,
                )
            }
        },
        popupHost = {},
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            val lazyListState = rememberLazyListState()
            val contentPadding =
                pageContentPadding(innerPadding, innerPadding, isWideScreen, extraTop = 12.dp)
            val navigationBarPadding =
                WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val listContentPadding = PaddingValues(
                start = contentPadding.calculateStartPadding(LayoutDirection.Ltr),
                top = 0.dp,
                end = contentPadding.calculateEndPadding(LayoutDirection.Ltr),
                bottom = contentPadding.calculateBottomPadding() + navigationBarPadding + 96.dp,
            )
            Column(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = 12.dp,
                            end = 12.dp,
                            top = contentPadding.calculateTopPadding(),
                        ),
                ) {
                    SearchBar(
                        modifier = Modifier.padding(bottom = 6.dp),
                        inputField = {
                            InputField(
                                query = searchValue,
                                onQueryChange = { searchValue = it },
                                onSearch = { keyboardController?.hide() },
                                expanded = expanded,
                                onExpandedChange = { expanded = it },
                                label = "搜索",
                                leadingIcon = {
                                    Icon(
                                        modifier = Modifier
                                            .padding(start = 12.dp, end = 8.dp)
                                            .size(20.dp)
                                            .alpha(0.4f),
                                        imageVector = MiuixIcons.Basic.Search,
                                        tint = colorScheme.onSurfaceContainer,
                                        contentDescription = "Search",
                                    )
                                },
                            )
                        },
                        outsideEndAction = {
                            Text(
                                modifier = Modifier
                                    .padding(end = 12.dp)
                                    .clickable(
                                        interactionSource = null,
                                        indication = null,
                                    ) {
                                        expanded = false
                                        searchValue = ""
                                    },
                                text = "取消",
                                style = TextStyle(
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                ),
                                color = colorScheme.primary,
                            )
                        },
                        expanded = expanded,
                        onExpandedChange = { expanded = it },
                    ) {
                    }

                    SmallTitle(text = "冻结信息")
                }

                Box(modifier = Modifier.weight(1f)) {
                    LazyColumn(
                        state = lazyListState,
                        modifier = Modifier.pageScrollModifiers(
                            appState.enableScrollEndHaptic,
                            true,
                            scrollBehavior,
                        ),
                        contentPadding = listContentPadding,
                    ) {
                        val appCount = filteredApps.size
                        itemsIndexed(
                            items = filteredApps,
                            key = { _, item -> item.pkg },
                        ) { i, item ->
                            val itemLabel = labels[item.pkg] ?: item.pkg.substringAfterLast('.')
                            val onTap: () -> Unit = {
                                val now = System.currentTimeMillis()
                                if (armedPkg == item.pkg && now - armedAt < 3000L) {
                                    armedPkg = ""
                                    scope.launch {
                                        val r = withContext(Dispatchers.IO) { Ctrl.killPkg(item.pkg, "force_stop") }
                                        Toast.makeText(ctx, "已清理 " + itemLabel + "：" + r.trim(), Toast.LENGTH_SHORT).show()
                                    }
                                    tick++
                                } else {
                                    armedPkg = item.pkg
                                    armedAt = now
                                    Toast.makeText(ctx, "再次点击将杀死 " + itemLabel, Toast.LENGTH_SHORT).show()
                                }
                            }
                            val onLong: () -> Unit = {
                                ctx.startActivity(
                                    Intent(ctx, AppDetailActivity::class.java)
                                        .putExtra("pkg", item.pkg)
                                        .putExtra("label", itemLabel)
                                )
                            }
                            val content: @Composable () -> Unit = {
                                FrozenAppItem(item, itemLabel, timeoutSec, onTap, onLong)
                            }
                            if (appCount == 1) {
                                CirnoCard(modifier = Modifier.padding(horizontal = 12.dp)) {
                                    content()
                                }
                            } else {
                                val isFirst = i == 0
                                val isLast = i == appCount - 1
                                val shape = when {
                                    isFirst -> MonitorListTopShape
                                    isLast -> MonitorListBottomShape
                                    else -> RectangleShape
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp)
                                        .clip(shape)
                                        .cirnoCardBackground(shape, colorScheme.surfaceContainer),
                                ) {
                                    content()
                                }
                            }
                        }

                        item {
                            if (hasLoadedOnce && appCount == 0) {
                                Text(
                                    text = "当前没有被冻结计时的应用",
                                    color = colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 32.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }

                    VerticalScrollBar(
                        adapter = rememberScrollBarAdapter(lazyListState),
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                        trackPadding = listContentPadding,
                    )
                }
            }
        }
    }
}

/** 与 Cirno FrozenAppItemCompose 同构的行：图标 + 名称 + 进程/冻结注记 + 右侧剩余与已冻结。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FrozenAppItem(
    app: FrozenApp,
    label: String,
    timeoutSec: Long,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val subtitleColor = colorScheme.onSurfaceVariantSummary
    val progress = if (timeoutSec > 0) {
        (app.frozenSec.toFloat() / timeoutSec * 100f).coerceIn(0f, 100f)
    } else 0f

    val subtitleText = buildAnnotatedString {
        withStyle(SpanStyle(color = subtitleColor)) {
            append(app.totalProcs.toString() + "进程")
            if (app.frozenProcs > 0) {
                append(" " + app.frozenProcs.toString() + "已冻结  ")
                withStyle(SpanStyle(color = Color(0xFFFF8C00))) {
                    append("V2")
                }
            }
        }
    }

    CustomBasicComponent(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        title = label,
        subtitleAnnotated = subtitleText,
        rightText = String.format(java.util.Locale.ROOT, "%.2f%%", progress),
        rightTextColor = colorScheme.onSurfaceVariantSummary,
        leftAction = {
            AppIcon(pkg = app.pkg, label = label, size = 46.dp)
            Spacer(Modifier.size(16.dp))
        },
        rightActions = {
            Text(
                text = "剩余 " + app.remainSec + "s",
                fontSize = 14.sp,
                color = colorScheme.onSurfaceVariantSummary,
            )
        },
        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
    )
}
