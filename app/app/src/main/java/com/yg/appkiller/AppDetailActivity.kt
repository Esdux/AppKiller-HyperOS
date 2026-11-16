package com.yg.appkiller
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.CompositionLocalProvider
import com.yg.appkiller.ui.app.LocalUpdateAppState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yg.appkiller.ui.app.AppState
import com.yg.appkiller.ui.app.AppTheme
import com.yg.appkiller.ui.app.LocalAppState
import com.yg.appkiller.ui.utils.AdaptiveTopAppBar
import com.yg.appkiller.ui.utils.BlurredBar
import com.yg.appkiller.ui.utils.CirnoCard
import com.yg.appkiller.ui.utils.MiuixBackground
import com.yg.appkiller.ui.utils.UiPrefs
import com.yg.appkiller.ui.utils.pageScrollModifiers
import com.yg.appkiller.ui.utils.rememberBlurBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

class AppDetailActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = intent.getStringExtra("pkg") ?: ""
        val label = intent.getStringExtra("label") ?: pkg
        setContent {
            val appState = remember { AppState(liquidGlass = UiPrefs.liquidGlass(this)) }
            AppTheme(
                uiStyle = appState.uiStyle,
                colorMode = appState.colorMode,
                paletteStyle = appState.themePaletteStyle,
                colorSpec = appState.themeColorSpec,
            ) {
                CompositionLocalProvider(
                    LocalAppState provides appState,
                    LocalUpdateAppState provides { _ -> },
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
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()

    var inWhite by remember { mutableStateOf(false) }
    var inBlack by remember { mutableStateOf(false) }
    var perAppMode by remember { mutableStateOf("-") }
    var perAppScope by remember { mutableStateOf("-") }
    var procs by remember { mutableStateOf<List<Ctrl.ProcEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var msg by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
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
        perAppMode = pa.first
        perAppScope = pa.second
        procs = d[3] as List<Ctrl.ProcEntry>
        loading = false
    }

    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
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
        popupHost = {},
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .pageScrollModifiers(true, true, scrollBehavior),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 12.dp,
                start = 12.dp,
                end = 12.dp,
                bottom = 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            /* 应用信息 */
            item {
                CirnoCard(insideMargin = PaddingValues(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(pkg = pkg, label = label, size = 48.dp)
                        Spacer(Modifier.size(12.dp))
                        Column {
                            Text(text = label, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Text(text = pkg, fontSize = 13.sp, color = colorScheme.onSurfaceVariantSummary)
                        }
                    }
                }
            }

            /* 名单设置 */
            item {
                SmallTitle(text = "名单设置")
            }
            item {
                CirnoCard(insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "白名单（豁免清理）", fontSize = 15.sp)
                            Text(text = "加入后该应用永不被清理", fontSize = 12.sp, color = colorScheme.onSurfaceVariantSummary)
                        }
                        Switch(
                            checked = inWhite,
                            onCheckedChange = { v ->
                                inWhite = v
                                if (v) inBlack = false
                                scope.launch {
                                    withContext(Dispatchers.IO) { saveLists(pkg, inWhite, inBlack) }
                                    msg = if (v) "已加入白名单" else "已移出白名单"
                                }
                            },
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "黑名单（强制清理）", fontSize = 15.sp)
                            Text(text = "即使系统应用也按超时清理", fontSize = 12.sp, color = colorScheme.onSurfaceVariantSummary)
                        }
                        Switch(
                            checked = inBlack,
                            onCheckedChange = { v ->
                                inBlack = v
                                if (v) inWhite = false
                                scope.launch {
                                    withContext(Dispatchers.IO) { saveLists(pkg, inWhite, inBlack) }
                                    msg = if (v) "已加入黑名单" else "已移出黑名单"
                                }
                            },
                        )
                    }
                }
            }

            /* 清理方式 */
            item {
                SmallTitle(text = "清理方式")
            }
            item {
                CirnoCard(insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                    val modes = listOf("sigkill", "am_kill", "force_stop")
                    val modeLabels = listOf("SIGKILL（直接杀进程）", "am kill（系统回收）", "force-stop（强制停止）")
                    modes.forEachIndexed { i, m ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = modeLabels[i],
                                fontSize = 14.sp,
                                modifier = Modifier.weight(1f),
                                color = if (perAppMode == m) colorScheme.primary else colorScheme.onSurface,
                            )
                            Switch(
                                checked = perAppMode == m,
                                onCheckedChange = { v ->
                                    if (v) {
                                        perAppMode = m
                                        scope.launch {
                                            withContext(Dispatchers.IO) { Ctrl.setPerApp(pkg, m, perAppScope) }
                                            msg = "清理方式: " + m
                                        }
                                    } else if (perAppMode == m) {
                                        perAppMode = "-"
                                        scope.launch {
                                            withContext(Dispatchers.IO) { Ctrl.setPerApp(pkg, "-", perAppScope) }
                                            msg = "清理方式: 跟随全局"
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }

            /* 清理范围 */
            item {
                SmallTitle(text = "清理范围")
            }
            item {
                CirnoCard(insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                    val scopes = listOf("pkg", "frozen")
                    val scopeLabels = listOf("整包（所有后台进程）", "仅冻结进程")
                    scopes.forEachIndexed { i, sc ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = scopeLabels[i],
                                fontSize = 14.sp,
                                modifier = Modifier.weight(1f),
                                color = if (perAppScope == sc) colorScheme.primary else colorScheme.onSurface,
                            )
                            Switch(
                                checked = perAppScope == sc,
                                onCheckedChange = { v ->
                                    if (v) {
                                        perAppScope = sc
                                        scope.launch {
                                            withContext(Dispatchers.IO) { Ctrl.setPerApp(pkg, perAppMode, sc) }
                                            msg = "清理范围: " + sc
                                        }
                                    } else if (perAppScope == sc) {
                                        perAppScope = "-"
                                        scope.launch {
                                            withContext(Dispatchers.IO) { Ctrl.setPerApp(pkg, perAppMode, "-") }
                                            msg = "清理范围: 跟随全局"
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }

            /* 进程列表 */
            item {
                SmallTitle(text = "进程列表")
            }
            if (procs.isEmpty()) {
                item {
                    CirnoCard(insideMargin = PaddingValues(16.dp)) {
                        Text(
                            text = if (loading) "加载中…" else "当前无运行中的进程",
                            fontSize = 14.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            } else {
                items(procs, key = { it.pid }) { proc ->
                    CirnoCard(insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = proc.cmdline, fontSize = 14.sp)
                                Text(
                                    text = "pid " + proc.pid + " · adj " + proc.adj +
                                        (if (proc.frozen) " · 已冻结" else ""),
                                    fontSize = 12.sp,
                                    color = if (proc.frozen) Color(0xFFFF8C00) else colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            /* 每进程模式：默认 / skip */
                            val isSkip = proc.mode == "skip"
                            TextButton(
                                text = if (isSkip) "跳过" else "默认",
                                onClick = {
                                    scope.launch {
                                        val newMode = if (isSkip) "-" else "skip"
                                        withContext(Dispatchers.IO) { Ctrl.setPerProc(proc.cmdline, newMode) }
                                        procs = procs.map {
                                            if (it.pid == proc.pid) it.copy(mode = newMode) else it
                                        }
                                        msg = proc.cmdline + ": " + (if (isSkip) "恢复默认" else "已跳过")
                                    }
                                },
                            )
                        }
                    }
                }
            }

            /* 操作 */
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(
                        text = "立即清理",
                        onClick = {
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { Ctrl.killPkg(pkg) }
                                msg = "清理结果: " + r.trim()
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = "刷新进程",
                        onClick = {
                            scope.launch {
                                procs = withContext(Dispatchers.IO) { Ctrl.procs(pkg) }
                                msg = "已刷新"
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            /* 状态提示 */
            if (msg.isNotEmpty()) {
                item {
                    Text(
                        text = msg,
                        fontSize = 13.sp,
                        color = colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
    }
}

/** 保存黑白名单变更 */
private fun saveLists(pkg: String, inWhite: Boolean, inBlack: Boolean) {
    val w = ModuleRepo.list("protect.list").toMutableSet()
    val b = ModuleRepo.list("blacklist.list").toMutableSet()
    if (inWhite) { w.add(pkg); b.remove(pkg) } else w.remove(pkg)
    if (inBlack) { b.add(pkg); w.remove(pkg) } else b.remove(pkg)
    ModuleRepo.writeList("protect.list", "# 保护名单", w)
    ModuleRepo.writeList("blacklist.list", "# 黑名单", b)
    ModuleRepo.reload()
}
