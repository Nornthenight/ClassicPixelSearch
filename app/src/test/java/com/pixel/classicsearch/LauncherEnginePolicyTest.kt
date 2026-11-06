package com.pixel.classicsearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherEnginePolicyTest {

    @Test
    fun `resolveTargetEngine when dock replacement disabled returns null`() {
        val target = LauncherEnginePolicy.resolveTargetEngine(
            isDockReplacementEnabled = false,
            isDrawerInputFocused = true
        )
        assertNull(target)
    }

    @Test
    fun `resolveTargetEngine activates google search when user is focused in drawer search`() {
        val target = LauncherEnginePolicy.resolveTargetEngine(
            isDockReplacementEnabled = true,
            isDrawerInputFocused = true
        )
        assertEquals(ServiceHelper.GOOGLE_SEARCH_PACKAGE, target)
    }

    @Test
    fun `resolveTargetEngine immediately restores classic search when drawer loses focus`() {
        // When drawer swipe-down starts, focus is lost instantly, restoring ClassicSearch before animation completes
        val target = LauncherEnginePolicy.resolveTargetEngine(
            isDockReplacementEnabled = true,
            isDrawerInputFocused = false
        )
        assertEquals(ServiceHelper.CLASSIC_SEARCH_PACKAGE, target)
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
    fun `shouldUpdateEngine returns true when transition between engines is required`() {
        val shouldUpdateToGoogle = LauncherEnginePolicy.shouldUpdateEngine(
            currentEngineInSettings = ServiceHelper.CLASSIC_SEARCH_PACKAGE,
            targetEngine = ServiceHelper.GOOGLE_SEARCH_PACKAGE
        )
        assertTrue(shouldUpdateToGoogle)

        val shouldUpdateToClassic = LauncherEnginePolicy.shouldUpdateEngine(
            currentEngineInSettings = ServiceHelper.GOOGLE_SEARCH_PACKAGE,
            targetEngine = ServiceHelper.CLASSIC_SEARCH_PACKAGE
        )
        assertTrue(shouldUpdateToClassic)
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
