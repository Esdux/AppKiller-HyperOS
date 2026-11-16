package com.yg.appkiller.ui.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

const val UI_STYLE_MIUIX = 0
const val UI_STYLE_MATERIAL = 1

val LocalColorMode = compositionLocalOf { 0 }
val LocalUiStyle = compositionLocalOf { UI_STYLE_MIUIX }

@Composable
fun AppTheme(
    uiStyle: Int = UI_STYLE_MIUIX,
    colorMode: Int = 0,
    keyColor: Color? = null,
    paletteStyle: Int = 0,
    colorSpec: Int = 0,
    smoothRounding: Boolean = true,
    content: @Composable () -> Unit,
) {
    val spec = ThemeColorSpec.entries.getOrNull(colorSpec) ?: ThemeColorSpec.Spec2021
    val style = ThemePaletteStyle.entries.getOrNull(paletteStyle) ?: ThemePaletteStyle.Content
    val controller = remember(colorMode, keyColor, spec, style) {
        when (colorMode) {
            1 -> ThemeController(ColorSchemeMode.Light)
            2 -> ThemeController(ColorSchemeMode.Dark)
            3 -> ThemeController(ColorSchemeMode.MonetSystem, keyColor = keyColor, colorSpec = spec, paletteStyle = style)
            4 -> ThemeController(ColorSchemeMode.MonetLight, keyColor = keyColor, colorSpec = spec, paletteStyle = style)
            5 -> ThemeController(ColorSchemeMode.MonetDark, keyColor = keyColor, colorSpec = spec, paletteStyle = style)
            else -> ThemeController(ColorSchemeMode.System)
        }
    }
    CompositionLocalProvider(
        LocalColorMode provides colorMode,
        LocalUiStyle provides uiStyle,
        LocalSquircleEnabled provides smoothRounding,
    ) {
        MiuixTheme(controller) {
            content()
        }
    }
}

@Composable
fun isInDarkTheme(): Boolean = when (LocalColorMode.current) {
    1, 4 -> false
    2, 5 -> true
    else -> isSystemInDarkTheme()
}
