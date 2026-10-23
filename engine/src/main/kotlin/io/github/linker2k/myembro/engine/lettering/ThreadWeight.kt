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
    /** Conservative heuristic scaled from the existing 40wt threshold; not a sew-out guarantee. */
    val minimumFeatureMm: Double get() = 1.2 * (spacingMm / 0.4)
    val tightGapMm: Double get() = spacingMm / 0.4
    val minimumFillHeightMm: Double get() = 6.0 * (spacingMm / 0.4)
}
