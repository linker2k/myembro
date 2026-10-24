// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

import io.github.linker2k.myembro.engine.model.Bounds
import io.github.linker2k.myembro.engine.sewout.SewoutLimits
import kotlin.math.ceil
import kotlin.math.floor

/** Shared editor/export inset and rounding tolerance, in millimeters. */
const val HOOP_SAFETY_MARGIN_MM = SewoutLimits.HOOP_SAFETY_MARGIN_UNITS_0P1MM / 10.0
const val HOOP_BOUNDARY_TOLERANCE_MM = SewoutLimits.PEC_DIMENSION_TOLERANCE_UNITS_0P1MM / 10.0

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
    val left=-(width/2).toDouble();val top=-(height/2).toDouble()
    return SewingArea(left,top,left+SewoutLimits.hoopMaxXUnits0p1mm(this),top+SewoutLimits.hoopMaxYUnits0p1mm(this))
}
