// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg

import io.github.linker2k.myembro.engine.inkstitch.elements.SatinParameters
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.StitchGroup
import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.model.EmbPattern
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import kotlin.math.sqrt

/** Coordinate coverage audit, not a physical thread-width model or a new stitch generator.
 * JTS buffer/difference uses finished 0.1mm needle segments, never travel jumps.
 * Allowed distance = upstream satin row spacing / 2 + worst Euclidean PEC rounding error.
 * A regular 0.4mm-spaced surface is within 0.2mm of a row before rounding. See docs/satin-coverage.md.
 */
object SatinCoverage {
    enum class Border { NONE, CLOSED_STROKE, POLYGON_RING }
    /** Existing source/topology only: a closed SVG stroke or polygon with an interior ring.
     * A hole does not establish artistic intent; this conservative exemption preserves ring-like
     * satin rather than inventing a color/width/area heuristic. */
    fun border(region: Geometry, closedSourceStroke: Boolean = false): Border = when {
        closedSourceStroke -> Border.CLOSED_STROKE
        (0 until region.numGeometries).map(region::getGeometryN)
            .filterIsInstance<org.locationtech.jts.geom.Polygon>().any { it.numInteriorRing > 0 } -> Border.POLYGON_RING
        else -> Border.NONE
    }
    val allowedDistanceMm: Double = SatinParameters().zigzag_spacing_mm / 2 + sqrt(2.0) * 0.05
    data class Audit(val beforeUncoveredMm2: Double, val fellBack: Boolean)

    fun uncoveredAreaMm2(regionMm: Geometry, groups: List<StitchGroup>): Double =
        uncoveredAreaMm2(regionMm, GeneratedPatternProcessor.fromGroups(groups, pecJumpTrims=true,
            lockAtThreadBoundaries=true, omitZeroLengthMotions=true))

    fun uncoveredAreaMm2(regionMm: Geometry, pattern: EmbPattern): Double {
        if(Thread.currentThread().isInterrupted) throw InterruptedException()
        val factory=regionMm.factory
        val lines=mutableListOf<org.locationtech.jts.geom.LineString>()
        var previous: Coordinate?=null
        for(s in pattern.stitches) {
            val point=Coordinate(s.x/10.0,s.y/10.0)
            when(s.command) {
                Command.STITCH -> {
                    previous?.let { if(!it.equals2D(point)) lines+=factory.createLineString(arrayOf(it,point)) }
                    previous=point
                }
                Command.JUMP -> previous=point // travel isn't sewn; next needle can sew from the landing
                else -> previous=null // never join across trim/stop/color change
            }
        }
        if(lines.isEmpty()) return regionMm.area
        // Established JTS default round-cap buffer; no raster, invented gap sampler or area cutoff.
        val covered=factory.createMultiLineString(lines.toTypedArray()).buffer(allowedDistanceMm)
        if(Thread.currentThread().isInterrupted) throw InterruptedException()
        return regionMm.difference(covered).area
    }
}
