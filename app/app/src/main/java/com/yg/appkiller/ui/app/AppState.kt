package com.yg.appkiller.ui.app

import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import top.yukonga.miuix.kmp.shader.isRenderEffectSupported

/** 界面层全局状态：风格、配色、动效开关。 */
@Stable
data class AppState(
    val uiStyle: Int = 0,
    val colorMode: Int = 0,
    val themeKeyColor: Int = 0,
    val themeColorSpec: Int = 0,
    val themePaletteStyle: Int = 0,
    val blur: Boolean = isRenderEffectSupported(),
    val enablePageUserScroll: Boolean = true,
    val enableScrollEndHaptic: Boolean = true,
    val enableCornerClip: Boolean = true,
    val enableDim: Boolean = true,
)

val LocalAppState = compositionLocalOf<AppState> {
    error("No AppState provided!")
}

val LocalUpdateAppState = staticCompositionLocalOf<((AppState) -> AppState) -> Unit> {
    error("No AppState updater provided!")
}

/** 是否宽屏（平板/折叠展开）：与 Cirno 的 LocalIsWideScreen 同义。 */
val LocalIsWideScreen = compositionLocalOf { false }
