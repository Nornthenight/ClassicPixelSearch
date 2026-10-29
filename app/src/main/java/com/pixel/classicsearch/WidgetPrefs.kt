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
    const val ICON_DYNAMIC_MONET = 0    // Default: Material You Dynamic Monet Colors (tracks wallpaper)
    const val ICON_ORIGINAL_GOOGLE = 1  // Classic 4-color Google + Gemini gradient
    const val ICON_CLASSIC_COLOR = 1    // Alias for backwards compatibility
    const val ICON_ADAPTIVE_EMERALD = 2 // Dual-Tone Accent Shimmer (Dynamic system accents)
    const val ICON_LIGHT_DARK = 3       // High-contrast Light / Dark monochrome theme

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
        return prefs.getInt(KEY_ICON_STYLE, ICON_DYNAMIC_MONET)
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
