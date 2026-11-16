@file:OptIn(ExperimentalScrollBarApi::class)
package com.yg.appkiller
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only

import android.content.Intent
import android.os.PowerManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.key
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yg.appkiller.ui.app.LocalAppState
import com.yg.appkiller.ui.app.LocalIsWideScreen
import com.yg.appkiller.ui.app.isInDarkTheme
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Search
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.window.WindowListPopup

private val AppListTopShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
private val AppListBottomShape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)

/** 右上角 ⋮ + 系统/用户应用切换（与 Cirno 的 TopBarActions 一致） */
@Composable
private fun TopBarActions(onlySystem: Boolean, onSelect: (Boolean) -> Unit) {
    val showTopPopup = remember { mutableStateOf(false) }
    val topPopupHoldDown = remember { mutableStateOf(false) }
    val hapticFeedback = LocalHapticFeedback.current
    IconButton(
        onClick = {
            showTopPopup.value = true
            topPopupHoldDown.value = true
        },
        holdDownState = topPopupHoldDown.value,
    ) {
        Icon(
            imageVector = MiuixIcons.More,
            contentDescription = "WindowListPopup",
            tint = colorScheme.onBackground,
        )
    }
    WindowListPopup(
        show = showTopPopup.value,
        popupPositionProvider = ListPopupDefaults.ContextMenuPositionProvider,
        alignment = PopupPositionProvider.Align.TopEnd,
        onDismissRequest = { showTopPopup.value = false },
        onDismissFinished = { topPopupHoldDown.value = false },
        content = {
            val state = LocalDismissState.current
            val items = listOf("用户应用", "系统应用")
            ListPopupColumn {
                items.forEachIndexed { index, string ->
                    key(index) {
                        DropdownImpl(
                            text = string,
                            optionSize = items.size,
                            isSelected = (if (onlySystem) 1 else 0) == index,
                            onSelectedIndexChange = {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                                onSelect(index == 1)
                                state?.invoke()
                            },
                            index = index,
                        )
                    }
                }
            }
        },
    )
}

@Composable
fun AppListPage() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()
    val appState = LocalAppState.current
    val isWideScreen = LocalIsWideScreen.current
    val hapticFeedback = LocalHapticFeedback.current

    var expanded by remember { mutableStateOf(false) }
    var searchValue by remember { mutableStateOf("") }
    var onlySystem by remember { mutableStateOf(false) }
    var all by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var white by remember { mutableStateOf<Set<String>>(emptySet()) }
    var black by remember { mutableStateOf<Set<String>>(emptySet()) }
    var optimized by remember { mutableStateOf<Set<String>>(emptySet()) }
    var dirty by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var refreshTick by remember { mutableStateOf(0) }

    LaunchedEffect(refreshTick) {
        val d = withContext(Dispatchers.IO) {
            val apps = AppRepo.installed(ctx)
            val w = ModuleRepo.list("protect.list").toSet()
            val b = ModuleRepo.list("blacklist.list").toSet()
            val pm = ctx.getSystemService(PowerManager::class.java)
            val opt = apps.mapNotNull { e ->
                runCatching {
                    if (pm != null && !pm.isIgnoringBatteryOptimizations(e.pkg)) e.pkg else null
                }.getOrNull()
            }.toSet()
            arrayOf<Any>(apps, w, b, opt)
        }
        all = d[0] as List<AppEntry>
        white = d[1] as Set<String>
        black = d[2] as Set<String>
        optimized = d[3] as Set<String>
        loading = false
    }

    val pullToRefreshState = rememberPullToRefreshState()
    var isRefreshing by remember { mutableStateOf(false) }
    LaunchedEffect(isRefreshing) {
        if (!isRefreshing) return@LaunchedEffect
        refreshTick++
        isRefreshing = false
    }

    val filteredApps = all
        .filter { if (onlySystem) it.system else !it.system }
        .filter {
            searchValue.isBlank() || it.pkg.contains(searchValue, true) ||
                it.label.contains(searchValue, true)
        }
        .sortedBy { it.label.lowercase() }

    val keyboardController = LocalSoftwareKeyboardController.current
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
                    title = "应用列表",
                    isWideScreen = isWideScreen,
                    scrollBehavior = scrollBehavior,
                    color = barColor,
                    actions = {
                        TopBarActions(onlySystem) {
                            onlySystem = it
                            scope.launch { }
                        }
                    },
                )
            }
        },
        popupHost = {},
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            AnimatedVisibility(visible = !loading, enter = fadeIn(), exit = fadeOut()) {
                PullToRefresh(
                    isRefreshing = isRefreshing,
                    onRefresh = { isRefreshing = true },
                    pullToRefreshState = pullToRefreshState,
                    topAppBarScrollBehavior = scrollBehavior,
                    contentPadding = PaddingValues(
                        top = innerPadding.calculateTopPadding() + 12.dp,
                        bottom = if (isWideScreen) {
                            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                        } else {
                            0.dp
                        },
                    ),
                ) {
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

                            SmallTitle(text = "应用信息")
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
                                item {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = "共 " + all.size + " 个应用 · 白名单 " +
                                                white.size + " · 黑名单 " + black.size,
                                            fontSize = 12.sp,
                                            color = colorScheme.onSurfaceVariantSummary,
                                            modifier = Modifier.weight(1f),
                                        )

                                    }
                                }

                                val appCount = filteredApps.size
                                itemsIndexed(
                                    items = filteredApps,
                                    key = { _, item -> item.pkg },
                                ) { i, item ->
                                    val inWhite = white.contains(item.pkg)
                                    val inBlack = black.contains(item.pkg)
                                    val configured = inWhite || inBlack
                                    val onTap = {
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                                        ctx.startActivity(
                                            Intent(ctx, AppDetailActivity::class.java)
                                                .putExtra("pkg", item.pkg)
                                                .putExtra("label", item.label)
                                        )
                                    }
                                    val content: @Composable () -> Unit = {
                                        CustomBasicComponent(
                                            title = item.label,
                                            subtitle = if (optimized.contains(item.pkg)) "电池优化" else null,
                                            subtitleColor = Color(0xFF3CB371),
                                            subtitleMaxLines = 1,
                                            subtitleOverflow = TextOverflow.Ellipsis,
                                            summary = item.pkg,
                                            leftAction = {
                                                AppIcon(pkg = item.pkg, label = item.label, size = 46.dp)
                                                Spacer(Modifier.size(16.dp))
                                            },
                                            rightActions = {
                                                if (configured) {
                                                    StatusTag(
                                                        label = if (inBlack) "黑名单" else "白名单",
                                                        backgroundColor = if (inBlack) {
                                                            Color(0xFFD13636)
                                                        } else if (isInDarkTheme()) {
                                                            Color.White
                                                        } else {
                                                            Color.Black
                                                        },
                                                        contentColor = if (inBlack) Color.White else if (isInDarkTheme()) Color.Black else Color.White,
                                                    )
                                                }
                                            },
                                            onClick = { onTap() },
                                            insideMargin = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                        )
                                    }
                                    if (appCount == 1) {
                                        CirnoCard(modifier = Modifier.padding(horizontal = 12.dp)) {
                                            content()
                                        }
                                    } else {
                                        val isFirst = i == 0
                                        val isLast = i == appCount - 1
                                        val shape = when {
                                            isFirst -> AppListTopShape
                                            isLast -> AppListBottomShape
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
                                    if (notice.isNotEmpty()) {
                                        Text(
                                            text = notice,
                                            fontSize = 12.sp,
                                            color = colorScheme.primary,
                                            modifier = Modifier.padding(start = 20.dp, top = 8.dp),
                                        )
                                    }
                                    if (saving) {
                                        Text(
                                            text = "保存中…",
                                            fontSize = 12.sp,
                                            color = colorScheme.onSurfaceVariantSummary,
                                            modifier = Modifier.padding(start = 20.dp, top = 8.dp),
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
    }
}

@Composable
private fun StatusTag(label: String, backgroundColor: Color, contentColor: Color) {
    Box(
        modifier = Modifier.background(
            color = backgroundColor,
            shape = RoundedCornerShape(6.dp),
        ),
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            text = label,
            color = contentColor,
            fontSize = 9.sp,
            fontWeight = FontWeight(750),
            maxLines = 1,
            softWrap = false,
        )
    }
}
