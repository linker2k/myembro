// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import io.github.linker2k.myembro.engine.geometry.PolygonRegion
import io.github.linker2k.myembro.engine.inkstitch.utils.Point
import org.locationtech.jts.geom.Polygon

/** Reuse the existing fixed-start, improvement-only JGraphT photo route.
 * 2026-10-28 matched PES audit against b23060f (/tmp/myembro-island-order/table.md):
 * silhouette 90: 16204.931 -> 13615.477mm jumps; color 88: 48073.308 -> 39137.527mm;
 * 91.7mm proxy: 1313.434 -> 521.065mm; v8 gate 179/179 PASS.
 * The route accepts only a lower closed-ring-anchor cost; actual PES travel is measured
 * separately because individual islands can still increase it. Color layer order is untouched.
 */
internal object PhotoIslandOrder {
    fun polygons(region: PolygonRegion): List<Polygon> {
        val polygons=(0 until region.geometry.numGeometries).map { region.geometry.getGeometryN(it) as Polygon }
        val anchors=polygons.map { polygon -> polygon.exteriorRing.coordinates.map { Point(it.x,it.y) } }
        return PhotoOutline.route(anchors).paths.map { polygons[anchors.indexOf(it)] }
    }
}
