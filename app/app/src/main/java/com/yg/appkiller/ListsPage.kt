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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
fun ListsPage() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var all by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var white by remember { mutableStateOf<Set<String>>(emptySet()) }
    var black by remember { mutableStateOf<Set<String>>(emptySet()) }
    var dirty by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val d = withContext(Dispatchers.IO) {
            arrayOf<Any>(
                AppRepo.installed(ctx),
                ModuleRepo.list("protect.list").toSet(),
                ModuleRepo.list("blacklist.list").toSet(),
            )
        }
        all = d[0] as List<AppEntry>
        white = d[1] as Set<String>
        black = d[2] as Set<String>
        loaded = true
    }

    val shown = all.filter {
        (query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true)) &&
            (tab != 2 || it.system)
    }

    Column(Modifier.fillMaxSize()) {
        PageHeader("名单", "白名单豁免 · 黑名单强制清理（优先级更高）")
        InnerTabs(listOf("白名单", "黑名单", "系统应用"), tab) { tab = it }
        Column(Modifier.padding(horizontal = 12.dp)) {
            TextField(
                value = query,
                onValueChange = { query = it },
                label = "搜索应用名或包名",
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(6.dp))
        if (tab == 2) {
            PanelCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column {
                    Text("系统应用默认走白名单（不清理）", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MiuixTheme.colorScheme.onSurface)
                    Text(
                        "要让某个系统应用也被纳入清理，把它的开关打开（等于加入黑名单）。硬保护名单（system_server、SystemUI、电话、蓝牙等）不会被覆盖。",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            items(shown, key = { it.pkg }) { app ->
                val inWhite = white.contains(app.pkg)
                val inBlack = black.contains(app.pkg)
                PanelCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = app.label,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = app.pkg + (if (app.system) "  · 系统" else ""),
                                fontSize = 11.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        when (tab) {
                            0 -> Switch(checked = inWhite, onCheckedChange = { on ->
                                white = if (on) white + app.pkg else white - app.pkg
                                dirty = true
                            })
                            else -> Switch(checked = inBlack, onCheckedChange = { on ->
                                black = if (on) black + app.pkg else black - app.pkg
                                dirty = true
                            })
                        }
                    }
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when {
                    !loaded -> "加载中…"
                    notice.isNotEmpty() -> notice
                    dirty -> "有未保存的改动"
                    else -> "白名单 " + white.size + " · 黑名单 " + black.size
                },
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    scope.launch {
                        saving = true
                        withContext(Dispatchers.IO) {
                            ModuleRepo.writeList("protect.list", "# 白名单：命中的包不参与冻结超时清理", white)
                            ModuleRepo.writeList("blacklist.list", "# 黑名单：命中的包强制参与清理，优先级高于白名单与系统应用默认", black)
                            ModuleRepo.reload()
                        }
                        saving = false
                        dirty = false
                        notice = "已保存并通知守护进程重载"
                    }
                },
                enabled = dirty && !saving,
            ) {
                Text(if (saving) "保存中…" else "保存并重载", fontSize = 14.sp)
            }
        }
    }
}
