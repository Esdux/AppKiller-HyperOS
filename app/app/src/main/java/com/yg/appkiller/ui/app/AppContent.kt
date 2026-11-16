package com.yg.appkiller.ui.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.yg.appkiller.AppListPage
import com.yg.appkiller.RunningPage
import com.yg.appkiller.SettingsPage
import com.yg.appkiller.StatusPage
import com.yg.appkiller.ui.custom.liquid.IosLiquidGlassNavigationBar
import com.yg.appkiller.ui.utils.rememberBlurBackdrop
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.icon.extended.Favorites
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.VerticalSplit
import top.yukonga.miuix.kmp.theme.MiuixTheme

const val PAGE_COUNT = 4

private val PAGE_TITLES = listOf("主页", "应用列表", "运行列表", "设置")

private val PAGE_ICONS: List<ImageVector> = listOf(
    MiuixIcons.VerticalSplit,
    MiuixIcons.Contacts,
    MiuixIcons.Favorites,
    MiuixIcons.Settings,
)

/** 应用外壳：横向分页 + 液态玻璃悬浮导航栏（取自 VCut Lower 的 IosLiquidGlassNavigationBar）。 */
@Composable
fun AppContent() {
    val appState = LocalAppState.current
    val pagerState = rememberPagerState(pageCount = { PAGE_COUNT })
    val scope = rememberCoroutineScope()
    val surfaceColor = MiuixTheme.colorScheme.surface
    val backdrop = if (appState.blur) rememberBlurBackdrop() else null
    val navItems = remember { List(PAGE_COUNT) { i -> NavigationItem(PAGE_TITLES[i], PAGE_ICONS[i]) } }

    Box(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = appState.enablePageUserScroll,
            modifier = Modifier
                .fillMaxSize()
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier),
            key = { it },
        ) { page ->
            when (page) {
                0 -> StatusPage(callback = { index -> scope.launch { pagerState.animateScrollToPage(index) } })
                1 -> AppListPage()
                2 -> RunningPage()
                else -> SettingsPage()
            }
        }

        IosLiquidGlassNavigationBar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 12.dp),
            items = navItems,
            selectedIndex = pagerState.currentPage,
            onItemClick = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
            backdrop = backdrop,
            isBlurActive = appState.blur && backdrop != null,
        )
    }
}
