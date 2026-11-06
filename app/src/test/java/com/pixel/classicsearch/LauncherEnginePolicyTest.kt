package com.pixel.classicsearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherEnginePolicyTest {

    @Test
    fun `resolveTargetEngine when dock replacement enabled strictly returns classic search package`() {
        val target = LauncherEnginePolicy.resolveTargetEngine(isDockReplacementEnabled = true)
        assertEquals(ServiceHelper.CLASSIC_SEARCH_PACKAGE, target)
    }

    @Test
    fun `resolveTargetEngine when dock replacement disabled returns null`() {
        val target = LauncherEnginePolicy.resolveTargetEngine(isDockReplacementEnabled = false)
        assertNull(target)
    }

    @Test
    fun `drawer drop and fast collapse preserves classic search engine without stock google fallback`() {
        // Fast swipe test: drawer opened -> rapid swipe down -> home screen idle
        val targetAtOpen = LauncherEnginePolicy.resolveTargetEngine(isDockReplacementEnabled = true)
        val targetAtDrop = LauncherEnginePolicy.resolveTargetEngine(isDockReplacementEnabled = true)
        val targetAtHome = LauncherEnginePolicy.resolveTargetEngine(isDockReplacementEnabled = true)

        assertEquals(ServiceHelper.CLASSIC_SEARCH_PACKAGE, targetAtOpen)
        assertEquals(ServiceHelper.CLASSIC_SEARCH_PACKAGE, targetAtDrop)
        assertEquals(ServiceHelper.CLASSIC_SEARCH_PACKAGE, targetAtHome)
    }

    @Test
    fun `shouldUpdateEngine returns false when current setting matches target`() {
        val shouldUpdate = LauncherEnginePolicy.shouldUpdateEngine(
            currentEngineInSettings = ServiceHelper.CLASSIC_SEARCH_PACKAGE,
            targetEngine = ServiceHelper.CLASSIC_SEARCH_PACKAGE
        )
        assertFalse(shouldUpdate)
    }

    @Test
    fun `shouldUpdateEngine returns true when current setting is stock google or foreign package`() {
        val shouldUpdateFromGoogle = LauncherEnginePolicy.shouldUpdateEngine(
            currentEngineInSettings = ServiceHelper.GOOGLE_SEARCH_PACKAGE,
            targetEngine = ServiceHelper.CLASSIC_SEARCH_PACKAGE
        )
        assertTrue(shouldUpdateFromGoogle)
    }

    @Test
    fun `shouldUpdateEngine returns false when target is null`() {
        val shouldUpdate = LauncherEnginePolicy.shouldUpdateEngine(
            currentEngineInSettings = ServiceHelper.CLASSIC_SEARCH_PACKAGE,
            targetEngine = null
        )
        assertFalse(shouldUpdate)
    }
}
