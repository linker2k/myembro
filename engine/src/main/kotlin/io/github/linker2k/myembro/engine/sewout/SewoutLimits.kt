// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.sewout

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.model.Bounds

/** AGENTS.md P1–P8; native coordinates are integer 0.1mm units. */
object SewoutLimits {
    const val HOOP_SAFETY_MARGIN_UNITS_0P1MM=20
    fun maxDesignWidthUnits0p1mm(hoop:HoopSpec)=hoop.width-2*HOOP_SAFETY_MARGIN_UNITS_0P1MM
    fun maxDesignHeightUnits0p1mm(hoop:HoopSpec)=hoop.height-2*HOOP_SAFETY_MARGIN_UNITS_0P1MM
    const val PEC_MIN_COORD_UNITS_0P1MM=0
    const val PEC_DIMENSION_TOLERANCE_UNITS_0P1MM=0
    const val MAX_STITCH_LENGTH_UNITS_0P1MM=120
    const val DENSITY_CELL_UNITS_0P1MM=5
    const val MAX_GENERATED_PENETRATIONS_PER_CELL=15
    const val MAX_IMPORTED_PENETRATIONS_PER_CELL=30
    const val MAX_CONSECUTIVE_POSITIONS=2
    const val MAX_ZERO_LENGTH_MOTION_COMMANDS=0
    fun hoopMaxXUnits0p1mm(hoop:HoopSpec)=hoop.width-1
    fun hoopMaxYUnits0p1mm(hoop:HoopSpec)=hoop.height-1
    fun placedFits(hoop:HoopSpec,bounds:Bounds):Boolean {
        val inset=2L*HOOP_SAFETY_MARGIN_UNITS_0P1MM
        return 2L*bounds.minX>=-hoop.width.toLong()+inset && 2L*bounds.maxX<=hoop.width.toLong()-inset &&
            2L*bounds.minY>=-hoop.height.toLong()+inset && 2L*bounds.maxY<=hoop.height.toLong()-inset
    }
    fun requirePlaced(hoop:HoopSpec,bounds:Bounds?) {
        require(bounds==null || placedFits(hoop,bounds)) { "HOOP_SAFETY_MARGIN: placed design must keep $HOOP_SAFETY_MARGIN_UNITS_0P1MM units from every hoop edge" }
    }
    fun exceedsStitchLength(dx:Long,dy:Long):Boolean {
        val limit=MAX_STITCH_LENGTH_UNITS_0P1MM.toLong()
        // Guard before squaring, including arbitrary Int coordinate extremes.
        return dx !in -limit..limit || dy !in -limit..limit || dx*dx+dy*dy>limit*limit
    }
}
