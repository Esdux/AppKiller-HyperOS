package com.yg.appkiller.ui.app
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yg.appkiller.AppListPage
import com.yg.appkiller.AppRepo
import com.yg.appkiller.DialogHost
import com.yg.appkiller.ModuleRepo
import com.yg.appkiller.RunningPage
import com.yg.appkiller.SettingsPage
import com.yg.appkiller.StatusPage
import com.yg.appkiller.ui.custom.liquid.IosLiquidGlassNavigationBar
import com.yg.appkiller.ui.utils.rememberBlurBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.icon.extended.Favorites
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.VerticalSplit
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme

const val PAGE_COUNT = 4

private val PAGE_TITLES = listOf("主页", "应用列表", "运行列表", "设置")

private val PAGE_ICONS: List<ImageVector> = listOf(
    MiuixIcons.VerticalSplit,
    MiuixIcons.Contacts,
    MiuixIcons.Favorites,
    MiuixIcons.Settings,
)

/**
 * 应用外壳：横向分页 + VCut Lower 同源的悬浮导航栏。
 *
 * 三个关键设计：
 * 1. beyondViewportPageCount = PAGE_COUNT - 1：四个页面全部保活，
 *    切页不再重新 compose / 重跑 IO，这是切页卡顿与「缓存不生效」的根因修复。
 * 2. targetPage 与 pagerState.currentPage 分离：点击导航栏先更新 targetPage，
 *    翻页动画中途 currentPage 经过中间页不会把导航栏选中态拉回去（修「回跳」）。
 * 3. 对话框渲染在导航栏之后（z 序更高），不会被底栏盖住。
 */
@Composable
fun AppContent() {
    val appState = LocalAppState.current
    val pagerState = rememberPagerState(pageCount = { PAGE_COUNT })
    val scope = rememberCoroutineScope()

    // 导航栏选中态直接跟 pagerState.targetPage：点击或手势发起翻页时立即更新，
    // 动画经过的中间页不会抢选中态（修「点一次回跳、要点两次」）。
    val selectPage: (Int) -> Unit = { index ->
        scope.launch { pagerState.animateScrollToPage(index) }
    }

    // backdrop 常建：模糊开关只决定页面内容是否接入，液态玻璃开关只决定导航栏用不用。
    val backdrop = rememberBlurBackdrop()
    val navItems = remember { List(PAGE_COUNT) { i -> NavigationItem(PAGE_TITLES[i], PAGE_ICONS[i]) } }

    // miuix 的 OverlayDialog/Popup 依赖 Scaffold 提供的 LocalDialogStates +
    // MiuixPopupHost 弹层宿主；裸 Box 里对话框不渲染（导入名单候选框消失的根因）。
    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
    Box(Modifier.fillMaxSize().padding(innerPadding)) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = appState.enablePageUserScroll,
            // 只保活左右各一页：四页全保活会让三个页面的轮询同时跑，白白占 CPU
            beyondViewportPageCount = 1,
            modifier = Modifier
                .fillMaxSize()
                .then(if (appState.blur && backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier),
            key = { it },
        ) { page ->
            when (page) {
                0 -> StatusPage(callback = selectPage)
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
            selectedIndex = pagerState.targetPage,
            onItemClick = selectPage,
            backdrop = if (appState.liquidGlass) backdrop else null,
            isBlurActive = appState.liquidGlass,
        )

        CirnoImportDialog()
        DaemonLogDialog()
    }
    }
}

/** Cirno 名单导入候选对话框：勾选条目 + 合并/覆盖。 */
@Composable
private fun CirnoImportDialog() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var resultMsg by remember { mutableStateOf("") }
    // labelMap 可能触发一次 su + 逐包取标签，绝不能放在 composition 里同步跑
    var labels by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(Unit) {
        labels = withContext(Dispatchers.IO) { AppRepo.labelMap(ctx) }
    }

    OverlayDialog(
        title = "导入 Cirno 名单",
        show = DialogHost.cirnoShow,
        onDismissRequest = { DialogHost.cirnoShow = false },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (DialogHost.cirnoLoading) {
                Text(
                    text = "正在读取 Cirno 配置…",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        modifier = Modifier.weight(1f),
                        text = "白名单 " + DialogHost.cirnoWhite.size,
                        colors = if (DialogHost.cirnoTab == 0) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(),
                        onClick = { DialogHost.cirnoTab = 0 },
                    )
                    TextButton(
                        modifier = Modifier.weight(1f),
                        text = "黑名单 " + DialogHost.cirnoBlack.size,
                        colors = if (DialogHost.cirnoTab == 1) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(),
                        onClick = { DialogHost.cirnoTab = 1 },
                    )
                }
                val items = if (DialogHost.cirnoTab == 0) DialogHost.cirnoWhite else DialogHost.cirnoBlack
                val sel = if (DialogHost.cirnoTab == 0) DialogHost.cirnoSelW else DialogHost.cirnoSelB
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp),
                ) {
                    items(items.size) { i ->
                        val pkg = items[i]
                        val on = pkg in sel
                        val toggle: (Boolean) -> Unit = { v ->
                            if (DialogHost.cirnoTab == 0) {
                                DialogHost.cirnoSelW = if (v) sel + pkg else sel - pkg
                            } else {
                                DialogHost.cirnoSelB = if (v) sel + pkg else sel - pkg
                            }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { toggle(!on) }
                                .padding(horizontal = 4.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = labels[pkg] ?: pkg,
                                    fontSize = 13.sp,
                                    color = if (on) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                                Text(
                                    text = pkg,
                                    fontSize = 10.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            Switch(checked = on, onCheckedChange = toggle)
                        }
                    }
                }
                SwitchPreference(
                    title = "与现有名单合并",
                    summary = if (DialogHost.cirnoMerge) "保留现有条目，追加勾选项" else "用勾选项整体覆盖现有名单",
                    checked = DialogHost.cirnoMerge,
                    onCheckedChange = { DialogHost.cirnoMerge = it },
                )
                if (resultMsg.isNotEmpty()) {
                    Text(
                        text = resultMsg,
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        modifier = Modifier.weight(1f),
                        text = "取消",
                        onClick = { DialogHost.cirnoShow = false },
                    )
                    TextButton(
                        modifier = Modifier.weight(1f),
                        text = "导入 " + (DialogHost.cirnoSelW.size + DialogHost.cirnoSelB.size) + " 项",
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        onClick = {
                            val merge = DialogHost.cirnoMerge
                            scope.launch {
                                val msg = withContext(Dispatchers.IO) {
                                    val (w, b) = ModuleRepo.importCirnoSelection(
                                        DialogHost.cirnoSelW.toList(),
                                        DialogHost.cirnoSelB.toList(),
                                        merge,
                                    )
                                    if (w == 0 && b == 0) "导入失败：没有可写入的条目"
                                    else "已" + (if (merge) "合并" else "覆盖") + "导入：白名单 " + w + " 条、黑名单 " + b + " 条"
                                }
                                resultMsg = msg
                                if (!msg.startsWith("导入失败")) DialogHost.cirnoShow = false
                            }
                        },
                    )
                }
            }
        }
    }
}

/** 守护日志对话框。 */
@Composable
private fun DaemonLogDialog() {
    OverlayDialog(
        title = "daemon.log（尾部）",
        show = DialogHost.logShow,
        onDismissRequest = { DialogHost.logShow = false },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = DialogHost.logText.ifEmpty { "读取中…" },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .padding(horizontal = 4.dp),
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                text = "确定",
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = { DialogHost.logShow = false },
            )
        }
    }
}
