// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/elements/fill_stitch.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.lettering

import io.github.linker2k.myembro.engine.geometry.Point
import io.github.linker2k.myembro.engine.geometry.Polyline
import io.github.linker2k.myembro.engine.geometry.PolygonRegion
import io.github.linker2k.myembro.engine.inkstitch.elements.FillParameters
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.*
import io.github.linker2k.myembro.engine.inkstitch.stitches.*
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.engine.inkstitch.utils.Point as SvgPoint

/** Element adapter only. All fill/underlay geometry and routing are model-owned staging ports. */
internal object FontTatami {
    fun stitchGroups(element: InkFill, px: (Point) -> SvgPoint, attrs: Map<String,String>,
        color: EmbThread, previous: Stitch?): List<StitchGroup> {
        val p=FillParameters.fromSvgAttributes(attrs)
        if (!p.fill) return emptyList()
        require(p.fill_method in setOf("tatami_fill", "auto_fill")) { "Unsupported font fill method: ${p.fill_method}" }
        // PolygonRegion is used for its SVG fill rule; numbers remain SVG pixels throughout.
        val region=PolygonRegion.fromPaths(element.paths.map { path ->
            Polyline(path.points.map { px(it).let { q -> Point(q.x,q.y) } }, path.closed)
        },element.rule)
        if (region.isEmpty) return emptyList()
        val shape=FillGeometry.makeValid(region.geometry)
        val locks=LockStitches.fromParameters(LockStitchParameters.fromAttributes(attrs))
        var start=previous?.let { FillPoint(it.x,it.y) }
        val paths=mutableListOf<List<FillPoint>>()
        val components=FillGeometry.polygons(shape).sortedBy { part ->
            start?.let { part.distance(FillGeometry.point(it)) } ?: part.envelopeInternal.minX
        }
        for ((index, component) in components.withIndex()) {
            val end=components.getOrNull(index+1)?.let { next ->
                org.locationtech.jts.operation.distance.DistanceOp.nearestPoints(component,next)[0]
                    .let { FillPoint(it.x,it.y) }
            }
            for (layer in FillUnderlay.generate(component,p,start)) {
                paths+=layer.stitches
                start=layer.stitches.lastOrNull() ?: start
            }
            for (expanded in FillGeometry.polygons(FillGeometry.shrinkOrGrow(component,p.expandPx))) {
                val top=TatamiFill.fill(expanded,p.angleRadians,p.rowSpacingPx,p.endRowSpacingPx,
                    p.maxStitchLengthPx,p.runningLengthPx,p.runningTolerancePx,p.staggers,p.skip_last,start,end,
                    underpath=p.underpath,gapFillRows=p.gap_fill_rows,
                    pullCompensationPx=p.pullCompensationPx,pullCompensationPercent=p.pullCompensationRatio)
                    .map { it.tagged("tatami_fill", "fill_top") }
                paths+=top
                start=top.lastOrNull() ?: start
            }
        }
        val groups=mutableListOf<StitchGroup>()
        for (path in paths) {
            var current=mutableListOf<Stitch>()
            fun flush() {
                if(current.isEmpty()) return
                groups+=StitchGroup(color,current,lockStart=locks.start,lockEnd=locks.end,
                    minStitchLengthMm=p.min_stitch_length_mm,minJumpLengthMm=p.min_jump_stitch_length_mm,
                    forceLockStitches=p.force_lock_stitches)
                current=mutableListOf()
            }
            for (q in path) {
                if("jump" in q.tags) flush()
                current+=Stitch(q.x,q.y,q.tags - "jump")
            }
            flush()
        }
        if(groups.isNotEmpty()) groups[groups.lastIndex]=groups.last().copy(trimAfter=p.trim_after,stopAfter=p.stop_after)
        return groups
    }
}
