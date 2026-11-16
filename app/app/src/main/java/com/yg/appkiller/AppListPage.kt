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
import androidx.compose.ui.platform.LocalContext
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

/**
 * 应用列表：对应 Cirno 的 App list。
 * 每行只有一个白名单开关 —— 打开表示该应用在白名单里（永不清理），关闭表示不在白名单。
 */
@Composable
fun AppListPage() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var filter by remember { mutableIntStateOf(0) }   // 0 全部 / 1 白名单 / 2 系统
    var query by remember { mutableStateOf("") }
    var all by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var white by remember { mutableStateOf<Set<String>>(emptySet()) }
    var dirty by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val d = withContext(Dispatchers.IO) {
            arrayOf<Any>(AppRepo.installed(ctx), ModuleRepo.list("protect.list").toSet())
        }
        all = d[0] as List<AppEntry>
        white = d[1] as Set<String>
        loaded = true
    }

    val shown = all.filter {
        (query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true)) &&
            when (filter) {
                1 -> white.contains(it.pkg)
                2 -> it.system
                else -> true
            }
    }

    Column(Modifier.fillMaxSize()) {
        PageHeader("应用列表", "开关打开 = 加入白名单（永不清理）")
        InnerTabs(listOf("全部", "白名单", "系统应用"), filter) { filter = it }
        Column(Modifier.padding(horizontal = 12.dp)) {
            TextField(
                value = query,
                onValueChange = { query = it },
                label = "搜索应用名或包名",
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(6.dp))
        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            items(shown, key = { it.pkg }) { app ->
                val inWhite = white.contains(app.pkg)
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
                                text = buildString {
                                    append(app.pkg)
                                    if (app.system) append("  · 系统")
                                    if (app.persistent) append("  · 常驻")
                                },
                                fontSize = 11.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        Switch(
                            checked = inWhite,
                            onCheckedChange = { on ->
                                white = if (on) white + app.pkg else white - app.pkg
                                dirty = true
                            },
                        )
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
                    else -> "共 " + all.size + " 个应用 · 白名单 " + white.size
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
                            ModuleRepo.writeList(
                                "protect.list",
                                "# 保护名单：命中的包永不被清理（前缀 * 匹配，# 为注释）",
                                white,
                            )
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
