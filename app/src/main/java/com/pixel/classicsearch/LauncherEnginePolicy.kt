package com.pixel.classicsearch

/**
 * Policy governing the selected_search_engine setting in Pixel Launcher.
 *
 * In Pixel Launcher, the drawer (All Apps) app-highlight and Enter-launch algorithms are
 * strictly coupled to Google OneSearch (`GOOGLE_SEARCH_PACKAGE`).
 *
 * To simultaneously achieve:
 * 1) Native OneSearch highlight & instant app launch on Enter in the app drawer, AND
 * 2) Zero dock flicker when the drawer collapses,
 *
 * The engine is set to Google ONLY while the search input is actively focused.
 * As soon as the input loses focus (at the start of the drawer swipe-down gesture),
 * the policy immediately restores CLASSIC_SEARCH_PACKAGE. Because Settings.Secure is
 * updated during the 300ms close animation, the dock is already showing ClassicPixelSearch
 * by the time the drawer reveals the home screen.
 */
object LauncherEnginePolicy {

    fun resolveTargetEngine(
        isDockReplacementEnabled: Boolean,
        isDrawerInputFocused: Boolean
    ): String? {
        if (!isDockReplacementEnabled) return null
        return if (isDrawerInputFocused) {
            ServiceHelper.GOOGLE_SEARCH_PACKAGE
        } else {
            ServiceHelper.CLASSIC_SEARCH_PACKAGE
        }
    }

    fun shouldUpdateEngine(currentEngineInSettings: String?, targetEngine: String?): Boolean {
        if (targetEngine == null) return false
        return currentEngineInSettings != targetEngine
    }
}
