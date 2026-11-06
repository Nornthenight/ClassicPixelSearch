package com.pixel.classicsearch

/**
 * Policy governing the selected_search_engine setting in Pixel Launcher.
 *
 * In Android 17 (CP41) and modern Pixel Launcher architectures, the app drawer (All Apps)
 * and the home dock (Hotseat) search containers are isolated. Dynamic toggling of
 * selected_search_engine to Google on drawer opening causes OseWidgetView to rebuild and flash
 * the stock Google Search bar on drawer close/collapse.
 *
 * By strictly enforcing CLASSIC_SEARCH_PACKAGE without transient toggles, dock flicker is
 * completely eliminated while in-app search dispatch remains fully functional via SearchTapActivity.
 */
object LauncherEnginePolicy {

    /**
     * Resolves the target search engine package name based on user preference.
     * Always returns CLASSIC_SEARCH_PACKAGE when dock replacement is enabled,
     * guaranteeing zero dock flicker when the app drawer opens or collapses.
     */
    fun resolveTargetEngine(isDockReplacementEnabled: Boolean): String? {
        return if (isDockReplacementEnabled) ServiceHelper.CLASSIC_SEARCH_PACKAGE else null
    }

    /**
     * Determines whether Settings.Secure needs to be updated.
     */
    fun shouldUpdateEngine(currentEngineInSettings: String?, targetEngine: String?): Boolean {
        if (targetEngine == null) return false
        return currentEngineInSettings != targetEngine
    }
}
