// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import io.github.linker2k.myembro.engine.geometry.Point
import io.github.linker2k.myembro.engine.geometry.PolygonRegion
import org.locationtech.jts.algorithm.MinimumDiameter
import org.locationtech.jts.geom.Polygon

/** Conservative app admission policy, NOT a TTF-to-satin decomposition algorithm.
 * Only hole-free, essentially rectangular constant-width components qualify; all others auto-fill.
 * Skeleton-based OutlineColumns is intentionally absent from production generation.
 */
internal object OutlineSatinValidation {
    fun rails(region: PolygonRegion): Pair<List<Point>, List<Point>>? {
        val geometry = region.geometry
        val polygon = (if (geometry.numGeometries == 1) geometry.getGeometryN(0) else geometry) as? Polygon ?: return null
        if (polygon.numInteriorRing != 0) return null
        val rectangle = MinimumDiameter.getMinimumRectangle(polygon)
        if (rectangle !is Polygon || rectangle.area <= 0 || polygon.symDifference(rectangle).area > rectangle.area * 0.005) return null
        val p = rectangle.exteriorRing.coordinates.take(4).map { Point(it.x / 10, it.y / 10) }
        if (p.size != 4) return null
        val a = p[0].distance(p[1]); val b = p[1].distance(p[2])
        if (minOf(a, b) < 0.6 || maxOf(a, b) < minOf(a, b) * 1.5) return null
        return if (a >= b) listOf(p[0], p[1]) to listOf(p[3], p[2]) else listOf(p[1], p[2]) to listOf(p[0], p[3])
    }
}
