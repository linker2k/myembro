// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/elements/utils/stroke_to_satin.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
// Join style: lib/svg/styles.py; coordinate rounding: lib/utils/geometry.py:remove_duplicate_points (same commit).
package io.github.linker2k.myembro.engine.inkstitch.elements.utils

import io.github.linker2k.myembro.engine.inkstitch.elements.SatinColumn
import io.github.linker2k.myembro.engine.inkstitch.elements.SatinParameters
import io.github.linker2k.myembro.engine.inkstitch.svg.PIXELS_PER_MM
import io.github.linker2k.myembro.engine.inkstitch.utils.Point
import io.github.linker2k.myembro.engine.model.EmbThread
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.linearref.LengthIndexedLine
import org.locationtech.jts.operation.buffer.BufferParameters
import org.locationtech.jts.operation.buffer.OffsetCurve
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.*

/** Explicit constant-width path conversion, in SVG px. Does not infer widths or select a stitch style. */
object StrokeToSatin {
    data class Style(val joinStyle: Int = BufferParameters.JOIN_MITRE, val mitreLimit: Double = 5.0,
        val quadSegments: Int = 16) {
        init { require(joinStyle in 1..3 && mitreLimit.isFinite() && mitreLimit>0 && quadSegments>0) }
        companion object {
            fun fromSvg(join: String? = null, miterLimit: Double = 4.0) = when(join) {
                "miter" -> Style(mitreLimit=miterLimit)
                "round" -> Style(joinStyle=BufferParameters.JOIN_ROUND)
                "bevel" -> Style(joinStyle=BufferParameters.JOIN_BEVEL)
                else -> Style()
            }
        }
    }
    data class Section(val left: List<Point>, val right: List<Point>, val rungs: List<Pair<Point,Point>>) {
        fun column(parameters: SatinParameters = SatinParameters(), color: EmbThread? = null) =
            SatinColumn(left,right,parameters,rungs.map { SatinColumn.Rung(it.first,it.second) },color)
    }
    private val factory=GeometryFactory()
    private fun line(path:List<Point>)=factory.createLineString(path.map { Coordinate(it.x,it.y) }.toTypedArray())
    private fun point(c:Coordinate)=Point(c.x,c.y)
    private fun points(path:LineString)=path.coordinates.map(::point)
    private fun at(path:LineString, fraction:Double)=point(LengthIndexedLine(path).extractPoint(fraction.coerceIn(0.0,1.0)*path.length))

    fun fixLoop(path:List<Point>):List<Point> {
        if(path.size<2 || path.first()!=path.last()) return path
        val midpoint=(path[0]+path[1])/2.0
        return listOf(midpoint)+path.drop(1)+listOf(path.first(),midpoint)
    }
    private fun clean(path:List<Point>):List<Point> {
        fun round(x:Double)=BigDecimal(x).setScale(4,RoundingMode.HALF_EVEN).toDouble()
        return path.map { Point(round(it.x),round(it.y)) }.fold(mutableListOf()) { out,q ->
            if(out.lastOrNull()!=q) out+=q;out
        }
    }
    fun convertPathToSatin(path:List<Point>, strokeWidth:Double, style:Style=Style(),
        rungsAtNodes:Boolean=false):Section? {
        require(strokeWidth.isFinite() && strokeWidth>0)
        val cleaned=clean(fixLoop(path))
        if(cleaned.size<2) return null
        return sections(cleaned,strokeWidth,style,rungsAtNodes,0).reduceOrNull(::merge)
    }
    private fun sections(path:List<Point>,width:Double,style:Style,nodes:Boolean,depth:Int):List<Section> {
        val shape=line(path)
        // Every descendant of a path shorter than 1px also fails upstream's endpoint test.
        // Prune that provably empty recursion without changing any returned sections.
        if(shape.length<1.0) return emptyList()
        if(path.first().distance(path.last())>=1.0) {
            val left=OffsetCurve.getCurve(shape,-width/2,style.quadSegments,style.joinStyle,style.mitreLimit)
            val right=OffsetCurve.getCurve(shape,width/2,style.quadSegments,style.joinStyle,style.mitreLimit)
            if(left is LineString && right is LineString) {
                return listOf(Section(points(left),points(right),generateRungs(shape,width,left,right,nodes)))
            }
        }
        if(depth>=20) return emptyList()
        val indexed=LengthIndexedLine(shape)
        return listOf(indexed.extractLine(0.0,shape.length/2),indexed.extractLine(shape.length/2,shape.length))
            .flatMap { sections(points(it as LineString),width,style,nodes,depth+1) }
    }
    /** get_scores: NumPy int32 accumulation and Python round-to-even bucket positions. */
    fun scores(path:List<Point>):IntArray {
        val result=IntArray(101)
        val length=line(path).length
        var previous:Point?=null
        var distance=0.0
        for((a,b) in path.zipWithNext()) {
            val direction=(b-a).unit()
            previous?.let {
                val angle=Math.toDegrees(acos(it.dot(direction).coerceIn(-1.0,1.0)))
                val bucket=Math.rint(distance/length*100).toInt()
                result[bucket]=(result[bucket].toDouble()+angle*angle).toLong().toInt()
            }
            distance+=a.distance(b);previous=direction
        }
        return result
    }
    private fun generateRungs(path:LineString,width:Double,left:LineString,right:LineString,nodes:Boolean):List<Pair<Point,Point>> {
        val raw=scores(points(path));val kernel=intArrayOf(1,2,4,8,16,8,4,2,1)
        val smooth=LongArray(101) { i -> kernel.indices.sumOf { j -> raw.getOrElse(i+j-4) { 0 }.toLong()*kernel[j] } }
        fun sign(x:Long)=x.compareTo(0L)
        val locations=(1..99).filter { sign(smooth[it+1]-smooth[it])-sign(smooth[it]-smooth[it-1])>0 }
            .map(Int::toDouble).toMutableList()
        if(nodes) {
            val indexed=LengthIndexedLine(path)
            locations+=path.coordinates.map { indexed.project(it)/path.length*100 }
        }
        var selected=locations.filter { it!=0.0 && it!=100.0 }.distinct().sorted()
        if(selected.isEmpty()) selected=listOf(50.0)
        val rungs=mutableListOf<Pair<Point,Point>>()
        var last:Point?=null
        for(location in selected) {
            val fraction=location/100
            val center=at(path,fraction)
            if(last?.distance(center)?.let { it<(if(nodes) 1 else 2)*PIXELS_PER_MM }==true) continue
            last=center
            val tangent=(at(path,fraction+0.001)-center).unit()
            val normal=Point(-tangent.y,tangent.x)
            val offset=normal*(width/2)*1.2
            val rung=(center+offset) to (center-offset)
            val shape=line(listOf(rung.first,rung.second))
            if(shape.intersection(left) is org.locationtech.jts.geom.Point &&
                shape.intersection(right) is org.locationtech.jts.geom.Point) rungs+=rung
        }
        return rungs
    }
    /** set_first_node + roll_linear_ring, for the extension's closed-path caller. */
    fun setFirstNode(path:List<Point>,strokeWidth:Double):List<Point> {
        require(strokeWidth.isFinite() && strokeWidth>0)
        require(path.size>=4 && path.first()==path.last()) { "Expected a closed ring" }
        val ring=factory.createLinearRing(path.map { Coordinate(it.x,it.y) }.toTypedArray())
        val boundary=org.locationtech.jts.operation.buffer.BufferOp.bufferOp(ring,strokeWidth/2,16).boundary
        val indexed=LengthIndexedLine(ring)
        for((a,b) in path.zipWithNext()) {
            val length=a.distance(b)
            if(length==0.0) continue
            val center=(a+b)/2.0
            val direction=(b-a)/length
            val offset=Point(-direction.y,direction.x)*((strokeWidth+0.001)/2)
            val intersection=line(listOf(center-offset,center+offset)).intersection(boundary)
            if(intersection is org.locationtech.jts.geom.MultiPoint && intersection.numGeometries==2) {
                val distance=indexed.project(Coordinate(center.x,center.y))
                if(distance==0.0 || distance==ring.length) return path
                return points(indexed.extractLine(distance,ring.length) as LineString)+
                    points(indexed.extractLine(0.0,distance) as LineString).drop(1)
            }
        }
        return path
    }
    /** Extension.filtered_rungs: keep exactly two point intersections with the combined rails. */
    fun filteredRungs(section:Section):Section {
        val rails=factory.createMultiLineString(arrayOf(line(section.left),line(section.right)))
        return section.copy(rungs=section.rungs.filter { (a,b) ->
            val intersection=line(listOf(a,b)).intersection(rails)
            intersection is org.locationtech.jts.geom.MultiPoint && intersection.numGeometries==2
        })
    }

    private fun merge(a:Section,b:Section):Section {
        if(b.left.size<2 || b.right.size<2) return a
        val center=(b.left.first()+b.right.first())/2.0
        val rung=(center+(b.left.first()-center)*1.2) to (center+(b.right.first()-center)*1.2)
        return Section(a.left+b.left.drop(1),a.right+b.right.drop(1),a.rungs+listOf(rung)+b.rungs)
    }
}
