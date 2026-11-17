package com.yg.appkiller

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Path
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 分组小标题（Cirno「冻结信息」「应用信息」「界面设置」那种灰字） */
@Composable
fun GroupTitle(text: String) {
    Text(
        text = text,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 2.dp),
    )
}

/**
 * 图标内存缓存：LazyColumn 复用行时同一个包会被反复请求，
 * 每次都 getApplicationIcon + 画 Bitmap 是列表滑动卡顿的主因。
 * 键是包名，值是不可变 ImageBitmap，命中即零开销。
 */
private object IconCache {
    private const val MAX = 400
    private val map = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean = size > MAX
    }
    @Synchronized fun get(pkg: String): ImageBitmap? = map[pkg]
    @Synchronized fun put(pkg: String, b: ImageBitmap) { map[pkg] = b }
}

/** 应用图标：圆角方图，取不到时退回首字母块 */
@Composable
fun AppIcon(pkg: String, label: String = "", size: Dp = 44.dp) {
    val ctx = LocalContext.current
    var bmp by remember(pkg) { mutableStateOf(IconCache.get(pkg)) }
    LaunchedEffect(pkg) {
        if (bmp != null) return@LaunchedEffect
        bmp = withContext(Dispatchers.IO) {
            runCatching {
                val d = ctx.packageManager.getApplicationIcon(pkg)
                val b = if (d is BitmapDrawable) d.bitmap else {
                    val w = d.intrinsicWidth.coerceIn(1, 512)
                    val h = d.intrinsicHeight.coerceIn(1, 512)
                    val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    d.setBounds(0, 0, w, h)
                    d.draw(Canvas(bm))
                    bm
                }
                b.asImageBitmap().also { IconCache.put(pkg, it) }
            }.getOrNull()
        }
    }

    val shape = RoundedCornerShape(size * 0.28f)
    Box(
        Modifier
            .size(size)
            .clip(shape)
            .background(MiuixTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val image = bmp
        if (image != null) {
            Image(bitmap = image, contentDescription = null, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                text = (label.ifEmpty { pkg }).take(1).uppercase(),
                fontSize = (size.value * 0.4f).sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/** 状态标签（白名单 / 黑名单 那种小胶囊） */
@Composable
fun TagChip(text: String, container: Color, content: Color) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(container)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(text = text, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = content)
    }
}

/** 一行列表项：图标 + 两行文字 + 尾部内容 */
@Composable
fun IconListRow(
    pkg: String,
    title: String,
    subtitle: String,
    tag: (@Composable () -> Unit)? = null,
    thirdLine: String = "",
    trailingTop: String = "",
    trailingBottom: String = "",
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(pkg = pkg, label = title)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (tag != null) {
                    Spacer(Modifier.width(8.dp))
                    tag()
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (thirdLine.isNotEmpty()) {
                Text(
                    text = thirdLine,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailingTop.isNotEmpty() || trailingBottom.isNotEmpty()) {
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                if (trailingTop.isNotEmpty()) {
                    Text(
                        text = trailingTop,
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                if (trailingBottom.isNotEmpty()) {
                    Text(
                        text = trailingBottom,
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

/** 选择行：左标题 + 右值 + 上下箭头（Cirno 设置页那种） */
@Composable
fun SelectRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            fontSize = 17.sp,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            fontSize = 15.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.width(6.dp))
        UpDownGlyph(MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

/** Cirno 设置行右侧的「上下箭头」（Miuix 的 ArrowUpDown 接收者类型对不上，这里自绘） */
@Composable
fun UpDownGlyph(color: androidx.compose.ui.graphics.Color, width: Dp = 11.dp, height: Dp = 16.dp) {
    Canvas(Modifier.size(width, height)) {
        val w = size.width
        val h = size.height
        val up = Path().apply {
            moveTo(w / 2f, 0f)
            lineTo(w, h * 0.38f)
            lineTo(0f, h * 0.38f)
            close()
        }
        val down = Path().apply {
            moveTo(w / 2f, h)
            lineTo(w, h * 0.62f)
            lineTo(0f, h * 0.62f)
            close()
        }
        drawPath(up, color)
        drawPath(down, color)
    }
}

/** 开关行：左标题 + 说明 + 右侧 Switch */
@Composable
fun SwitchRow(title: String, summary: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, fontSize = 17.sp, color = MiuixTheme.colorScheme.onSurface)
            if (summary.isNotEmpty()) {
                Text(
                    text = summary,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 滑块行：标题 + 当前值 + Slider */
@Composable
fun SliderRow(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                fontSize = 15.sp,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueText,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 主界面两列统计用：大号数字块 */
@Composable
fun StatBlock(title: String, value: String, modifier: Modifier = Modifier, accent: Color? = null) {
    Column(modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            color = accent ?: MiuixTheme.colorScheme.onSurface,
        )
    }
}

/** 分组容器：把若干行装进一张卡（行之间不加线，Cirno 也是这样） */
@Composable
fun GroupCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    com.yg.appkiller.ui.utils.CirnoCard(modifier = modifier.fillMaxWidth(), insideMargin = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
        Column { content() }
    }
}
/** 放大镜（自绘，避免依赖图标包里的具体名字） */
@Composable
fun SearchGlyph(color: androidx.compose.ui.graphics.Color, dimension: Dp = 18.dp) {
    Canvas(Modifier.size(dimension)) {
        val r = size.minDimension * 0.34f
        val c = androidx.compose.ui.geometry.Offset(size.width * 0.42f, size.height * 0.42f)
        drawCircle(color = color, radius = r, center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(width = size.minDimension * 0.11f))
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(c.x + r * 0.72f, c.y + r * 0.72f),
            end = androidx.compose.ui.geometry.Offset(size.width * 0.9f, size.height * 0.9f),
            strokeWidth = size.minDimension * 0.11f,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }
}

/** Cirno 风格搜索框：圆角胶囊 + 放大镜 + 占位文字 */
@Composable
fun SearchField(value: String, onValueChange: (String) -> Unit, hint: String = "搜索") {
    Row(
        Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(25.dp))
            .background(MiuixTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchGlyph(MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    text = hint,
                    fontSize = 16.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 16.sp,
                    color = MiuixTheme.colorScheme.onSurface,
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MiuixTheme.colorScheme.primary),
                modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
            )
        }
    }
}
