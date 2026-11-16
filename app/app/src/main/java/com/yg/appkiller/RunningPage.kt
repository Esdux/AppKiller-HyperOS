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
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.layout.Arrangement as Arr

/**
 * 运行列表：与 Cirno 的 Running list 对应 —— 只列当前真正处于「被冻结且正在计时」的应用。
 * 每行显示：应用名 / 包名 / 冻结进程数 / 已冻结秒数 / 距离清理的剩余秒数。
 */
@Composable
fun RunningPage() {
    val ctx = LocalContext.current
    var snap by remember { mutableStateOf(StatusSnapshot()) }
    var cfg by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var labels by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var query by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(tick) {
        val d = withContext(Dispatchers.IO) {
            arrayOf<Any>(StateRepo.snapshot(), ModuleRepo.configMap())
        }
        snap = d[0] as StatusSnapshot
        cfg = d[1] as Map<String, String>
    }
    LaunchedEffect(Unit) {
        labels = withContext(Dispatchers.IO) { AppRepo.labelMap(ctx) }
        while (true) {
            delay(1500)
            tick++
        }
    }

    val timeoutSec = ((cfg["TIMEOUT_MIN"]?.toDoubleOrNull() ?: 30.0) * 60.0).toLong().coerceAtLeast(1)
    val shown = snap.apps.filter {
        query.isBlank() || it.pkg.contains(query, true) ||
            (labels[it.pkg] ?: "").contains(query, true)
    }

    Column(Modifier.fillMaxSize()) {
        PageHeader("运行列表", "被冻结并正在计时的应用")
        Column(Modifier.padding(horizontal = 12.dp)) {
            TextField(
                value = query,
                onValueChange = { query = it },
                label = "搜索应用名或包名",
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(6.dp))
        if (shown.isEmpty()) {
            Column(Modifier.padding(horizontal = 12.dp)) {
                PanelCard(Modifier.fillMaxWidth()) {
                    Column {
                        Text(
                            "当前没有被冻结计时的应用",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        Text(
                            "把应用切到后台静置，系统（或 Cirno）冻结它之后，这里就会出现倒计时",
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }
        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arr.spacedBy(3.dp),
        ) {
            items(shown, key = { it.pkg }) { app ->
                val label = labels[app.pkg] ?: app.pkg.substringAfterLast('.')
                val frac = (app.frozenSec.toFloat() / timeoutSec.toFloat()).coerceIn(0f, 1f)
                PanelCard(Modifier.fillMaxWidth()) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = label,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "剩余 " + app.remainSec + "s",
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.primary,
                            )
                        }
                        Text(app.pkg, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        Spacer(Modifier.height(6.dp))
                        ThinProgress(frac)
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "冻结 " + app.frozenProcs + "/" + app.totalProcs + " 进程 · 已冻结 " + app.frozenSec + "s",
                                fontSize = 11.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.weight(1f),
                            )
                            if (app.killedTimes > 0) {
                                Text(
                                    "已清理 " + app.killedTimes + " 次",
                                    fontSize = 11.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}
