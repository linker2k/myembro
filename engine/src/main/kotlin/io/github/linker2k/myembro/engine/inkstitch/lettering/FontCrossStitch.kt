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
import org.locationtech.jts.operation.distance.DistanceOp
import io.github.linker2k.myembro.engine.inkstitch.utils.Point as SvgPoint

/** do_cross_stitch / _prepare_cross_stitch_shape. Like FontTatami, the last shape has no ending point. */
internal object FontCrossStitch {
    /** Test-only observer of every cross stitch call (oracle export). */
    @Volatile internal var trace: ((org.locationtech.jts.geom.Geometry, CrossStitch.Settings, FillPoint?, FillPoint?, List<List<FillPoint>>) -> Unit)? = null

    fun stitchGroups(element: InkFill, px: (Point) -> SvgPoint, attrs: Map<String, String>,
        color: EmbThread, previous: Stitch?): List<StitchGroup> {
        val p = FillParameters.fromSvgAttributes(attrs)
        if (!p.fill) return emptyList()
        val settings = CrossStitch.Settings.fromAttributes(attrs)
        val region = PolygonRegion.fromPaths(element.paths.map { path ->
            Polyline(path.points.map { px(it).let { q -> Point(q.x, q.y) } }, path.closed)
        }, element.rule)
        if (region.isEmpty) return emptyList()
        val shape = FillGeometry.makeValid(region.geometry)
        // shrink_or_grow_shape: shapely buffer() default is 16 segments per quadrant.
        val grown = if (p.expandPx == 0.0) shape else shape.buffer(p.expandPx, 16).let { if (it.isEmpty) shape else it }
        val start0 = previous?.let { FillPoint(it.x, it.y) }
        val shapes = FillGeometry.polygons(FillGeometry.makeValid(grown)).sortedBy { part ->
            start0?.let { part.distance(FillGeometry.point(it)) } ?: part.envelopeInternal.minX
        }
        val locks = LockStitches.fromParameters(LockStitchParameters.fromAttributes(attrs))
        val groups = mutableListOf<StitchGroup>()
        var start = start0
        for ((index, part) in shapes.withIndex()) {
            val end = shapes.getOrNull(index + 1)?.let { next -> DistanceOp.nearestPoints(part, next)[0].let { FillPoint(it.x, it.y) } }
            val cycles = CrossStitch.generate(part, settings, start, end)
            trace?.invoke(part, settings, start, end, cycles)
            for (cycle in cycles) {
                if (cycle.isEmpty()) continue
                groups += StitchGroup(color, cycle.map { Stitch(it.x, it.y, it.tags) }, lockStart = locks.start, lockEnd = locks.end,
                    minStitchLengthMm = p.min_stitch_length_mm, minJumpLengthMm = p.min_jump_stitch_length_mm,
                    forceLockStitches = p.force_lock_stitches)
                start = cycle.last()
            }
        }
        if (groups.isNotEmpty()) groups[groups.lastIndex] = groups.last().copy(trimAfter = p.trim_after, stopAfter = p.stop_after)
        return groups
    }
}
