package com.yg.appkiller.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yg.appkiller.ui.app.LocalAppState
import com.yg.appkiller.ui.utils.AdaptiveTopAppBar
import com.yg.appkiller.ui.utils.pageScrollModifiers
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior

/**
 * Cirno 风格页面壳：顶栏（随滚动收起）+ 可滚动内容 + 给浮动底栏预留的底部留白。
 * 内容一律用 CirnoCard 组织，保持与 Cirno 一致的分块观感。
 */
@Composable
fun CirnoPage(
    title: String,
    subtitle: String = "",
    actions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val appState = LocalAppState.current
    val scrollBehavior = MiuixScrollBehavior()

    Column(
        Modifier
            .fillMaxSize()
            .pageScrollModifiers(
                enableScrollEndHaptic = appState.enableScrollEndHaptic,
                showTopAppBar = true,
                topAppBarScrollBehavior = scrollBehavior,
            )
    ) {
        AdaptiveTopAppBar(
            title = title,
            subtitle = subtitle,
            isWideScreen = false,
            scrollBehavior = scrollBehavior,
            actions = actions,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 150.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            content()
        }
    }
}
