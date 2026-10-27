// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import io.github.linker2k.myembro.engine.geometry.*
import io.github.linker2k.myembro.engine.inkstitch.svg.PIXELS_PER_MM
import io.github.linker2k.myembro.engine.inkstitch.svg.Units
import org.locationtech.jts.operation.union.UnaryUnionOp

/** Coordinator-approved JTS closing of ALL original color polygons; coordinates are native 0.1 mm.
 * Radius is exactly the requested fill expansion, not a separate policy/default.
 */
internal object PictureFillMask {
    fun union(outlines:List<RedworkOutline>):PolygonRegion {
        val parts=outlines.map { outline ->
            PolygonRegion.fromPaths(outline.paths.flatMap { path ->
                SvgPath.flatten(path,tolerance=Units.mmToPixels(0.1)).map { line ->
                    Polyline(line.points.map { Point(it.x*10/PIXELS_PER_MM,it.y*10/PIXELS_PER_MM) },true)
                }
            },FillRule.EVEN_ODD).geometry
        }
        return if(parts.isEmpty()) PolygonRegion.empty() else PolygonRegion(UnaryUnionOp.union(parts))
    }

    fun closing(region:PolygonRegion,expandMm:Double):PolygonRegion {
        require(expandMm.isFinite() && expandMm>=0)
        if(expandMm==0.0 || region.isEmpty) return region
        val radius=expandMm*10
        return PolygonRegion(region.geometry.buffer(radius).buffer(-radius))
    }
}
