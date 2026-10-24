// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/stitches/auto_satin.py:SatinSegment/build_graph/path_to_operations/
// collapse_sequential_segments and lib/elements/satin_column.py:find_cut_points/split/
// _assign_rungs_to_split_rails/_add_rungs_if_necessary/center_line@b0edd96311ece82ee48816dc93466274b12c2a9c
// (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.stitches

import io.github.linker2k.myembro.engine.inkstitch.elements.*
import io.github.linker2k.myembro.engine.inkstitch.elements.utils.StrokeToSatin.Section
import io.github.linker2k.myembro.engine.inkstitch.stitches.utils.SatinRouteGraph
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.*
import io.github.linker2k.myembro.engine.inkstitch.utils.Point
import io.github.linker2k.myembro.engine.inkstitch.svg.PIXELS_PER_MM
import io.github.linker2k.myembro.engine.model.EmbThread
import org.locationtech.jts.geom.*
import org.locationtech.jts.linearref.LengthIndexedLine
import kotlin.math.ceil

/** Explicit satin ladders; no glyph/jamo inference. DOM-free source auto-route adapter.
 * Operations retain provenance so callers can verify each underpath precedes its covering satin.
 */
object AutoSatin {
    data class Operation(val element:Int,val start:Double,val end:Double,val reverse:Boolean,val underpath:Boolean)
    data class Result(val groups:List<StitchGroup>,val operations:List<Operation>,val jumpEdges:Int,val discardedSatins:Int)
    private val factory=GeometryFactory()
    private fun line(points:List<Point>)=factory.createLineString(points.map { Coordinate(it.x,it.y) }.toTypedArray())
    private fun point(c:Coordinate)=Point(c.x,c.y)
    private fun slice(path:LineString,start:Double,end:Double):List<Point> =
        LengthIndexedLine(path).extractLine(path.length*start,path.length*end).coordinates.map(::point)
    private fun at(path:LineString,t:Double)=point(LengthIndexedLine(path).extractPoint(path.length*t))
    private fun center(section:Section,p:SatinParameters):LineString {
        val points=SatinColumn.routingPairs(section.left,section.right,p,section.rungs.map { SatinColumn.Rung(it.first,it.second) })
            .map { (a,b) -> SatinColumn.offsetPoints(a,b,0.0 to 0.0,-0.5 to -0.5).first }
        require(points.isNotEmpty())
        return line(if(points.size<2)points+points.first() else points)
    }
    /** No trim commands inside one component; user grouping supplies boundary trims separately. */
    fun route(sections:List<Section>,color:EmbThread,parameters:SatinParameters=SatinParameters()):Result {
        require(sections.isNotEmpty())
        val centers=sections.map { center(it,parameters) }
        val nodes=linkedMapOf<Point,Int>();val points=mutableListOf<Point>()
        fun node(p:Point):Int=nodes.getOrPut(p) { points.add(p);points.lastIndex }
        data class Segment(val element:Int,val start:Double,val end:Double)
        val segments=mutableListOf<Segment>();val edges=mutableListOf<SatinRouteGraph.Edge>()
        centers.forEachIndexed { index,c ->
            require(c.length>0) { "Auto-route centerline has zero length" }
            val count=ceil(c.length/PIXELS_PER_MM).toInt()
            for(i in 0 until count) {
                val a=i.toDouble()/count;val b=(i+1).toDouble()/count
                val id=segments.size;segments+=Segment(index,a,b)
                edges+=SatinRouteGraph.Edge(node(at(c,a)),node(at(c,b)),id)
            }
        }
        // autoroute._add_unordered_jumps: minimum spanning augmentation between components.
        // k=1 edge augmentation uses nearest component node pairs and Kruskal's MST.
        val parent=IntArray(points.size) { it }
        fun root(n:Int):Int { var x=n;while(parent[x]!=x)x=parent[x];return x }
        fun union(a:Int,b:Int) { parent[root(a)]=root(b) }
        edges.forEach { union(it.start,it.end) }
        val connected=points.indices.groupBy { root(it) }.values.toList()
        data class Bridge(val a:Int,val b:Int,val length:Double)
        val bridges=mutableListOf<Bridge>()
        for(i in connected.indices) for(j in i+1 until connected.size) {
            var best:Bridge?=null
            for(a in connected[i])for(b in connected[j]) {
                val d=points[a].distance(points[b])
                if(best==null || d<best.length)best=Bridge(a,b,d)
            }
            best?.let { bridges+=it }
        }
        for(bridge in bridges.sortedBy { it.length }) if(root(bridge.a)!=root(bridge.b)) {
            edges+=SatinRouteGraph.Edge(bridge.a,bridge.b);union(bridge.a,bridge.b)
        }
        val start=points.indices.minBy { points[it].x };val end=points.indices.maxBy { points[it].x }
        val steps=SatinRouteGraph.route(edges,start,end)
        val operations=mutableListOf<Operation>()
        var jumps=0;var afterJump=false
        for(step in steps) {
            val id=step.satinSegment
            if(id==null) { jumps++;afterJump=true;continue }
            val segment=segments[id]
            val reversed=points[step.start]!=at(centers[segment.element],segment.start)
            val op=Operation(segment.element,segment.start,segment.end,reversed,step.underpath)
            val last=operations.lastOrNull()
            val sequential=!afterJump && last!=null && last.element==op.element && last.underpath==op.underpath &&
                last.reverse==op.reverse && if(op.reverse)last.start==op.end else last.end==op.start
            // Source satin sequential collapse. Running collapse below uses geometry distance <0.5px.
            if(sequential) operations[operations.lastIndex]=if(op.reverse)last!!.copy(start=op.start)else last!!.copy(end=op.end)
            else operations+=op
            afterJump=false
        }
        data class Render(val op:Operation,val running:List<Point>?)
        val render=mutableListOf<Render>()
        for(op in operations) {
            if(!op.underpath) { render+=Render(op,null);continue }
            // RunningStitch set_precision(0.00001), is_sequential, __add__.
            val precise=PrecisionModel(100000.0)
            val path=slice(centers[op.element],if(op.reverse)op.end else op.start,if(op.reverse)op.start else op.end)
                .map { q -> val coordinate=Coordinate(q.x,q.y);precise.makePrecise(coordinate);point(coordinate) }
                .fold(mutableListOf<Point>()) { out,q -> if(out.lastOrNull()!=q)out+=q;out }
            require(path.size>=2)
            val last=render.lastOrNull()
            val previous=last?.running
            val opposite=previous!=null && previous.first()==path.last() && previous.last()==path.first()
            if(last!=null && last.op.element==op.element && previous!=null && !opposite && line(previous).distance(line(path))<0.5)
                render[render.lastIndex]=last.copy(running=previous+path)
            else render+=Render(op,path)
        }
        val groups=mutableListOf<StitchGroup>();var discarded=0
        for(item in render) {
            val op=item.op
            val generated=if(op.underpath) {
                val path=requireNotNull(item.running)
                Stroke(listOf(path),color,StrokeParameters(running_stitch_length_mm=listOf(parameters.running_stitch_length_mm))).toStitchGroups(groups.lastOrNull()?.stitches?.lastOrNull())
            } else {
                var section=sections[op.element]
                // Compute both cut pairs from the ORIGINAL before either split (source avoids gaps).
                val pairs=SatinColumn.routingPairs(section.left,section.right,parameters,section.rungs.map { SatinColumn.Rung(it.first,it.second) })
                fun cutPair(t:Double):Pair<Point,Point> {
                    val flat=pairs.flatMap { listOf(it.first,it.second) }
                    val index=Math.rint(flat.size*t).toInt()
                    require(index in flat.indices && index+1-index%2<flat.size) { "Invalid original satin cut index" }
                    return flat[index-index%2] to flat[index+1-index%2]
                }
                val first=if(op.start>0)cutPair(op.start)else null
                val last=if(op.end<1)cutPair(op.end)else null
                if(first!=null) { val split=split(section,first,parameters).second;if(split==null){discarded++;continue};section=split }
                if(last!=null) { val split=split(section,last,parameters).first;if(split==null){discarded++;continue};section=split }
                if(op.reverse)section=section.copy(left=section.left.reversed(),right=section.right.reversed())
                section.column(parameters,color).toStitchGroups(groups.lastOrNull()?.stitches?.lastOrNull())
            }
            groups+=generated
        }
        return Result(groups,operations,jumps,discarded)
    }
    /** Source split adapter for already oriented convert-to-satin ladders (not arbitrary raw rails). */
    private fun split(section:Section,cut:Pair<Point,Point>,p:SatinParameters):Pair<Section?,Section?> {
        val rails=listOf(line(section.left),line(section.right))
        fun cutRail(rail:LineString,point:Point):Pair<List<Point>?,List<Point>?> {
            val index=LengthIndexedLine(rail);val d=index.project(Coordinate(point.x,point.y))
            val before=if(d<=0)null else index.extractLine(0.0,d).coordinates.map(::point)
            val after=if(d>=rail.length)null else index.extractLine(d,rail.length).coordinates.map(::point)
            return before to after
        }
        val a=cutRail(rails[0],cut.first);val b=cutRail(rails[1],cut.second)
        fun section(left:List<Point>?,right:List<Point>?):Section? {
            if(left==null || right==null)return null
            val l=line(left);val r=line(right)
            if(l.length<p.zigzag_spacing_mm*PIXELS_PER_MM || r.length<p.zigzag_spacing_mm*PIXELS_PER_MM)return null
            val rungs=section.rungs.filter { (a,b) -> val rung=line(listOf(a,b));l.intersects(rung)&&r.intersects(rung) }.toMutableList()
            val count=rungs.size
            fun add(position:Double,normalized:Boolean=false) {
                fun interpolate(path:LineString):Point {
                    var distance=if(normalized)position*path.length else position
                    if(distance<0)distance=path.length+distance
                    return point(LengthIndexedLine(path).extractPoint(distance.coerceIn(0.0,path.length)))
                }
                val start=interpolate(l);val end=interpolate(r);val mid=(start+end)/2.0
                var x=mid+(start-mid)*1.1;var y=mid+(end-mid)*1.1
                if(x.distance(y)<5) { x=mid+(x-mid)*3.0;y=mid+(y-mid)*3.0 }
                rungs+=x to y
            }
            if(count==0 || count==2)add(0.3)
            if(count==0) { add(0.5,true);add(-0.3) }
            return Section(left,right,rungs)
        }
        return section(a.first,b.first) to section(a.second,b.second)
    }
}
