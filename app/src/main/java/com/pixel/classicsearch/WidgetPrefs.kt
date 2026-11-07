package com.pixel.classicsearch

import android.content.Context

object WidgetPrefs {
    private const val PREFS_NAME = "classic_pixel_search_prefs"
    private const val KEY_SURFACE_STYLE = "surface_style"
    private const val KEY_ICON_STYLE = "icon_style"
    private const val KEY_OPACITY = "opacity_percent"
    private const val KEY_REPLACE_DOCK = "replace_dock"

    // Feature 1: Right-zone action keys
    private const val KEY_MIC_ACTION = "mic_action"
    private const val KEY_MIDDLE_ACTION = "middle_action"
    private const val KEY_AI_ACTION = "ai_action"

    // Feature 2: Haptic feedback keys
    private const val KEY_HAPTIC_ENABLED = "haptic_enabled"
    private const val KEY_HAPTIC_STYLE = "haptic_style"

    // Feature 3: Geometry & outer frame alpha keys
    private const val KEY_OUTER_ALPHA = "outer_alpha_percent"
    private const val KEY_RING_GAP = "ring_gap_dp"
    private const val KEY_CORNER_RADIUS = "corner_radius_dp"

    // Feature 4: Default Search & AI Service for Enter query
    private const val KEY_DEFAULT_SEARCH_AI = "default_search_ai"

    // Surface styles
    const val SURFACE_DEEP_RING = 0    // Default: system_accent2_300 frame + system_neutral1_10 inner
    const val SURFACE_PASTEL = 1       // system_accent1_200 frame + system_neutral1_10 inner
    const val SURFACE_RICH_ACCENT = 2  // system_accent1_400 frame + system_neutral1_10 inner
    const val SURFACE_AMOLED = 3       // Pure OLED Black (#000000) frame + pitch black (#0A0A0A) inner + subtle stroke

    // Icon styles
    const val ICON_ADAPTIVE_EMERALD = 0 // Default: Theme adaptive + Emerald Teal shimmer (Reference style)
    const val ICON_DYNAMIC_MONET = 1    // Multi-tone fragmented Material You gradients
    const val ICON_CLASSIC_COLOR = 2    // Classic 4-color Google + Gemini gradient

    // Button Action presets
    const val ACTION_DEFAULT = "default"
    const val ACTION_VOICE_SEARCH = "voice_search"
    const val ACTION_GEMINI = "gemini"
    const val ACTION_GOOGLE_LENS = "google_lens"
    const val ACTION_AI_MODE = "ai_mode"
    const val ACTION_CHATGPT = "chatgpt"
    const val ACTION_PERPLEXITY = "perplexity"
    const val ACTION_QR_SCANNER = "qr_scanner"
    const val ACTION_BROWSER_BRAVE = "browser_brave"
    const val ACTION_BROWSER_FIREFOX = "browser_firefox"
    const val ACTION_BROWSER_CROMITE = "browser_cromite"
    const val PREFIX_APP = "app:"

    // Search & AI Engine for Enter query
    const val SEARCH_ENGINE_GOOGLE = "google"
    const val SEARCH_ENGINE_CHATGPT = "chatgpt"
    const val SEARCH_ENGINE_PERPLEXITY = "perplexity"
    const val SEARCH_ENGINE_GEMINI = "gemini"
    const val SEARCH_ENGINE_AI_MODE = "ai_mode"
    const val SEARCH_ENGINE_COPILOT = "copilot"
    const val SEARCH_ENGINE_CLAUDE = "claude"

    // Haptic feedback styles
    const val HAPTIC_CLOCK_TICK = 0
    const val HAPTIC_CONTEXT_CLICK = 1

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

    fun isReplaceDockEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_REPLACE_DOCK, true)
    }

    fun setReplaceDockEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_REPLACE_DOCK, enabled)
            .apply()
    }

    // --- Feature 1: Right-Zone Actions ---

    fun getMicAction(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_MIC_ACTION, ACTION_VOICE_SEARCH) ?: ACTION_VOICE_SEARCH
    }

    fun setMicAction(context: Context, action: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MIC_ACTION, action)
            .apply()
    }

    fun getMiddleAction(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_MIDDLE_ACTION, ACTION_GEMINI) ?: ACTION_GEMINI
    }

    fun setMiddleAction(context: Context, action: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MIDDLE_ACTION, action)
            .apply()
    }

    fun getAiAction(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_AI_ACTION, ACTION_AI_MODE) ?: ACTION_AI_MODE
    }

    fun setAiAction(context: Context, action: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_AI_ACTION, action)
            .apply()
    }

    // --- Feature 2: Pixel-Style Haptics ---

    fun isHapticEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_HAPTIC_ENABLED, true)
    }

    fun setHapticEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HAPTIC_ENABLED, enabled)
            .apply()
    }

    fun getHapticStyle(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_HAPTIC_STYLE, HAPTIC_CLOCK_TICK)
    }

    fun setHapticStyle(context: Context, style: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_HAPTIC_STYLE, style)
            .apply()
    }

    // --- Feature 3: Geometry & Outer Alpha Controls ---

    fun getOuterAlphaPercent(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_OUTER_ALPHA, 85).coerceIn(0, 100)
    }

    fun setOuterAlphaPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_OUTER_ALPHA, percent.coerceIn(0, 100))
            .apply()
    }

    fun getRingGapDp(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_RING_GAP, 6).coerceIn(2, 12)
    }

    fun setRingGapDp(context: Context, dp: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_RING_GAP, dp.coerceIn(2, 12))
            .apply()
    }

    fun getCornerRadiusDp(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getInt(KEY_CORNER_RADIUS, 31)
        val value = if (saved == 28) 31 else saved
        return value.coerceIn(12, 31)
    }

    fun setCornerRadiusDp(context: Context, dp: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_CORNER_RADIUS, dp.coerceIn(12, 31))
            .apply()
    }

    // --- Feature 4: Default Search & AI Service for Enter Query ---

    fun getDefaultSearchAi(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_DEFAULT_SEARCH_AI, SEARCH_ENGINE_GOOGLE) ?: SEARCH_ENGINE_GOOGLE
    }

    fun setDefaultSearchAi(context: Context, engine: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DEFAULT_SEARCH_AI, engine)
            .apply()
    }
}
