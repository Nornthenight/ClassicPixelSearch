package com.pixel.classicsearch

import kotlin.math.roundToInt

/**
 * Optical geometry and alignment helper ensuring strict adherence to the 24dp visual standard.
 */
object WidgetGeometryHelper {

    /**
     * Non-negotiable visual optical standard: all action buttons and the Google G logo
     * must project an optical bounding box of exactly 24dp.
     */
    const val TARGET_ICON_OPTICAL_SIZE_DP = 24f

    /**
     * Converts the 24dp optical target to pixel dimension based on device display metrics.
     */
    fun calculateTargetSizePx(density: Float): Int {
        return (TARGET_ICON_OPTICAL_SIZE_DP * density).roundToInt()
    }

    /**
     * Computes the uniform padding (in dp) required for a given container dimension
     * to constrain child icon content to exactly visualSizeDp.
     */
    fun calculatePaddingDp(containerSizeDp: Float, visualSizeDp: Float = TARGET_ICON_OPTICAL_SIZE_DP): Float {
        return ((containerSizeDp - visualSizeDp) / 2f).coerceAtLeast(0f)
    }
}
