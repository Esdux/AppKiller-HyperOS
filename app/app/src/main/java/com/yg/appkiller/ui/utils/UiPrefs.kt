package com.yg.appkiller.ui.utils

import android.content.Context

/** 界面偏好的轻量持久化（SharedPreferences），与守护配置无关。 */
object UiPrefs {
    private const val NAME = "ui_prefs"
    private const val KEY_LIQUID_GLASS = "liquid_glass"

    fun liquidGlass(ctx: Context): Boolean =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_LIQUID_GLASS, true)

    fun setLiquidGlass(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_LIQUID_GLASS, on)
            .apply()
    }
}
