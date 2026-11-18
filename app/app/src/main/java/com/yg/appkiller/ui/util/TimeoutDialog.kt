package com.yg.appkiller.ui.util

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 分钟数的显示文案。输入、存储、显示一律用分钟，不做小时/天换算 ——
 * 换算看着体贴，实际会让「30 分钟」和「0.5 小时」两种说法同时存在，
 * 用户在两处对照时反而要多算一遍。
 */
fun timeoutText(minutes: Int, zeroText: String = "跟随上级"): String =
    if (minutes <= 0) zeroText else minutes.toString() + " 分钟"

/**
 * 清理时间的「自定义」输入框。全局设置页与应用二级页共用一份，单位统一分钟。
 *
 * 为什么是分钟而不是秒：档位本身是 1/2/5/10/15/30/60/120 分钟，用户脑子里的刻度就是分钟；
 * 让他在两处之间换算秒数，只会输错一个零。
 *
 * 为什么不用滑杆：这个值能到 7200 分钟，滑杆要么精度不够要么拖半天。
 */
@Composable
fun TimeoutInputDialog(
    show: Boolean,
    title: String,
    initialMinutes: Int,
    allowZero: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    // 每次打开都从当前值起编，而不是记住上次输入 —— 用户点开时想改的是「现在这个值」。
    var text by remember(show, initialMinutes) { mutableStateOf(if (initialMinutes > 0) initialMinutes.toString() else "") }
    var error by remember(show) { mutableStateOf("") }

    val parsed = text.trim().toIntOrNull()
    val valid = when {
        text.isBlank() -> allowZero
        parsed == null -> false
        parsed < 0 -> false
        parsed == 0 -> allowZero
        else -> parsed <= 100000
    }

    // 不传 summary：标题已经说明这是「清理时间」，再补一句「冻结超过这个时间才清理」
    // 只是把同一件事说两遍，还多占一行高度。
    WindowDialog(
        show = show,
        title = title,
        onDismissRequest = onDismiss,
    ) {
        val dismiss = LocalDismissState.current
        Column {
            TextField(
                value = text,
                onValueChange = { raw ->
                    // 只留数字，顺手把超长输入截断：这个框只收分钟数，粘进一篇文章没有意义。
                    val digits = raw.filter { it.isDigit() }.take(6)
                    text = digits
                    error = ""
                },
                modifier = Modifier.fillMaxWidth(),
                label = "分钟",
                useLabelAsPlaceholder = true,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            // 只在出错时占一行：换算提示已去掉，正常输入时不需要额外文案。
            if (error.isNotEmpty()) {
                MiuixText(
                    text = error,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Row(
                modifier = Modifier.padding(top = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    modifier = Modifier.weight(1f),
                    text = "取消",
                    onClick = { dismiss?.invoke() },
                )
                Spacer(Modifier.width(12.dp))
                TextButton(
                    modifier = Modifier.weight(1f),
                    text = "确定",
                    onClick = {
                        when {
                            !valid && text.isBlank() -> error = "请输入分钟数"
                            !valid -> error = "请输入 1 到 100000 之间的整数"
                            else -> {
                                dismiss?.invoke()
                                onConfirm(parsed ?: 0)
                            }
                        }
                    },
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}