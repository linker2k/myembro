// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

/** Digitizing presets, not physical thread diameters. Sources/limits: docs/lettering.md. */
enum class ThreadWeight(val wt: Int, val spacingMm: Double, private val smallSpacingMm: Double) {
    WT40(40, 0.40, 0.60),
    WT50(50, 0.35, 0.50),
    WT60(60, 0.30, 0.30);

    fun spacingForHeight(heightMm: Double): Double {
        require(heightMm.isFinite() && heightMm > 0)
        return if (heightMm < 7.0) smallSpacingMm else spacingMm
    }
    /**
     * Factor applied to [FontQuality]'s 40wt stroke, gap and loop limits (1.0 / 0.875 / 0.75).
     * Conservative heuristic scaled from the 40wt thresholds; not a sew-out guarantee.
     */
    val featureScale: Double get() = spacingMm / 0.4
    val minimumFeatureMm: Double get() = FontQuality.INK_MIN_MM * featureScale
    val minimumFillHeightMm: Double get() = 6.0 * featureScale
}
