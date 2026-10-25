// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.geometry

import org.locationtech.jts.geom.*

/** Representation ordering only: rotate closed rings without reversing them or changing vertices.
 * JTS Polygonizer traverses identity-hashed edges; stabilize that adapter artifact after union.
 * See docs/agent/noto-fill-order-fix.md for the comparison with Ink/Stitch/Shapely.
 */
internal object CanonicalPolygonOrder {
    private fun compare(a:Coordinate,b:Coordinate):Int =
        java.lang.Double.compare(a.x,b.x).takeIf { it!=0 } ?: java.lang.Double.compare(a.y,b.y)

    private fun ring(ring:LineString):LinearRing {
        val c=ring.coordinates
        if(c.isEmpty())return ring.factory.createLinearRing(c)
        val size=c.size-1
        fun rotation(a:Int,b:Int):Int {
            for(i in 0 until size) { val result=compare(c[(a+i)%size],c[(b+i)%size]);if(result!=0)return result }
            return 0
        }
        var first=0
        for(i in 1 until size) {
            val comparison=compare(c[i],c[first])
            if(comparison<0 || comparison==0 && rotation(i,first)<0)first=i
        }
        return ring.factory.createLinearRing(Array(size+1) { c[(first+it)%size].copy() })
    }
    private fun compareRings(a:LineString,b:LineString):Int {
        val x=a.coordinates;val y=b.coordinates
        for(i in 0 until minOf(x.size,y.size)) { val result=compare(x[i],y[i]);if(result!=0)return result }
        return x.size.compareTo(y.size)
    }
    private fun polygon(p:Polygon):Polygon = p.factory.createPolygon(ring(p.exteriorRing),
        (0 until p.numInteriorRing).map { ring(p.getInteriorRingN(it)) }.sortedWith(::compareRings).toTypedArray())
    private fun comparePolygons(a:Polygon,b:Polygon):Int {
        val exterior=compareRings(a.exteriorRing,b.exteriorRing);if(exterior!=0)return exterior
        for(i in 0 until minOf(a.numInteriorRing,b.numInteriorRing)) {
            val result=compareRings(a.getInteriorRingN(i),b.getInteriorRingN(i));if(result!=0)return result
        }
        return a.numInteriorRing.compareTo(b.numInteriorRing)
    }
    fun polygons(parts:List<Polygon>):List<Polygon> = parts.map(::polygon).sortedWith(::comparePolygons)
    fun canonicalize(shape:Geometry):Geometry = when(shape) {
        is Polygon -> polygon(shape)
        is MultiPolygon -> shape.factory.createMultiPolygon((0 until shape.numGeometries)
            .map { shape.getGeometryN(it) as Polygon }.let(::polygons).toTypedArray())
        else -> error("Expected polygonal geometry")
    }
}
