package com.yg.appkiller.ui.utils

import android.content.Context

/** 界面偏好的轻量持久化（SharedPreferences），与守护配置无关。 */
object UiPrefs {
    private const val NAME = "ui_prefs"
    private const val KEY_LIQUID_GLASS = "liquid_glass"
    private const val KEY_COLOR_MODE = "color_mode"
    private const val KEY_BLUR = "blur"
    private const val KEY_HAPTIC = "haptic"

    fun liquidGlass(ctx: Context): Boolean =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_LIQUID_GLASS, true)

    fun setLiquidGlass(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_LIQUID_GLASS, on)
            .apply()
    }

    fun colorMode(ctx: Context): Int =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getInt(KEY_COLOR_MODE, 0)

    fun setColorMode(ctx: Context, v: Int) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putInt(KEY_COLOR_MODE, v).apply()
    }

    fun blur(ctx: Context): Boolean =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getBoolean(KEY_BLUR, true)

    fun setBlur(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_BLUR, on).apply()
    }

    fun haptic(ctx: Context): Boolean =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getBoolean(KEY_HAPTIC, true)

    fun setHaptic(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_HAPTIC, on).apply()
    }
}
