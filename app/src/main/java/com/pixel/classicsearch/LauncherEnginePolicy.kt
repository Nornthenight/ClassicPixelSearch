package com.pixel.classicsearch

/**
 * Policy governing the selected_search_engine setting in Pixel Launcher.
 *
 * Strictly and monotonically enforces CLASSIC_SEARCH_PACKAGE when dock replacement is enabled.
 * Never toggles to Google Search, guaranteeing 100% zero dock flicker under any swipe velocity,
 * quick tap, or rapid drawer opening/closing.
 *
 * App launch on Enter and matching are handled by SearchTapActivity and LauncherStateService
 * using the enhanced AppSearchMatcher with transliteration and package-segment resolution.
 */
object LauncherEnginePolicy {

    fun resolveTargetEngine(isDockReplacementEnabled: Boolean): String? {
        return if (isDockReplacementEnabled) ServiceHelper.CLASSIC_SEARCH_PACKAGE else null
    }

    fun shouldUpdateEngine(currentEngineInSettings: String?, targetEngine: String?): Boolean {
        if (targetEngine == null) return false
        return currentEngineInSettings != targetEngine
    }
}
