@file:OptIn(ExperimentalScrollBarApi::class)
package com.yg.appkiller

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yg.appkiller.ui.app.LocalAppState
import com.yg.appkiller.ui.app.LocalIsWideScreen
import com.yg.appkiller.ui.app.LocalUpdateAppState
import com.yg.appkiller.ui.utils.AdaptiveTopAppBar
import com.yg.appkiller.ui.utils.BackgroundManager
import com.yg.appkiller.ui.utils.BlurredBar
import com.yg.appkiller.ui.utils.CirnoCard
import com.yg.appkiller.ui.utils.LocalImageBackdrop
import com.yg.appkiller.ui.utils.pageContentPadding
import com.yg.appkiller.ui.utils.pageScrollModifiers
import com.yg.appkiller.ui.utils.UiPrefs
import com.yg.appkiller.ui.utils.rememberBlurBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

private fun fmtNum(v: Float): String =
    if (v == v.toLong().toFloat()) v.toLong().toString() else String.format(java.util.Locale.US, "%.1f", v)

/**
 * 设置页：与 Cirno SettingsPage 同构 —— Scaffold + BlurredBar 顶栏 +
 * SmallTitle 分组 + CirnoCard 内 OverlayDropdownPreference / SwitchPreference / SliderPreference。
 */
@Composable
fun SettingsPage() {
    val isWideScreen = LocalIsWideScreen.current
    val imageBackdrop = LocalImageBackdrop.current
    val backdrop = if (imageBackdrop != null) null else rememberBlurBackdrop()
    val blurActive = imageBackdrop != null || backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface
    val scrollBehavior = MiuixScrollBehavior()

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            BlurredBar(backdrop, blurActive, scrollBehavior) {
                AdaptiveTopAppBar(
                    title = "设置",
                    isWideScreen = isWideScreen,
                    scrollBehavior = scrollBehavior,
                    color = barColor,
                )
            }
        },
    ) { innerPadding ->
        SettingsContent(
            padding = innerPadding,
            topAppBarScrollBehavior = scrollBehavior,
            backdrop = backdrop,
        )
    }
}

@Composable
private fun SettingsContent(
    padding: androidx.compose.foundation.layout.PaddingValues,
    topAppBarScrollBehavior: top.yukonga.miuix.kmp.basic.ScrollBehavior,
    backdrop: top.yukonga.miuix.kmp.blur.LayerBackdrop?,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val isWideScreen = LocalIsWideScreen.current
    val appState = LocalAppState.current
    val updateAppState = LocalUpdateAppState.current
    val lazyListState = rememberLazyListState()
    val contentPadding = pageContentPadding(padding, padding, isWideScreen)

    var enable by remember { mutableStateOf(true) }
    var timeoutVal by remember { mutableFloatStateOf(60f) }
    var intervalVal by remember { mutableFloatStateOf(2000f) }
    var killModeIndex by remember { mutableStateOf(0) }
    var killScopeIndex by remember { mutableStateOf(0) }
    var dryRun by remember { mutableStateOf(false) }
    var debugLog by remember { mutableStateOf(false) }
    var sysDefaultIndex by remember { mutableStateOf(0) }
    var userDefaultIndex by remember { mutableStateOf(1) }
    var maxPerRound by remember { mutableFloatStateOf(3f) }
    var cooldown by remember { mutableFloatStateOf(180f) }
    var loaded by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf("") }
    var backupMsg by remember { mutableStateOf("") }

    val killModeItems = listOf("SIGKILL", "am kill", "强制停止")
    val killModeValues = listOf("sigkill", "am_kill", "force_stop")
    val killScopeItems = listOf("整个应用", "仅冻结进程")
    val killScopeValues = listOf("pkg", "frozen")
    val defaultItems = listOf("默认豁免", "默认清理")
    val defaultValues = listOf("protect", "manage")

    LaunchedEffect(Unit) {
        val c = withContext(Dispatchers.IO) { ModuleRepo.configMap() }
        enable = c["ENABLE"] != "0"
        timeoutVal = (c["TIMEOUT_MIN"]?.toFloatOrNull() ?: 60f).coerceIn(0.5f, 240f)
        intervalVal = (c["INTERVAL_MS"]?.toFloatOrNull() ?: 2000f).coerceIn(500f, 10000f)
        killModeIndex = killModeValues.indexOf(c["KILL_MODE"] ?: "sigkill").coerceAtLeast(0)
        killScopeIndex = killScopeValues.indexOf(c["KILL_SCOPE"] ?: "pkg").coerceAtLeast(0)
        dryRun = c["DRY_RUN"] == "1"
        debugLog = c["DEBUG_LOG"] == "1"
        sysDefaultIndex = defaultValues.indexOf(c["SYSTEM_DEFAULT"] ?: "protect").coerceAtLeast(0)
        userDefaultIndex = defaultValues.indexOf(c["USER_DEFAULT"] ?: "manage").coerceAtLeast(0)
        maxPerRound = (c["MAX_KILL_PER_ROUND"]?.toFloatOrNull() ?: 3f).coerceIn(1f, 10f)
        cooldown = (c["KILL_COOLDOWN_SEC"]?.toFloatOrNull() ?: 180f).coerceIn(30f, 900f)
        loaded = true
    }

    val backgroundLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) { BackgroundManager.set(ctx, uri) }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            backupMsg = withContext(Dispatchers.IO) {
                val map = ModuleRepo.exportAll()
                if (map.isEmpty()) return@withContext "导出失败：读不到模块文件"
                runCatching {
                    ctx.contentResolver.openOutputStream(uri)?.use { os ->
                        os.write(ModuleRepo.toBackupText(map).toByteArray(Charsets.UTF_8))
                    }
                    "已导出 " + map.size + " 个文件到所选位置"
                }.getOrElse { e -> "导出失败：" + (e.message ?: e.toString()) }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            backupMsg = withContext(Dispatchers.IO) {
                val text = runCatching {
                    ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                }.getOrNull()
                if (text.isNullOrBlank()) return@withContext "导入失败：读不到文件内容"
                if (!text.startsWith("# AppKiller 备份")) return@withContext "导入失败：不是 AppKiller 备份文件"
                val n = ModuleRepo.importAll(text)
                if (n > 0) "已还原 " + n + " 个文件并重载" else "导入失败：没有可还原的文件"
            }
        }
    }

    val cirnoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            backupMsg = withContext(Dispatchers.IO) {
                val text = runCatching {
                    ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                }.getOrNull()
                if (text.isNullOrBlank()) return@withContext "导入失败：读不到文件内容"
                val (w, b) = ModuleRepo.importCirnoLists(text)
                if (w == 0 && b == 0) "没有新增条目（文件不是 Cirno 的 ApplicationSettings.json，或名单已包含）"
                else "Cirno 名单导入完成：白名单 +" + w + "、黑名单 +" + b
            }
        }
    }

    fun save() {
        scope.launch {
            withContext(Dispatchers.IO) {
                ModuleRepo.setConfigs(
                    linkedMapOf(
                        "ENABLE" to (if (enable) "1" else "0"),
                        "TIMEOUT_MIN" to fmtNum(timeoutVal),
                        "INTERVAL_MS" to intervalVal.toLong().toString(),
                        "KILL_MODE" to killModeValues[killModeIndex],
                        "KILL_SCOPE" to killScopeValues[killScopeIndex],
                        "DRY_RUN" to (if (dryRun) "1" else "0"),
                        "DEBUG_LOG" to (if (debugLog) "1" else "0"),
                        "SYSTEM_DEFAULT" to defaultValues[sysDefaultIndex],
                        "USER_DEFAULT" to defaultValues[userDefaultIndex],
                        "MAX_KILL_PER_ROUND" to maxPerRound.toLong().toString(),
                        "KILL_COOLDOWN_SEC" to cooldown.toLong().toString(),
                    ),
                )
                ModuleRepo.reload()
            }
        }
    }

    Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
        LazyColumn(
            state = lazyListState,
            modifier = Modifier.pageScrollModifiers(appState.enableScrollEndHaptic, true, topAppBarScrollBehavior),
            contentPadding = contentPadding,
        ) {
            item {
                SmallTitle(text = "冻结设置")
                CirnoCard(modifier = Modifier.padding(12.dp)) {
                    SwitchPreference(
                        title = "启用清理",
                        summary = "关闭后只观测、不杀任何进程",
                        checked = enable,
                        onCheckedChange = { enable = it; save() },
                    )
                    OverlayDropdownPreference(
                        title = "清理方式",
                        items = killModeItems,
                        selectedIndex = killModeIndex,
                        onSelectedIndexChange = { killModeIndex = it; save() },
                    )
                    OverlayDropdownPreference(
                        title = "清理范围",
                        items = killScopeItems,
                        selectedIndex = killScopeIndex,
                        onSelectedIndexChange = { killScopeIndex = it; save() },
                    )
                    SliderPreference(
                        title = "冻结延迟",
                        value = timeoutVal,
                        onValueChange = { timeoutVal = it },
                        onValueChangeFinished = { save() },
                        valueRange = 0.5f..240f,
                        steps = 478,
                        valueText = fmtNum(timeoutVal) + " 分",
                    )
                    SliderPreference(
                        title = "扫描间隔",
                        value = intervalVal,
                        onValueChange = { intervalVal = it },
                        onValueChangeFinished = { save() },
                        valueRange = 500f..10000f,
                        steps = 94,
                        valueText = intervalVal.toLong().toString() + " ms",
                    )
                    SwitchPreference(
                        title = "观察模式",
                        summary = "只写日志，不真正杀进程",
                        checked = dryRun,
                        onCheckedChange = { dryRun = it; save() },
                    )
                }
            }

            item {
                SmallTitle(text = "名单策略")
                CirnoCard(modifier = Modifier.padding(12.dp)) {
                    OverlayDropdownPreference(
                        title = "普通应用默认",
                        items = defaultItems,
                        selectedIndex = userDefaultIndex,
                        onSelectedIndexChange = { userDefaultIndex = it; save() },
                    )
                    OverlayDropdownPreference(
                        title = "系统应用默认",
                        items = defaultItems,
                        selectedIndex = sysDefaultIndex,
                        onSelectedIndexChange = { sysDefaultIndex = it; save() },
                    )
                    ArrowPreference(
                        title = "优先级说明",
                        summary = "黑名单 > 白名单 > 系统应用默认 > 普通应用默认",
                    )
                }
            }

            item {
                SmallTitle(text = "节流")
                CirnoCard(modifier = Modifier.padding(12.dp)) {
                    SliderPreference(
                        title = "每轮上限",
                        value = maxPerRound,
                        onValueChange = { maxPerRound = it },
                        onValueChangeFinished = { save() },
                        valueRange = 1f..10f,
                        steps = 8,
                        valueText = maxPerRound.toLong().toString() + " 个",
                    )
                    SliderPreference(
                        title = "清理冷却",
                        value = cooldown,
                        onValueChange = { cooldown = it },
                        onValueChangeFinished = { save() },
                        valueRange = 30f..900f,
                        steps = 86,
                        valueText = cooldown.toLong().toString() + " s",
                    )
                }
            }

            item {
                SmallTitle(text = "界面设置")
                CirnoCard(modifier = Modifier.padding(12.dp)) {
                    OverlayDropdownPreference(
                        title = "主题模式",
                        items = listOf("跟随系统", "浅色", "深色", "莫奈跟随系统", "莫奈浅色", "莫奈深色"),
                        selectedIndex = appState.colorMode.coerceIn(0, 5),
                        onSelectedIndexChange = { updateAppState { s -> s.copy(colorMode = it) } },
                    )
                    SwitchPreference(
                        title = "液态玻璃导航栏",
                        summary = "关闭后回退为普通胶囊底栏",
                        checked = appState.liquidGlass,
                        onCheckedChange = { on ->
                            UiPrefs.setLiquidGlass(ctx, on)
                            updateAppState { s -> s.copy(liquidGlass = on) }
                        },
                    )
                    SwitchPreference(
                        title = "模糊效果",
                        summary = "卡片与顶栏毛玻璃",
                        checked = appState.blur,
                        onCheckedChange = { on -> updateAppState { s -> s.copy(blur = on) } },
                    )
                    ArrowPreference(
                        title = "自定义背景图",
                        summary = if (BackgroundManager.currentUri == null) "未设置" else "已设置",
                        onClick = { backgroundLauncher.launch(arrayOf("image/*")) },
                    )
                    if (BackgroundManager.currentUri != null) {
                        ArrowPreference(
                            title = "移除自定义背景图",
                            onClick = { BackgroundManager.remove(ctx) },
                        )
                    }
                    SwitchPreference(
                        title = "末尾触感反馈",
                        summary = "滚动到边界时轻微震动",
                        checked = appState.enableScrollEndHaptic,
                        onCheckedChange = { on -> updateAppState { s -> s.copy(enableScrollEndHaptic = on) } },
                    )
                }
            }

            item {
                SmallTitle(text = "日志")
                CirnoCard(modifier = Modifier.padding(12.dp)) {
                    SwitchPreference(
                        title = "调试日志",
                        summary = "每轮打印扫描耗时与判定结果",
                        checked = debugLog,
                        onCheckedChange = { debugLog = it; save() },
                    )
                    ArrowPreference(
                        title = "查看守护日志",
                        summary = "读取 daemon.log 最后 60 行",
                        onClick = {
                            showLog = true
                            scope.launch {
                                log = withContext(Dispatchers.IO) { ModuleRepo.logTail(60) }
                            }
                        },
                    )
                }
            }

            item {
                SmallTitle(text = "配置备份")
                CirnoCard(modifier = Modifier.padding(12.dp)) {
                    ArrowPreference(
                        title = "导出配置",
                        summary = "配置与全部名单打包成一个文本文件",
                        onClick = { exportLauncher.launch("appkiller-backup.txt") },
                    )
                    ArrowPreference(
                        title = "导入配置",
                        summary = "从备份文件还原配置与名单",
                        onClick = { importLauncher.launch(arrayOf("text/plain", "*/*")) },
                    )
                    ArrowPreference(
                        title = "导入 Cirno 白名单",
                        summary = "读取 Cirno 的 ApplicationSettings.json，把 whiteApps / blackApps 并入本模块名单",
                        onClick = { cirnoLauncher.launch(arrayOf("application/json", "*/*")) },
                    )
                    if (backupMsg.isNotEmpty()) {
                        Text(
                            text = backupMsg,
                            fontSize = 12.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 8.dp, end = 20.dp),
                        )
                    }
                }
            }

            item {
                if (!loaded) {
                    Text(
                        text = "读取配置中…",
                        fontSize = 12.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(start = 20.dp),
                    )
                }
                Spacer(modifier = Modifier.height(96.dp))
            }
        }
    }

    OverlayDialog(
        title = "daemon.log（尾部）",
        show = showLog,
        onDismissRequest = { showLog = false },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = log.ifEmpty { "读取中…" },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp),
                fontSize = 11.sp,
                color = colorScheme.onSurfaceVariantSummary,
            )
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                text = "确定",
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = { showLog = false },
            )
        }
    }
}
