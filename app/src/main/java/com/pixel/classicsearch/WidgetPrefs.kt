package com.pixel.classicsearch

import android.content.Context

object WidgetPrefs {
    private const val PREFS_NAME = "classic_pixel_search_prefs"
    private const val KEY_SURFACE_STYLE = "surface_style"
    private const val KEY_ICON_STYLE = "icon_style"
    private const val KEY_OPACITY = "opacity_percent"

    // Surface styles
    const val SURFACE_DEEP_RING = 0    // Default: system_accent2_300 frame + system_neutral1_10 inner
    const val SURFACE_PASTEL = 1       // system_accent1_200 frame + system_neutral1_10 inner
    const val SURFACE_RICH_ACCENT = 2  // system_accent1_400 frame + system_neutral1_10 inner

    // Icon styles
    const val ICON_ADAPTIVE_EMERALD = 0 // Default: Theme adaptive + Emerald Teal shimmer (Reference style)
    const val ICON_DYNAMIC_MONET = 1    // Multi-tone fragmented Material You gradients
    const val ICON_CLASSIC_COLOR = 2    // Classic 4-color Google + Gemini gradient

    fun getSurfaceStyle(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_SURFACE_STYLE, SURFACE_DEEP_RING)
    }

    fun setSurfaceStyle(context: Context, style: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SURFACE_STYLE, style)
            .apply()
    }

    fun getIconStyle(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_ICON_STYLE, ICON_ADAPTIVE_EMERALD)
    }

    fun setIconStyle(context: Context, style: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_ICON_STYLE, style)
            .apply()
    }

    fun getOpacityPercent(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_OPACITY, 100)
    }

    fun setOpacityPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_OPACITY, percent.coerceIn(30, 100))
            .apply()
    }
}
