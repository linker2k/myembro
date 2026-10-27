// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import io.github.linker2k.myembro.engine.geometry.PolygonRegion
import io.github.linker2k.myembro.engine.geometry.CanonicalPolygonOrder
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.operation.union.UnaryUnionOp

/** Exact non-background pixel cells, before color cleanup/tracing. No union of color outlines.
 * Adjacent cells in each row form the same rectangle; JTS unions those rectangles, retaining holes.
 * Coordinates match RedworkPipeline's padded, centered native 0.1 mm coordinate system.
 */
internal object PictureForegroundMask {
    fun polygonize(width:Int,height:Int,foreground:BooleanArray,unitsPerPixel:Double):PolygonRegion {
        require(width>0 && height>0 && foreground.size==width*height)
        require(unitsPerPixel.isFinite() && unitsPerPixel>0)
        val factory=GeometryFactory();val cells=mutableListOf<Geometry>()
        for(y in 0 until height) {
            var x=0
            while(x<width) {
                if(!foreground[y*width+x]) { x++;continue }
                val start=x
                while(x<width && foreground[y*width+x]) x++
                cells+=factory.toGeometry(Envelope((start-width/2.0)*unitsPerPixel,
                    (x-width/2.0)*unitsPerPixel,(y-height/2.0)*unitsPerPixel,(y+1-height/2.0)*unitsPerPixel))
            }
        }
        if(cells.isEmpty()) return PolygonRegion.empty()
        return PolygonRegion(CanonicalPolygonOrder.canonicalize(UnaryUnionOp.union(cells)))
    }
}
