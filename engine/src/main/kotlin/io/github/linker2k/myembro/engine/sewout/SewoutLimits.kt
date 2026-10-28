// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.sewout

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.model.Bounds

/** AGENTS.md P1–P8; native coordinates are integer 0.1mm units. */
object SewoutLimits {
    const val HOOP_SAFETY_MARGIN_UNITS_0P1MM=1
    fun maxDesignWidthUnits0p1mm(hoop:HoopSpec)=hoopMaxXUnits0p1mm(hoop)
    fun maxDesignHeightUnits0p1mm(hoop:HoopSpec)=hoopMaxYUnits0p1mm(hoop)
    const val PEC_MIN_COORD_UNITS_0P1MM=0
    const val PEC_DIMENSION_TOLERANCE_UNITS_0P1MM=0
    const val MAX_STITCH_LENGTH_UNITS_0P1MM=120
    const val DENSITY_CELL_UNITS_0P1MM=5
    // User-approved 2026-10-28: corpus p99=15/max=25; failed sewout=38. See AGENTS P7.
    const val DENSITY_WARNING_PENETRATIONS_PER_CELL=16
    const val MAX_PENETRATIONS_PER_CELL=30
    const val MAX_GENERATED_PENETRATIONS_PER_CELL=MAX_PENETRATIONS_PER_CELL
    const val MAX_IMPORTED_PENETRATIONS_PER_CELL=MAX_PENETRATIONS_PER_CELL
    const val MAX_CONSECUTIVE_POSITIONS=2
    const val MAX_ZERO_LENGTH_MOTION_COMMANDS=0
    fun hoopMaxXUnits0p1mm(hoop:HoopSpec)=hoop.width-HOOP_SAFETY_MARGIN_UNITS_0P1MM
    fun hoopMaxYUnits0p1mm(hoop:HoopSpec)=hoop.height-HOOP_SAFETY_MARGIN_UNITS_0P1MM
    fun placedFits(hoop:HoopSpec,bounds:Bounds):Boolean {
        val left=-(hoop.width.toLong()/2);val top=-(hoop.height.toLong()/2)
        return bounds.minX>=left && bounds.maxX<=left+hoopMaxXUnits0p1mm(hoop) &&
            bounds.minY>=top && bounds.maxY<=top+hoopMaxYUnits0p1mm(hoop)
    }

    fun requirePlaced(hoop:HoopSpec,bounds:Bounds?) {
        require(bounds==null || placedFits(hoop,bounds)) { "HOOP_SAFETY_MARGIN: placed design exceeds integer hoop indices 0..size-1" }
    }
    fun exceedsStitchLength(dx:Long,dy:Long):Boolean {
        val limit=MAX_STITCH_LENGTH_UNITS_0P1MM.toLong()
        // Guard before squaring, including arbitrary Int coordinate extremes.
        return dx !in -limit..limit || dy !in -limit..limit || dx*dx+dy*dy>limit*limit
    }
}
