// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.geometry

import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.io.WKTReader
import org.locationtech.jts.operation.relate.RelateOp
import java.util.Random
import kotlin.test.*

class IndexedLineCrossesTest {
    private val factory=GeometryFactory()
    private fun compare(a: LineString,b: LineString,label: String) {
        val expected=RelateOp.relate(a,b).isCrosses(1,1)
        val actual=IndexedLineCrosses(a).test(b)
        assertEquals(expected,actual,"$label\n$a\n$b")
        assertEquals(RelateOp.relate(b,a).isCrosses(1,1),IndexedLineCrosses(b).test(a),"reverse $label\n$a\n$b")
    }
    @Test fun endpointOverlapTjunctionSelfTouchAndClosedMod2Boundaries() {
        val reader=WKTReader()
        val inputs=listOf("LINESTRING EMPTY","LINESTRING(0 0,0 0)","LINESTRING(0 0,10 0)",
            "LINESTRING(5 0,5 10)","LINESTRING(5 -5,5 5)","LINESTRING(10 0,20 0)",
            "LINESTRING(5 0,15 0)","LINESTRING(0 0,10 10,0 10,10 0)",
            "LINESTRING(0 0,10 0,5 -5,5 5,0 0)","LINESTRING(0 0,10 0,10 10,0 0)",
            "LINESTRING(0 0,0 0,5 5,10 0)","LINESTRING(0 0,10 0,0 0)",
            "LINESTRING(0 0,5 0,5 5,0 0)").map { reader.read(it) as LineString }
        for((i,a) in inputs.withIndex()) for((j,b) in inputs.withIndex()) compare(a,b,"degenerate $i/$j")
        println("SEGMENT_CROSSES_DEGENERATE pairs=${inputs.size*inputs.size} reversed=true mismatches=0")
    }
    @Test fun hundredFiftyThousandSeededRandomAndNearDegeneratePairsMatchClassicRelation() {
        val rng=Random(0xC2055L)
        fun line(n: Int,scale: Double,offset: Double): LineString = factory.createLineString(Array(n) { i ->
            val x=(rng.nextInt(41)-20)*scale+offset
            val y=if(rng.nextInt(5)==0) offset else (rng.nextInt(41)-20)*scale+offset
            Coordinate(x,y)
        })
        val paths=sortedMapOf<String,Int>()
        LineCrossesTrace.observe({ _,_,_,path -> paths[path]=(paths[path] ?: 0)+1 }) {
        repeat(150000) { case ->
            val scale=when(case%4) { 0 -> 1e-6; 1 -> 1.0; 2 -> 1000.0; else -> 0.1 }
            val offset=if(case%7==0) 1000000.0 else 0.0
            var a=line(2+rng.nextInt(7),scale,offset)
            var b=line(if(case%5==0) 2+rng.nextInt(7) else 2,scale,offset)
            val ac=a.coordinates;val bc=b.coordinates
            when(case%10) {
                0 -> bc[0]=ac.last().copy() // endpoint contact
                1 -> { bc[0]=ac[0].copy();bc[1]=ac[1].copy() } // positive overlap
                2 -> { ac[ac.lastIndex]=ac[0].copy();bc[bc.lastIndex]=bc[0].copy() } // closed boundary
                3 -> { ac[1]=ac[0].copy();bc[1]=bc[0].copy() } // repeated/collapsed segment
                4 -> { bc[0]=ac[ac.size/2].copy() } // T/self-vertex touch
                5 -> if(bc.size>2) { bc[1]=ac[0].copy();bc[bc.lastIndex]=ac[0].copy() }
                6 -> { bc[0]=Coordinate(ac[0].x,Math.nextUp(ac[0].y)) }
            }
            a=factory.createLineString(ac);b=factory.createLineString(bc)
            compare(a,b,"random $case")
        }
        }
        println("SEGMENT_CROSSES_PATHS $paths")
        println("SEGMENT_CROSSES_RANDOM seed=794709 pairs=150000 reversed=true comparisons=300000 mismatches=0")
    }
}
