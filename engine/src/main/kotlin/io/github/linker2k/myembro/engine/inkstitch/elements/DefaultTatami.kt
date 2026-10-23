// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/elements/fill_stitch.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.elements

import io.github.linker2k.myembro.engine.geometry.PolygonRegion
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.*
import io.github.linker2k.myembro.engine.inkstitch.stitches.InkAutoFill
import io.github.linker2k.myembro.engine.inkstitch.svg.PIXELS_PER_MM
import io.github.linker2k.myembro.engine.model.EmbThread
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryCollection
import org.locationtech.jts.geom.Polygon

/**
 * S4 candidate API: source-default tatami plus underlay, with no user settings.
 * Input is the existing engine PolygonRegion (0.1mm); groups use shared SVG-pixel units.
 * Reuses model-owned InkAutoFill; no second scanline/graph generator is implemented here.
 * The caller applies S1 StitchPlanBuilder once after combining all object groups.
 */
object DefaultTatami {
    private val top = InkAutoFill.Settings(angleDegrees=0.0,rowSpacing=2.5,
        stitchLength=40.0,travelLength=25.0,staggers=4,underpath=true)
    private val underlay = top.copy(angleDegrees=90.0,rowSpacing=7.5)

    fun stitchGroups(region: PolygonRegion, thread: EmbThread): List<StitchGroup> {
        if (region.isEmpty) return emptyList()
        val locks = LockStitches.fromParameters(LockStitchParameters())
        val groups = mutableListOf<StitchGroup>()
        // Keep each component's underlay directly before its top fill.
        for (part in polygons(region.geometry).sortedBy { it.envelopeInternal.minX }) {
            val component = PolygonRegion(part)
            for ((settings, tag) in listOf(underlay to "fill_underlay", top to "fill_top")) {
                for (path in InkAutoFill.paths(component,settings)) {
                    if (path.isEmpty()) continue
                    groups += StitchGroup(color=thread,stitches=path.map {
                        Stitch(it.x*PIXELS_PER_MM/10,it.y*PIXELS_PER_MM/10,setOf("tatami_fill",tag))
                    },lockStart=locks.start,lockEnd=locks.end)
                }
            }
        }
        return groups
    }

    private fun polygons(shape: Geometry): List<Polygon> = when {
        shape.isEmpty -> emptyList()
        shape is Polygon -> listOf(shape)
        shape is GeometryCollection -> (0 until shape.numGeometries).flatMap { polygons(shape.getGeometryN(it)) }
        else -> emptyList()
    }
}
