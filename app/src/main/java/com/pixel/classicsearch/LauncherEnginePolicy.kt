package com.pixel.classicsearch

/**
 * Policy governing the selected_search_engine setting in Pixel Launcher.
 *
 * In Pixel Launcher, the drawer (All Apps) app-highlight (OneSearch) and Enter-to-launch
 * algorithms are natively powered by Google App (`GOOGLE_SEARCH_PACKAGE`).
 *
 * To achieve BOTH:
 * 1) Full native OneSearch highlight & instant app launch on Enter in the app drawer, AND
 * 2) Zero dock flicker when the drawer collapses,
 *
 * The engine is set to Google ONLY while the drawer search input is actively focused.
 * As soon as the input loses focus (at the start of the drawer swipe-down gesture),
 * the policy immediately restores CLASSIC_SEARCH_PACKAGE. Because Settings.Secure is
 * updated within 10ms during the ~350ms close animation, the dock is already showing
 * ClassicPixelSearch by the time the drawer drops down to reveal the home screen.
 */
object LauncherEnginePolicy {

    fun resolveTargetEngine(
        isDockReplacementEnabled: Boolean,
        isActivelySearchingInDrawer: Boolean
    ): String? {
        if (!isDockReplacementEnabled) return null
        return if (isActivelySearchingInDrawer) {
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
