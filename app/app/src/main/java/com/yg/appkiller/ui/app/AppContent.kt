package com.yg.appkiller.ui.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.yg.appkiller.AppListPage
import com.yg.appkiller.RunningPage
import com.yg.appkiller.SettingsPage
import com.yg.appkiller.StatusPage
import com.yg.appkiller.ui.custom.FloatingBottomBar
import com.yg.appkiller.ui.custom.FloatingBottomBarItem
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.size
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Favorites
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.icon.extended.VerticalSplit

const val PAGE_COUNT = 4

private val PAGE_TITLES = listOf("主界面", "运行列表", "应用列表", "设置")

/** 应用外壳：横向分页 + 浮动底栏，结构与 Cirno 的 Home 一致。 */
@Composable
fun AppContent() {
    val appState = LocalAppState.current
    val pagerState = rememberPagerState(pageCount = { PAGE_COUNT })
    val scope = rememberCoroutineScope()
    val surfaceColor = MiuixTheme.colorScheme.surface
    val backdrop = rememberLayerBackdrop { drawRect(surfaceColor) }

    Box(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = appState.enablePageUserScroll,
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop),
            key = { it },
        ) { page ->
            when (page) {
                0 -> StatusPage()
                1 -> RunningPage()
                2 -> AppListPage()
                else -> SettingsPage()
            }
        }

        FloatingBottomBar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 28.dp)
                .width(320.dp),
            selectedIndex = { pagerState.currentPage },
            onSelected = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
            backdrop = backdrop,
            tabsCount = PAGE_COUNT,
            isBlurEnabled = appState.blur,
        ) {
            FloatingBottomBarItem(onClick = { scope.launch { pagerState.animateScrollToPage(0) } }) {
                BottomBarLabel(tabIndex = 0, selected = pagerState.currentPage == 0)
            }
            FloatingBottomBarItem(onClick = { scope.launch { pagerState.animateScrollToPage(1) } }) {
                BottomBarLabel(tabIndex = 1, selected = pagerState.currentPage == 1)
            }
            FloatingBottomBarItem(onClick = { scope.launch { pagerState.animateScrollToPage(2) } }) {
                BottomBarLabel(tabIndex = 2, selected = pagerState.currentPage == 2)
            }
            FloatingBottomBarItem(onClick = { scope.launch { pagerState.animateScrollToPage(3) } }) {
                BottomBarLabel(tabIndex = 3, selected = pagerState.currentPage == 3)
            }
        }
    }
}

@Composable
private fun BottomBarLabel(tabIndex: Int, selected: Boolean) {
    val color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary
    val icon = when (tabIndex) {
        0 -> MiuixIcons.VerticalSplit
        1 -> MiuixIcons.Contacts
        2 -> MiuixIcons.Favorites
        else -> MiuixIcons.Settings
    }
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = color,
        modifier = Modifier.size(20.dp),
    )
    Text(
        text = PAGE_TITLES[tabIndex],
        fontSize = 11.sp,
        color = color,
    )
}