package com.yg.appkiller.ui

import android.graphics.drawable.ColorDrawable
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animate
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yg.appkiller.ui.AppSettings
import com.yg.appkiller.ui.data.DaemonStateViewModel
import com.yg.appkiller.ui.screens.AppDetailActivity
import com.yg.appkiller.ui.screens.AppListPage
import com.yg.appkiller.ui.screens.AppListViewModel
import com.yg.appkiller.ui.screens.HomePage
import com.yg.appkiller.ui.screens.RunningPage
import com.yg.appkiller.ui.screens.SettingsPage
import com.yg.appkiller.ui.theme.MiuixAppTheme
import com.yg.appkiller.ui.theme.isInDarkTheme
import com.yg.appkiller.ui.util.PagerNavigationSpringSpec
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.FloatingToolbarDefaults
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.VerticalSplit
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 一个标签的定义。
 *
 * 把标签做成数据而不是散在 when(page) 里，是因为标签数量与顺序会随产品变。
 * 散着写时改一次标签要动五处，而且很容易漏掉底栏图标与返回键的对应关系。
 *
 * 这里只描述标签的身份，不携带页面内容 —— 页面分派在 AppShellScreen 里按 key 做，
 * 共享状态从参数走，不塞成全局变量。
 */
data class AppTab(
    val key: String,
    val label: String,
    val icon: ImageVector,
)

/**
 * 四标签：主页 → 应用 → 运行 → 设置。
 *
 * 顺序是硬规则（使用频率 × 首次理解成本）：主页永远第一，设置永远最后。
 * 「运行」放在应用之后，因为它是应用列表的一个切片视图 ——
 * 用户看完「有哪些应用」，下一个问题才是「现在谁被冻着」。
 */
@Composable
fun defaultTabs(): List<AppTab> = listOf(
    AppTab("home", "主页", MiuixIcons.Home),
    AppTab("apps", "应用", MiuixIcons.VerticalSplit),
    AppTab("running", "运行", MiuixIcons.Contacts),
    AppTab("settings", "设置", MiuixIcons.Settings),
)

/**
 * 四标签应用壳。
 *
 * 三件事必须照抄，它们是踩过坑的结论：
 *
 * 1. 主题在 onStop 应用，不在设置改变时应用。切换深浅色是一次 configuration change，
 *    Activity 会被重建；在设置页里当场应用，用户会看到开关跳一下、页面闪一下。
 *
 * 2. onResume 让 settingsEpoch 自增。主题页是独立 Activity，它在
 *    本 Activity stopped 期间保存自己的改动；而本页持有的设置是 onCreate 里读的。
 *
 * 3. 点底栏用弹簧滚过去，不用 animateScrollToPage。后者是线性缓动，跨多页时像被拖着走。
 *
 * 底栏两种形态由设置决定：0 = 贴底实心、1 = 悬浮。
 * 两种形态共用应用壳这一份 backdrop，它只注册在 pager 区域、不包底栏 ——
 * 注册层不能包含采样者，否则自采样递归。页面各有一份自己的 backdrop 给顶栏用。
 */
class AppShellActivity : ComponentActivity() {

    /** 每次回到前台自增，作为 setContent 里 remember 的 key，让设置跟着重读 */
    private var settingsEpoch by mutableStateOf(0)

    override fun onResume() {
        super.onResume()
        settingsEpoch++
    }

    override fun onStop() {
        super.onStop()
        // 只在这里应用夜间模式：它会重建 Activity，别让用户看着它发生
        AppSettings.applyNightMode(this)
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false

        // ViewModel 拿不到 Context（它不该持有 Activity），由壳塞一次 applicationContext。
        // 漏了这一步的后果不是崩溃，而是应用列表永久显示「读取失败」——
        // 因为 appContext 为 null 时 refresh() 直接走 failed 分支。
        AppListViewModel.appContext = applicationContext

        val saved = AppSettings.load(this)
        // 冷启动白闪三件套之一：先用上次绘制到的那层颜色涂窗口。
        AppSettings.surfaceArgb(this)?.let { window.setBackgroundDrawable(ColorDrawable(it)) }
        AppSettings.applyNightMode(this)

        setContent {
            val settings = remember(settingsEpoch) { AppSettings.load(this@AppShellActivity) }
            val themeMode = remember(settings) {
                runCatching { ColorSchemeMode.valueOf(settings.themeMode) }
                    .getOrDefault(ColorSchemeMode.System)
            }
            MiuixAppTheme(themeMode = themeMode) {
                val surface = MiuixTheme.colorScheme.surface
                LaunchedEffect(surface, themeMode) {
                    AppSettings.rememberSurface(this@AppShellActivity, themeMode.name, surface.toArgb())
                }
                AppShellScreen(
                    tabs = defaultTabs(),
                    isFloatingNavbar = settings.isFloatingNavbar,
                    isBlurEnabled = settings.isBlurEnabled,
                )
            }
        }
    }
}

/**
 * 应用壳本体。壳负责翻页、底栏、返回键、backdrop，以及把共享状态发给各页。
 *
 * 硬约束：
 * - tabs.size 必须是 3-5；超出说明层级没设计好，应该拆二级页。
 * - tabs[0] 必须是首页；返回键契约假设「不在首页就回首页」。
 *
 * 状态只有一份：DaemonStateViewModel 在这里创建，四个页面共用同一个 StateFlow。
 * 每页各开一个轮询协程，四页同时每秒打控制端口，是「界面很卡」的直接原因之一。
 */
@Composable
fun AppShellScreen(
    tabs: List<AppTab>,
    isFloatingNavbar: Boolean,
    isBlurEnabled: Boolean,
) {
    require(tabs.size in 3..5) { "标签页数量必须在 3-5 之间" }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pageCount = tabs.size
    val pagerState = rememberPagerState(pageCount = { pageCount })
    var selectedIndex by remember { mutableIntStateOf(0) }
    var isNavigating by remember { mutableStateOf(false) }
    var navJob by remember { mutableStateOf<Job?>(null) }

    val stateViewModel: DaemonStateViewModel = viewModel()
    val state by stateViewModel.state.collectAsStateWithLifecycle()

    // 手动刷新：点状态卡时立刻再读一次，不等下一轮
    val refresh: () -> Unit = {
        scope.launch { stateViewModel.refresh() }
    }

    // 末页常有逐帧动画：把「是不是当前页」做成 lambda 传下去，而不是把 currentPage
    // 传下去，否则翻页时所有页一起重组
    val onLastPage = remember(pageCount) {
        derivedStateOf { pagerState.currentPage == pageCount - 1 }
    }
    val isLastPageCurrent: () -> Boolean = remember(onLastPage) { { onLastPage.value } }

    // 手指拖动改变页码时同步底栏选中态；程序滚动期间不回写，避免与 goToPage 抢
    LaunchedEffect(pagerState.currentPage) {
        if (!isNavigating && selectedIndex != pagerState.currentPage) {
            selectedIndex = pagerState.currentPage
        }
    }

    val surfaceColor = MiuixTheme.colorScheme.surface
    // 整屏唯一的一层 backdrop：底栏的模糊都从这里采样
    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
    val navBarMode = if (isFloatingNavbar) 1 else 0

    fun goToPage(index: Int) {
        if (index == selectedIndex) return
        navJob?.cancel()
        selectedIndex = index
        isNavigating = true
        navJob = scope.launch {
            val myJob = coroutineContext.job
            try {
                pagerState.scroll(MutatePriority.UserInput) {
                    val info = pagerState.layoutInfo
                    val pageSize = info.pageSize + info.pageSpacing
                    val distanceInPages =
                        index - pagerState.currentPage - pagerState.currentPageOffsetFraction
                    val scrollPixels = distanceInPages * pageSize
                    var previous = 0f
                    animate(
                        initialValue = 0f,
                        targetValue = scrollPixels,
                        animationSpec = PagerNavigationSpringSpec,
                    ) { value, _ ->
                        previous += scrollBy(value - previous)
                    }
                }
                if (pagerState.currentPage != index) pagerState.scrollToPage(index)
            } finally {
                if (navJob == myJob) {
                    isNavigating = false
                    if (pagerState.currentPage != index) selectedIndex = pagerState.currentPage
                }
            }
        }
    }

    // 返回键契约：不在首页就回首页，首页交给系统（退出）
    BackHandler(enabled = selectedIndex != 0) { goToPage(0) }

    Scaffold(
        bottomBar = {
            BottomNavigationBar(
                mode = navBarMode,
                tabs = tabs,
                selectedIndex = selectedIndex,
                backdrop = backdrop,
                blurActive = isBlurEnabled,
                onItemSelected = { goToPage(it) },
            )
        },
    ) { globalPadding ->
        // 悬浮底栏不占 Scaffold 的高度（它是浮在内容之上的一层），
        // 于是 calculateBottomPadding() 给不出它的实际高度，列表最后一行会被它压住。
        // 贴底模式由 Scaffold 算准，直接用；悬浮模式用实测高度（胶囊 64dp + 上下留白）。
        val navBarHeight =
            if (isFloatingNavbar) 88.dp else globalPadding.calculateBottomPadding()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (isBlurEnabled) Modifier.layerBackdrop(backdrop) else Modifier)
                .background(surfaceColor),
        ) {
            HorizontalPager(
                state = pagerState,
                // 1 = 只保留左右各一页，翻页手感与内存的折中；不要用默认 0（翻页时白屏）
                beyondViewportPageCount = 1,
                // 必须关掉：miuix 的阻尼回弹自己接管，两者叠加会在两端打架
                overscrollEffect = null,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                when (tabs[page].key) {
                    "home" -> HomePage(
                        isBlurEnabled = isBlurEnabled,
                        extraBottomPadding = navBarHeight,
                        state = state,
                        onRefresh = refresh,
                    )

                    "apps" -> AppListPage(
                        isBlurEnabled = isBlurEnabled,
                        bottomPadding = navBarHeight,
                        onEntryClick = { app ->
                            context.startActivity(AppDetailActivity.intent(context, app))
                        },
                    )

                    "running" -> RunningPage(
                        isBlurEnabled = isBlurEnabled,
                        bottomPadding = navBarHeight,
                        state = state,
                        onEntryClick = { frozen ->
                            // 运行列表里点一行，进的是同一个二级页：
                            // 用户想知道「为什么它在冻」，那里才有清理方式与进程明细。
                            context.startActivity(
                                AppDetailActivity.intent(context, frozen.pkg, frozen.pkg),
                            )
                        },
                    )

                    "settings" -> SettingsPage(
                        isBlurEnabled = isBlurEnabled,
                        extraBottomPadding = navBarHeight,
                        state = state,
                        onRefresh = refresh,
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomNavigationBar(
    mode: Int,
    tabs: List<AppTab>,
    selectedIndex: Int,
    backdrop: LayerBackdrop?,
    blurActive: Boolean,
    onItemSelected: (Int) -> Unit,
) {
    when (mode) {
        // 悬浮：FloatingNavigationBar + textureBlur
        1 -> {
            val barColor =
                if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surfaceContainer
            val shape = RoundedCornerShape(FloatingToolbarDefaults.CornerRadius)
            val isDark = isInDarkTheme()
            val highlight = remember(isDark) {
                if (isDark) Highlight.GlassStrokeMiddleDark else Highlight.GlassStrokeMiddleLight
            }
            FloatingNavigationBar(
                modifier = if (blurActive && backdrop != null) {
                    Modifier.textureBlur(
                        backdrop = backdrop,
                        shape = shape,
                        blurRadius = 25f,
                        colors = BlurDefaults.blurColors(
                            blendColors = listOf(
                                BlendColorEntry(
                                    color = MiuixTheme.colorScheme.surfaceContainer.copy(0.4f),
                                ),
                            ),
                        ),
                        highlight = highlight,
                    )
                } else {
                    Modifier
                },
                color = barColor,
            ) {
                tabs.forEachIndexed { index, tab ->
                    FloatingNavigationBarItem(
                        selected = selectedIndex == index,
                        onClick = { onItemSelected(index) },
                        icon = tab.icon,
                        label = tab.label,
                        enabled = true,
                    )
                }
            }
        }

        // 贴底实心（默认）：模糊时条本身透明，模糊由 textureBlur 提供
        else -> {
            val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface
            Box(
                modifier = Modifier
                    .then(
                        if (blurActive && backdrop != null) {
                            Modifier.textureBlur(
                                backdrop = backdrop,
                                shape = RectangleShape,
                                blurRadius = 25f,
                                colors = BlurDefaults.blurColors(
                                    blendColors = listOf(
                                        BlendColorEntry(
                                            color = MiuixTheme.colorScheme.surface.copy(0.5f),
                                        ),
                                    ),
                                ),
                            )
                        } else {
                            Modifier
                        }
                    )
                    .background(barColor),
            ) {
                NavigationBar(color = barColor) {
                    tabs.forEachIndexed { index, tab ->
                        NavigationBarItem(
                            selected = selectedIndex == index,
                            onClick = { onItemSelected(index) },
                            icon = tab.icon,
                            label = tab.label,
                            enabled = true,
                        )
                    }
                }
            }
        }
    }
}
