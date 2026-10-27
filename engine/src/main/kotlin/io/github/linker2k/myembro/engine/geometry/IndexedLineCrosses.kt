// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.geometry

import org.locationtech.jts.algorithm.RobustLineIntersector
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.index.strtree.STRtree

/** DE-9IM line/line crosses: interior intersection dimension is exactly zero.
 * Robust segment intersections come from classic JTS; no approximation, epsilon or simplification.
 * Never return true early: a later overlap/vertex event may require classic graph labeling.
 * Multi-segment candidates, collapsed lines, overlap and vertex events use the exact classic oracle.
 * See docs/research/indexed-line-crosses.md for the boundary rule and oracle validation. */
internal class IndexedLineCrosses(private val row: LineString) {
    private data class Segment(val a: Coordinate,val b: Coordinate)
    private val points=row.coordinates
    private val index=STRtree()
    private val closed=points.isNotEmpty() && points.first().equals2D(points.last())
    private var nonempty=false

    init {
        for(i in 1 until points.size) {
            val a=points[i-1];val b=points[i]
            if(a.equals2D(b)) continue // Same repeated-coordinate removal as classic GeometryGraph.
            index.insert(Envelope(a,b),Segment(a,b));nonempty=true
        }
        index.build()
    }

    private fun result(candidate: LineString,value: Boolean,path: String): Boolean {
        LineCrossesTrace.emit(row,candidate,value,path)
        return value
    }
    private fun classic(candidate: LineString,path: String) = result(candidate,LegacyCrosses.test(row,candidate),path)

    fun test(candidate: LineString): Boolean {
        if(candidate.numPoints!=2) return classic(candidate,"classic:complex")
        if(!nonempty) return classic(candidate,"classic:collapsed")
        val other=candidate.coordinates
        if(other.size<2 || other[0].equals2D(other[1])) return classic(candidate,"classic:collapsed")
        val otherClosed=other.first().equals2D(other.last())
        fun boundary(p: Coordinate,line: Array<Coordinate>,isClosed: Boolean) =
            !isClosed && (p.equals2D(line.first()) || p.equals2D(line.last()))
        val intersector=RobustLineIntersector()
        var interiorPoint=false
        for(i in 1 until other.size) {
            val a=other[i-1];val b=other[i]
            if(a.equals2D(b)) continue
            for(item in index.query(Envelope(a,b))) {
                val segment=item as Segment
                intersector.computeIntersection(segment.a,segment.b,a,b)
                val count=intersector.intersectionNum
                if(count==0) continue
                // Positive overlap is dimension 1 for simple lines. For non-simple inputs classic JTS
                // can label coincident edges differently; preserve its exact result in this rare case.
                if(count==2 && !intersector.getIntersection(0).equals2D(intersector.getIntersection(1)))
                    return classic(candidate,"classic:overlap")
                val p=intersector.getIntersection(0)
                // Endpoint/vertex events require classic graph labels (including non-simple lines).
                if(!intersector.isProper || boundary(p,points,closed) || boundary(p,other,otherClosed))
                    return classic(candidate,"classic:vertex")
                interiorPoint=true
            }
        }
        return result(candidate,interiorPoint,"segments")
    }
}
