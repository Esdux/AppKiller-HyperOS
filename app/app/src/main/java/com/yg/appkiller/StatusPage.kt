package com.yg.appkiller

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

data class StatusSnapshot(val uptime: Long = 0L, val apps: List<FrozenApp> = emptyList(), val pid: Int = -1)

/**
 * 主界面：只呈现守护进程自身的运行状态与统计（Cirno main 页语义）。
 * 计时明细在「运行列表」页，应用黑白名单在「应用列表」页。
 */
@Composable
fun StatusPage() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var snap by remember { mutableStateOf(StatusSnapshot()) }
    var running by remember { mutableStateOf<Boolean?>(null) }
    var cfg by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var tick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }

    LaunchedEffect(tick) {
        val data = withContext(Dispatchers.IO) {
            arrayOf<Any>(ModuleRepo.running(), StateRepo.snapshot(), ModuleRepo.configMap())
        }
        running = data[0] as Boolean
        snap = data[1] as StatusSnapshot
        cfg = data[2] as Map<String, String>
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1500)
            tick++
        }
    }

    val timeoutSec = ((cfg["TIMEOUT_MIN"]?.toDoubleOrNull() ?: 30.0) * 60.0).toLong().coerceAtLeast(1)
    val mode = cfg["KILL_MODE"] ?: "sigkill"
    val scopeText = cfg["KILL_SCOPE"] ?: "pkg"
    val dry = cfg["DRY_RUN"] == "1"
    val enabled = cfg["ENABLE"] != "0"
    val frozenProcs = snap.apps.sumOf { it.frozenProcs }

    Column(Modifier.fillMaxSize()) {
        PageHeader("AppKiller", "后台冻结超时清理 · HyperOS greeze")
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                PanelCard(Modifier.fillMaxWidth()) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Dot(
                                when {
                                    !enabled -> Color(0xFF9E9E9E)
                                    running == true -> Color(0xFF4CAF50)
                                    else -> Color(0xFFF44336)
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = when {
                                    !enabled -> "已禁用"
                                    running == true -> "守护进程运行中"
                                    running == false -> "守护进程未运行"
                                    else -> "检测中…"
                                },
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MiuixTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = if (snap.pid > 0) "pid " + snap.pid else "",
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        InfoRow("清理阈值", "冻结满 " + timeoutSec / 60 + " 分 " + timeoutSec % 60 + " 秒后清理")
                        InfoRow("清理方式", mode + " · 范围 " + scopeText + (if (dry) " · 观察模式" else ""))
                        InfoRow("冻结判定", "cgroup frozen / cgroup.freeze / STATE=T")
                        InfoRow("运行时长", formatUptime(snap.uptime))
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        busy = true
                                        withContext(Dispatchers.IO) { ModuleRepo.startDaemon(ctx) }
                                        delay(600)
                                        tick++
                                        busy = false
                                        notice = "已请求启动"
                                    }
                                },
                                enabled = !busy,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("启动 / 重启", fontSize = 14.sp)
                            }
                            Button(
                                onClick = {
                                    scope.launch {
                                        busy = true
                                        withContext(Dispatchers.IO) { ModuleRepo.stopDaemon() }
                                        delay(400)
                                        tick++
                                        busy = false
                                        notice = "已停止"
                                    }
                                },
                                enabled = !busy,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("停止", fontSize = 14.sp)
                            }
                        }
                        if (notice.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(notice, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    }
                }
            }
            item { SectionTitle("统计") }
            item {
                PanelCard(Modifier.fillMaxWidth()) {
                    Column {
                        InfoRow("正在计时", snap.apps.size.toString() + " 个应用")
                        InfoRow("被冻结进程", frozenProcs.toString() + " 个")
                        InfoRow("最久冻结", longestFrozen(snap))
                        InfoRow("累计清理", snap.apps.sumOf { it.killedTimes }.toString() + " 次")
                    }
                }
            }
            item { SectionTitle("说明") }
            item {
                PanelCard(Modifier.fillMaxWidth()) {
                    Column {
                        Text(
                            "只在应用被系统冻结（greeze / cgroup frozen）后才开始计时；计时期间应用被唤起、被解冻或出现前台进程，计时立即归零。",
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "冻结满阈值后执行清理，清理前会复查一次应用状态，仍在前台则本轮跳过。",
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "名单优先级：黑名单 > 白名单 > persistent 应用 > 系统应用 > 默认策略。",
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

fun longestFrozen(snap: StatusSnapshot): String {
    if (snap.apps.isEmpty()) return "—"
    val max = snap.apps.maxOf { it.frozenSec }
    return max.toString() + " 秒"
}

fun formatUptime(sec: Long): String {
    if (sec <= 0) return "—"
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return when {
        h > 0 -> h.toString() + "小时" + m + "分"
        m > 0 -> m.toString() + "分" + s + "秒"
        else -> s.toString() + "秒"
    }
}