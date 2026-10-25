// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.geometry

import org.locationtech.jts.algorithm.Orientation
import org.locationtech.jts.geom.*
import org.locationtech.jts.io.WKBWriter
import kotlin.test.*

class CanonicalPolygonOrderTest {
    private val factory=GeometryFactory()
    private fun ring(x:Double,y:Double,size:Double,rotation:Int,reverse:Boolean=false):LinearRing {
        val raw=listOf(Coordinate(x,y),Coordinate(x+size,y),Coordinate(x+size,y+size),Coordinate(x,y+size))
            .let { if(reverse)it.reversed() else it }
        return factory.createLinearRing(Array(5) { raw[(it+rotation)%4].copy() })
    }
    @Test fun rotationsHolesAndPolygonListsHaveOneRepresentationWithoutChangingOrientation() {
        var baseline:ByteArray?=null
        for(rotation in 0..3) for(swapHoles in listOf(false,true)) for(swapParts in listOf(false,true)) {
            val holes=listOf(ring(1.0,1.0,1.0,rotation,true),ring(5.0,5.0,1.0,(rotation+1)%4,true))
                .let { if(swapHoles)it.reversed() else it }
            val a=factory.createPolygon(ring(0.0,0.0,10.0,rotation),holes.toTypedArray())
            val b=factory.createPolygon(ring(20.0,0.0,10.0,(rotation+2)%4))
            val input=factory.createMultiPolygon((if(swapParts)listOf(b,a)else listOf(a,b)).toTypedArray())
            val output=CanonicalPolygonOrder.canonicalize(input)
            assertTrue(input.equalsTopo(output))
            assertEquals(input.area.toBits(),output.area.toBits())
            for(i in 0 until output.numGeometries) {
                val p=output.getGeometryN(i) as Polygon
                assertTrue(Orientation.isCCW(p.exteriorRing.coordinates))
                for(j in 0 until p.numInteriorRing)assertFalse(Orientation.isCCW(p.getInteriorRingN(j).coordinates))
            }
            val bytes=WKBWriter().write(output)
            baseline?.let { assertContentEquals(it,bytes) } ?: run { baseline=bytes }
            assertContentEquals(bytes,WKBWriter().write(CanonicalPolygonOrder.canonicalize(output)))
        }
    }
    @Test fun preservesClockwiseInputAndEveryVertexBit() {
        val input=factory.createPolygon(ring(-3.25,7.5,2.125,2,true))
        val output=CanonicalPolygonOrder.canonicalize(input) as Polygon
        assertFalse(Orientation.isCCW(output.exteriorRing.coordinates))
        fun bits(p:Polygon)=p.exteriorRing.coordinates.dropLast(1).map { it.x.toBits() to it.y.toBits() }.toSet()
        assertEquals(bits(input),bits(output))
        assertEquals(-3.25,output.exteriorRing.getCoordinateN(0).x)
    }
}
