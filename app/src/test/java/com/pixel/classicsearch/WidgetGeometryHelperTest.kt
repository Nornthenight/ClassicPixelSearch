package com.pixel.classicsearch

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetGeometryHelperTest {

    @Test
    fun `target optical size constant is exactly 24dp`() {
        assertEquals(24f, WidgetGeometryHelper.TARGET_ICON_OPTICAL_SIZE_DP, 0.001f)
    }

    @Test
    fun `target size in pixels scales correctly across densities`() {
        // mdpi (1.0x) -> 24px
        assertEquals(24, WidgetGeometryHelper.calculateTargetSizePx(1.0f))

        // hdpi (1.5x) -> 36px
        assertEquals(36, WidgetGeometryHelper.calculateTargetSizePx(1.5f))

        // xhdpi (2.0x) -> 48px
        assertEquals(48, WidgetGeometryHelper.calculateTargetSizePx(2.0f))

        // xxhdpi (3.0x) -> 72px
        assertEquals(72, WidgetGeometryHelper.calculateTargetSizePx(3.0f))

        // xxxhdpi (4.0x) -> 96px
        assertEquals(96, WidgetGeometryHelper.calculateTargetSizePx(4.0f))

        // Pixel 7/8/9 Pro density (2.625x) -> 63px
        assertEquals(63, WidgetGeometryHelper.calculateTargetSizePx(2.625f))
    }

    @Test
    fun `calculatePaddingDp produces exact paddings for standard widget containers`() {
        // Google G logo: container 30dp -> padding 3dp -> optical size 24dp
        val gLogoPadding = WidgetGeometryHelper.calculatePaddingDp(30f)
        assertEquals(3f, gLogoPadding, 0.001f)

        // Mic and Lens buttons: container 40dp -> padding 8dp -> optical size 24dp
        val buttonPadding = WidgetGeometryHelper.calculatePaddingDp(40f)
        assertEquals(8f, buttonPadding, 0.001f)

        // Perfect 24dp container -> 0dp padding
        val exactPadding = WidgetGeometryHelper.calculatePaddingDp(24f)
        assertEquals(0f, exactPadding, 0.001f)

        // Sub-24dp container is coerced to 0dp
        val smallPadding = WidgetGeometryHelper.calculatePaddingDp(20f)
        assertEquals(0f, smallPadding, 0.001f)
    }
}
