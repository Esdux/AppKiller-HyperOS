package com.yg.appkiller

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yg.appkiller.ui.app.AppState
import com.yg.appkiller.ui.app.AppTheme
import com.yg.appkiller.ui.app.LocalAppState
import com.yg.appkiller.ui.app.LocalUpdateAppState
import com.yg.appkiller.ui.utils.AdaptiveTopAppBar
import com.yg.appkiller.ui.utils.BlurredBar
import com.yg.appkiller.ui.utils.MiuixBackground
import com.yg.appkiller.ui.utils.UiPrefs
import com.yg.appkiller.ui.utils.pageContentPadding
import com.yg.appkiller.ui.utils.pageScrollModifiers
import com.yg.appkiller.ui.utils.rememberBlurBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

class AppDetailActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = intent.getStringExtra("pkg") ?: ""
        val label = intent.getStringExtra("label") ?: pkg
        setContent {
            var appState by remember { mutableStateOf(AppState(liquidGlass = UiPrefs.liquidGlass(this))) }
            AppTheme(
                uiStyle = appState.uiStyle,
                colorMode = appState.colorMode,
                paletteStyle = appState.themePaletteStyle,
                colorSpec = appState.themeColorSpec,
            ) {
                CompositionLocalProvider(
                    LocalAppState provides appState,
                    LocalUpdateAppState provides { transform -> appState = transform(appState) },
                ) {
                    MiuixBackground {
                        AppDetailScreen(pkg = pkg, label = label, onBack = { finish() })
                    }
                }
            }
        }
    }
}

@Composable
private fun AppDetailScreen(pkg: String, label: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()
    val appState = LocalAppState.current

    var inWhite by remember { mutableStateOf(false) }
    var inBlack by remember { mutableStateOf(false) }
    var modeIndex by remember { mutableStateOf(0) }
    var scopeIndex by remember { mutableStateOf(0) }
    var procs by remember { mutableStateOf<List<Ctrl.ProcEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var msg by remember { mutableStateOf("") }

    val modeItems = listOf("跟随全局", "SIGKILL", "am kill", "强制停止")
    val modeValues = listOf("-", "sigkill", "am_kill", "force_stop")
    val scopeItems = listOf("跟随全局", "整个应用", "仅冻结进程")
    val scopeValues = listOf("-", "pkg", "frozen")

    fun refresh() {
        scope.launch {
            val d = withContext(Dispatchers.IO) {
                val w = ModuleRepo.list("protect.list").toSet()
                val b = ModuleRepo.list("blacklist.list").toSet()
                val pa = Ctrl.getPerApp(pkg)
                val ps = Ctrl.procs(pkg)
                arrayOf<Any>(w, b, pa, ps)
            }
            inWhite = (d[0] as Set<String>).contains(pkg)
            inBlack = (d[1] as Set<String>).contains(pkg)
            val pa = d[2] as Pair<String, String>
            modeIndex = modeValues.indexOf(pa.first).coerceAtLeast(0)
            scopeIndex = scopeValues.indexOf(pa.second).coerceAtLeast(0)
            procs = d[3] as List<Ctrl.ProcEntry>
            loading = false
        }
    }
    LaunchedEffect(Unit) { refresh() }

    val imageBackdrop = com.yg.appkiller.ui.utils.LocalImageBackdrop.current
    val backdrop = if (imageBackdrop != null) null else rememberBlurBackdrop()
    val blurActive = imageBackdrop != null || backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Horizontal),
        topBar = {
            BlurredBar(backdrop, blurActive, scrollBehavior) {
                AdaptiveTopAppBar(
                    title = label,
                    isWideScreen = false,
                    scrollBehavior = scrollBehavior,
                    color = barColor,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = "返回",
                                tint = colorScheme.onBackground,
                            )
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        val lazyListState = rememberLazyListState()
        val contentPadding = pageContentPadding(innerPadding, innerPadding, false)
        val listPadding = PaddingValues(
            start = contentPadding.calculateStartPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
            top = contentPadding.calculateTopPadding(),
            end = contentPadding.calculateEndPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
            bottom = 48.dp,
        )
        androidx.compose.foundation.layout.Box(
            modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier
        ) {
            LazyColumn(
                state = lazyListState,
                modifier = Modifier
                    .fillMaxSize()
                    .pageScrollModifiers(appState.enableScrollEndHaptic, true, scrollBehavior),
                contentPadding = listPadding,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                overscrollEffect = null,
            ) {
                /* 应用信息 */
                item { GroupTitle("应用信息") }
                item {
                    GroupCard {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIcon(pkg = pkg, label = label, size = 48.dp)
                            Spacer(Modifier.size(14.dp))
                            Column {
                                Text(text = label, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    text = pkg,
                                    fontSize = 12.sp,
                                    color = colorScheme.onSurfaceVariantSummary,
                                )
                                Text(
                                    text = when {
                                        inBlack -> "黑名单 · 强制清理"
                                        inWhite -> "白名单 · 豁免清理"
                                        else -> "默认策略"
                                    },
                                    fontSize = 12.sp,
                                    color = if (inBlack) Color(0xFFD13636) else colorScheme.primary,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                    }
                }

                /* 名单 */
                item { GroupTitle("名单设置") }
                item {
                    GroupCard {
                        SwitchRow(
                            title = "白名单",
                            summary = "加入后该应用永不被清理",
                            checked = inWhite,
                            onCheckedChange = { v ->
                                scope.launch {
                                    withContext(Dispatchers.IO) { saveLists(pkg, v, false) }
                                    msg = if (v) "已加入白名单" else "已移出白名单"
                                    refresh()
                                }
                            },
                        )
                        SwitchRow(
                            title = "黑名单",
                            summary = "即使系统应用也按超时清理",
                            checked = inBlack,
                            onCheckedChange = { v ->
                                scope.launch {
                                    withContext(Dispatchers.IO) { saveLists(pkg, false, v) }
                                    msg = if (v) "已加入黑名单" else "已移出黑名单"
                                    refresh()
                                }
                            },
                        )
                    }
                }

                /* 清理配置 */
                item { GroupTitle("清理配置") }
                item {
                    GroupCard {
                        OverlayDropdownPreference(
                            items = modeItems,
                            selectedIndex = modeIndex,
                            title = "清理方式",
                            onSelectedIndexChange = { i ->
                                modeIndex = i
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        Ctrl.setPerApp(pkg, modeValues[i], scopeValues[scopeIndex])
                                    }
                                    msg = "清理方式: " + modeItems[i]
                                }
                            },
                        )
                        OverlayDropdownPreference(
                            items = scopeItems,
                            selectedIndex = scopeIndex,
                            title = "清理范围",
                            onSelectedIndexChange = { i ->
                                scopeIndex = i
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        Ctrl.setPerApp(pkg, modeValues[modeIndex], scopeValues[i])
                                    }
                                    msg = "清理范围: " + scopeItems[i]
                                }
                            },
                        )
                    }
                }

                /* 进程 */
                item { GroupTitle("进程列表") }
                item {
                    GroupCard {
                        if (loading) {
                            Text(
                                text = "加载中…",
                                fontSize = 14.sp,
                                color = colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(14.dp),
                            )
                        } else if (procs.isEmpty()) {
                            Text(
                                text = "当前无运行中的进程",
                                fontSize = 14.sp,
                                color = colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(14.dp),
                            )
                        } else {
                            procs.forEach { proc ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = proc.cmdline, fontSize = 14.sp)
                                        Text(
                                            text = "pid " + proc.pid + " · adj " + proc.adj +
                                                if (proc.frozen) " · 已冻结" else "",
                                            fontSize = 12.sp,
                                            color = if (proc.frozen) Color(0xFFFF8C00)
                                            else colorScheme.onSurfaceVariantSummary,
                                        )
                                    }
                                    TextButton(
                                        text = if (proc.mode == "skip") "跳过" else "默认",
                                        onClick = {
                                            scope.launch {
                                                val nm = if (proc.mode == "skip") "-" else "skip"
                                                withContext(Dispatchers.IO) {
                                                    Ctrl.setPerProc(proc.cmdline, nm)
                                                }
                                                msg = proc.cmdline + ": " +
                                                    if (nm == "skip") "已跳过" else "恢复默认"
                                                refresh()
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                /* 操作 */
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TextButton(
                            text = "立即清理",
                            modifier = Modifier.weight(1f),
                            onClick = {
                                scope.launch {
                                    val r = withContext(Dispatchers.IO) {
                                        Ctrl.killPkg(pkg, "force_stop")
                                    }
                                    msg = "清理结果: " + r.trim()
                                    refresh()
                                }
                            },
                        )
                        TextButton(
                            text = "刷新进程",
                            modifier = Modifier.weight(1f),
                            onClick = { refresh() },
                        )
                    }
                }

                if (msg.isNotEmpty()) {
                    item {
                        Text(
                            text = msg,
                            fontSize = 13.sp,
                            color = colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 保存黑白名单变更（互斥） */
private fun saveLists(pkg: String, wantWhite: Boolean, wantBlack: Boolean) {
    val w = ModuleRepo.list("protect.list").toMutableSet()
    val b = ModuleRepo.list("blacklist.list").toMutableSet()
    if (wantWhite) { w.add(pkg); b.remove(pkg) } else w.remove(pkg)
    if (wantBlack) { b.add(pkg); w.remove(pkg) } else b.remove(pkg)
    ModuleRepo.writeList("protect.list", "# 保护名单", w)
    ModuleRepo.writeList("blacklist.list", "# 黑名单", b)
    ModuleRepo.reload()
}
