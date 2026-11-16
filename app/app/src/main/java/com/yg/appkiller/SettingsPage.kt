package com.yg.appkiller

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SettingsPage() {
    val scope = rememberCoroutineScope()
    var enable by remember { mutableStateOf(true) }
    var timeoutMin by remember { mutableStateOf("30") }
    var intervalMs by remember { mutableStateOf("2000") }
    var killMode by remember { mutableStateOf("sigkill") }
    var killScope by remember { mutableStateOf("pkg") }
    var dryRun by remember { mutableStateOf(false) }
    var debugLog by remember { mutableStateOf(false) }
    var sysDefault by remember { mutableStateOf("protect") }
    var userDefault by remember { mutableStateOf("manage") }
    var maxPerRound by remember { mutableStateOf("3") }
    var cooldown by remember { mutableStateOf("180") }
    var notice by remember { mutableStateOf("") }
    var log by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val c = withContext(Dispatchers.IO) { ModuleRepo.configMap() }
        enable = c["ENABLE"] != "0"
        timeoutMin = c["TIMEOUT_MIN"] ?: "30"
        intervalMs = c["INTERVAL_MS"] ?: "2000"
        killMode = c["KILL_MODE"] ?: "sigkill"
        killScope = c["KILL_SCOPE"] ?: "pkg"
        dryRun = c["DRY_RUN"] == "1"
        debugLog = c["DEBUG_LOG"] == "1"
        sysDefault = c["SYSTEM_DEFAULT"] ?: "protect"
        userDefault = c["USER_DEFAULT"] ?: "manage"
        maxPerRound = c["MAX_KILL_PER_ROUND"] ?: "3"
        cooldown = c["KILL_COOLDOWN_SEC"] ?: "180"
    }

    Column(Modifier.fillMaxSize()) {
        PageHeader("设置", "写入模块 config.conf 后热重载")
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PanelCard(Modifier.fillMaxWidth()) {
                Column {
                    InfoRow("启用清理", "关闭后只观测不动作") { Switch(checked = enable, onCheckedChange = { enable = it }) }
                    InfoRow("观察模式", "只写日志，不真正杀进程") { Switch(checked = dryRun, onCheckedChange = { dryRun = it }) }
                    InfoRow("调试日志", "每轮打印扫描耗时与判定") { Switch(checked = debugLog, onCheckedChange = { debugLog = it }) }
                }
            }
            SectionTitle("阈值")
            PanelCard(Modifier.fillMaxWidth()) {
                Column {
                    TextField(
                        value = timeoutMin,
                        onValueChange = { timeoutMin = it.filter { ch -> ch.isDigit() || ch == '.' } },
                        label = "冻结多少分钟后清理（可填小数，0.5 = 30 秒）",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    TextField(
                        value = intervalMs,
                        onValueChange = { intervalMs = it.filter { ch -> ch.isDigit() } },
                        label = "扫描间隔（毫秒，最小 200）",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            SectionTitle("清理方式")
            PanelCard(Modifier.fillMaxWidth()) {
                Column {
                    SegmentedRow(
                        options = listOf("sigkill" to "SIGKILL", "am_kill" to "am kill", "force_stop" to "强停"),
                        selected = killMode,
                        onSelect = { killMode = it },
                    )
                    Text(
                        "SIGKILL 最快；am kill 更温和；强停会阻止应用被系统重新拉起",
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(8.dp))
                    SegmentedRow(
                        options = listOf("pkg" to "整个应用", "frozen" to "仅冻结进程"),
                        selected = killScope,
                        onSelect = { killScope = it },
                    )
                }
            }
            SectionTitle("默认策略（Cirno 式黑白名单）")
            PanelCard(Modifier.fillMaxWidth()) {
                Column {
                    Text("系统应用", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurface)
                    SegmentedRow(
                        options = listOf("protect" to "默认豁免", "manage" to "默认清理"),
                        selected = sysDefault,
                        onSelect = { sysDefault = it },
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("普通应用", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurface)
                    SegmentedRow(
                        options = listOf("manage" to "默认清理", "protect" to "默认豁免"),
                        selected = userDefault,
                        onSelect = { userDefault = it },
                    )
                    Text(
                        "黑名单永远优先：命中黑名单的系统应用也会被清理；白名单次之；最后才看默认策略。",
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            SectionTitle("节流")
            PanelCard(Modifier.fillMaxWidth()) {
                Column {
                    TextField(
                        value = maxPerRound,
                        onValueChange = { maxPerRound = it.filter { ch -> ch.isDigit() } },
                        label = "每轮最多清理几个应用",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    TextField(
                        value = cooldown,
                        onValueChange = { cooldown = it.filter { ch -> ch.isDigit() } },
                        label = "同一应用两次清理的最短间隔（秒）",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                ModuleRepo.setConfigs(
                                    linkedMapOf(
                                        "ENABLE" to (if (enable) "1" else "0"),
                                        "TIMEOUT_MIN" to timeoutMin.ifBlank { "30" },
                                        "INTERVAL_MS" to intervalMs.ifBlank { "2000" },
                                        "KILL_MODE" to killMode,
                                        "KILL_SCOPE" to killScope,
                                        "DRY_RUN" to (if (dryRun) "1" else "0"),
                                        "DEBUG_LOG" to (if (debugLog) "1" else "0"),
                                        "SYSTEM_DEFAULT" to sysDefault,
                                        "USER_DEFAULT" to userDefault,
                                        "MAX_KILL_PER_ROUND" to maxPerRound.ifBlank { "3" },
                                        "KILL_COOLDOWN_SEC" to cooldown.ifBlank { "180" },
                                    ),
                                )
                                ModuleRepo.reload()
                            }
                            notice = "已保存到 config.conf，守护进程会热重载"
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("保存设置", fontSize = 14.sp)
                }
                Button(
                    onClick = {
                        scope.launch {
                            log = withContext(Dispatchers.IO) { ModuleRepo.logTail(80) }
                            notice = "已读取最后 80 行日志"
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("看日志", fontSize = 14.sp)
                }
            }
            if (notice.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(notice, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            if (log.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                PanelCard(Modifier.fillMaxWidth()) {
                    Column {
                        Text("daemon.log（尾部）", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MiuixTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(4.dp))
                        Text(log, fontSize = 10.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }
    }
}
