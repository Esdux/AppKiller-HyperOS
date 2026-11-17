package com.yg.appkiller.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yg.appkiller.R
import com.yg.appkiller.ui.data.DaemonState
import com.yg.appkiller.ui.theme.isInDarkTheme
import com.yg.appkiller.ui.util.DshSpacing
import com.yg.appkiller.ui.util.PageScaffold
import com.yg.appkiller.ui.util.firstCard
import com.yg.appkiller.ui.util.nextCard
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * 主页 = 状态卡 → 守护数据卡 → 设备卡。
 *
 * 顺序是排班规则，不是审美：状态 → 主操作 → 数据 → 元信息
 * （见 references/information-architecture.md）。用户打开 App 第一眼要回答的是
 * 「它现在好使吗」，所以状态卡永远第一张，且整张可点 = 手动刷新。
 *
 * 状态卡三要点（照抄 vcut_lower / HyperMusicCover）：
 * 1. 大色块表达成败，不是只写一行红字；
 * 2. 副标题给出**下一步动作**，不是复述错误；
 * 3. 底纹图标出血铺在右下角，不额外占高度。
 */
@Composable
fun HomePage(
    isBlurEnabled: Boolean,
    extraBottomPadding: Dp = 0.dp,
    state: DaemonState,
    onRefresh: () -> Unit = {},
) {
    PageScaffold(
        title = "AppKiller",
        isBlurEnabled = isBlurEnabled,
        extraBottomPadding = extraBottomPadding,
    ) {
        item(key = "status") {
            Box(modifier = Modifier.fillMaxWidth().firstCard()) {
                StatusCard(state = state, onRefresh = onRefresh)
            }
        }

        item(key = "data") {
            Card(modifier = Modifier.nextCard()) {
                Column {
                    BasicComponent(title = "守护状态", summary = runtimeText(state))
                    BasicComponent(title = "已运行", summary = state.uptimeText)
                    BasicComponent(
                        title = "正在冻结",
                        summary = if (state.watching.isEmpty()) "无" else state.watching.size.toString() + " 个",
                    )
                    BasicComponent(title = "白名单", summary = state.protectCount.toString() + " 个")
                    BasicComponent(title = "黑名单", summary = state.blackCount.toString() + " 个")
                }
            }
        }

        item(key = "device") {
            Card(modifier = Modifier.nextCard()) {
                Column {
                    BasicComponent(title = "设备型号", summary = Build.MODEL)
                    BasicComponent(title = "系统版本", summary = Build.DISPLAY)
                    BasicComponent(title = "Android", summary = Build.VERSION.RELEASE)
                }
            }
        }
    }
}

private fun runtimeText(state: DaemonState): String = when {
    !state.loaded -> "读取中"
    state.running -> "运行中 · pid " + state.pid
    else -> "已停止"
}

@Composable
private fun StatusCard(state: DaemonState, onRefresh: () -> Unit) {
    val ok = state.running
    val dark = isInDarkTheme()
    val dynamic = MiuixTheme.isDynamicColor

    // 颜色三档：动态取色 → 手写深色 → 手写浅色。
    // 不要直接用 errorContainer / secondaryContainer 了事：深色 Monet 下对比度会不够。
    val container = if (ok) {
        when {
            dynamic -> MiuixTheme.colorScheme.secondaryContainer
            dark -> Color(0xFF1A3825)
            else -> Color(0xFFDFFAE4)
        }
    } else {
        when {
            dynamic -> MiuixTheme.colorScheme.errorContainer
            dark -> Color(0xFF3D1C1C)
            else -> Color(0xFFFDE8E8)
        }
    }
    val iconTint = if (ok) {
        if (dynamic) MiuixTheme.colorScheme.primary.copy(alpha = 0.8f) else Color(0xFF36D167)
    } else {
        if (dynamic) MiuixTheme.colorScheme.error.copy(alpha = 0.8f) else Color(0xFFDC3545)
    }

    val title = when {
        !state.loaded -> "读取中"
        ok -> "守护中"
        else -> "未运行"
    }
    val subtitle = when {
        !state.loaded -> "正在连接守护进程"
        ok && state.watching.isNotEmpty() -> "正在冻结 " + state.watching.size + " 个应用"
        ok -> "一切正常，没有需要冻结的应用"
        !state.root -> "没有 root 权限，请先在 KernelSU / Magisk 里授权"
        else -> "去设置页点「启动守护」，或检查模块是否已刷入"
    }

    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.defaultColors(color = container),
            onClick = onRefresh,
            showIndication = true,
            // Tilt：按压时整卡轻微倾斜，与 HyperOS 系统卡片一致；不要用 Ripple
            pressFeedbackType = PressFeedbackType.Tilt,
        ) {
            Box {
                // 底纹图标：offset 让它故意出血，只露一角
                Box(
                    modifier = Modifier.fillMaxSize().offset(27.dp, 31.dp),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    Icon(
                        modifier = Modifier.size(110.dp),
                        painter = painterResource(
                            if (ok) R.drawable.ic_check_circle else R.drawable.ic_error_circle,
                        ),
                        tint = iconTint,
                        contentDescription = null,
                    )
                }
                // 左下角应用名：状态卡同时也是「我是谁」的位置
                Box(
                    modifier = Modifier.fillMaxSize()
                        .padding(DshSpacing.CardInside, DshSpacing.Compact),
                    contentAlignment = Alignment.BottomStart,
                ) {
                    MiuixText(
                        text = "AppKiller",
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Box(
                    modifier = Modifier.fillMaxSize()
                        .padding(DshSpacing.CardInside, DshSpacing.Block),
                    contentAlignment = Alignment.TopStart,
                ) {
                    Column {
                        MiuixText(
                            text = title,
                            style = MiuixTheme.textStyles.title3,
                            fontWeight = FontWeight.SemiBold,
                        )
                        // 2dp：标题与副标题之间只做视觉分离，不用行距。
                        // 1dp 在低密度屏上会被抹成 0，两行会贴在一起。
                        Spacer(Modifier.height(2.dp))
                        MiuixText(
                            text = subtitle,
                            style = MiuixTheme.textStyles.body2,
                        )
                    }
                }
            }
        }
    }
}
