// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

import io.github.linker2k.myembro.engine.model.Bounds
import kotlin.math.ceil
import kotlin.math.floor

/** Shared editor/export inset and rounding tolerance, in millimeters. */
const val HOOP_SAFETY_MARGIN_MM = 1.0
const val HOOP_BOUNDARY_TOLERANCE_MM = 0.05

/** Rectangular sewing area in 0.1 mm. The decorative rounded hoop is not sewing geometry. */
data class SewingArea(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    /** Snap inward onto the integer stitch grid, including hoops with odd dimensions. */
    val snapBounds: Bounds get() = Bounds(ceil(left).toInt(), ceil(top).toInt(), floor(right).toInt(), floor(bottom).toInt())
    fun containsX(min: Double, max: Double): Boolean =
        min >= left - HOOP_BOUNDARY_TOLERANCE_MM * 10 && max <= right + HOOP_BOUNDARY_TOLERANCE_MM * 10
    fun containsY(min: Double, max: Double): Boolean =
        min >= top - HOOP_BOUNDARY_TOLERANCE_MM * 10 && max <= bottom + HOOP_BOUNDARY_TOLERANCE_MM * 10

    /** Bounds must come from placedPattern(): integer coordinates after roundToInt(). */
    fun contains(bounds: Bounds): Boolean = containsX(bounds.minX.toDouble(), bounds.maxX.toDouble()) &&
        containsY(bounds.minY.toDouble(), bounds.maxY.toDouble())
}

val HoopSpec.sewingArea: SewingArea get() {
    // Keep tiny/custom hoops valid; a hoop smaller than the inset has only its center usable.
    val inset = minOf(HOOP_SAFETY_MARGIN_MM * 10, minOf(width, height) / 2.0)
    return SewingArea(-width / 2.0 + inset, -height / 2.0 + inset,
        width / 2.0 - inset, height / 2.0 - inset)
}
