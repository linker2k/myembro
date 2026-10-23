// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

/**
 * Digitizing presets. [diameterMm] is the nominal thread diameter and [floorScale] the factor on
 * FontQuality's detail floors; both are practice values, not machine specifications
 * (docs/lettering.md).
 */
enum class ThreadWeight(
    val wt: Int, val spacingMm: Double, private val smallSpacingMm: Double, val diameterMm: Double, val floorScale: Double,
) {
    WT40(40, 0.40, 0.60, 0.40, 1.0),
    WT50(50, 0.35, 0.50, 0.35, 0.9),
    WT60(60, 0.30, 0.30, 0.30, 0.8);

    fun spacingForHeight(heightMm: Double): Double {
        require(heightMm.isFinite() && heightMm > 0)
        return if (heightMm < 7.0) smallSpacingMm else spacingMm
    }
    /** Spacing relative to 40wt (1.0 / 0.875 / 0.75). */
    val featureScale: Double get() = spacingMm / 0.4
    /** Narrowest satin column FontQuality accepts for this thread. */
    val minimumFeatureMm: Double get() = FontQuality.satinMinMm(this)
    val minimumFillHeightMm: Double get() = 6.0 * featureScale
}
