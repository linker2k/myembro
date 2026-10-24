// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/elements/satin_column.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
// Functions: flattened_sections, _get_rails_to_reverse, plot_points_on_rails, SatinProcessor,
// inset_short_stitches_sawtooth, get_split_points, do_satin/e_stitch/s_stitch/zigzag,
// do_center_walk/contour_underlay/zigzag_underlay, to_stitch_groups.
// Geometry offsets: lib/utils/geometry.py:offset_points; splitting: lib/stitches/running_stitch.py.
package io.github.linker2k.myembro.engine.inkstitch.elements

import io.github.linker2k.myembro.engine.inkstitch.utils.Point
import io.github.linker2k.myembro.engine.inkstitch.svg.Units
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.Stitch
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.StitchGroup
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.InkLockStitch
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.LockStitch
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.engine.geometry.Point as GeometryPoint
import io.github.linker2k.myembro.engine.inkstitch.stitches.*
import io.github.linker2k.myembro.engine.inkstitch.utils.Prng
import org.locationtech.jts.geom.*
import org.locationtech.jts.linearref.LengthIndexedLine
import org.locationtech.jts.operation.distance.DistanceOp
import kotlin.math.*

private fun Point.interpolate(other:Point,t:Double)=this*(1-t)+other*t
private fun Point.norm()=length()
private infix fun Point.scalar(other:Point)=dot(other)
private fun px(mm:Double)=Units.mmToPixels(mm)
private fun Pair<Double,Double>.pixels()=px(first) to px(second)
private fun Point.geometry()=GeometryPoint(x,y)
private fun GeometryPoint.svg()=Point(x,y)
private fun split(a:Point,b:Point,length:Double)=InkRunning.split(a.geometry(),b.geometry(),length).map { it.svg() }

/** Intermediate geometric layers for the shared StitchGroup adapter. All coordinates are SVG px. */
data class SatinLayer(val points: List<Point>, val tags: Set<String>, val stitchTags: List<Set<String>> = List(points.size) { emptySet() }) {
    init { require(points.size == stitchTags.size) }
}

/** No raster skeleton/TTF inference. Input is an explicit two-rail ladder, already flattened. */
class SatinColumn(
    private val leftRail:List<Point>,
    private val rightRail:List<Point>,
    val parameters:SatinParameters = SatinParameters(),
    private val rungs:List<Rung> = emptyList(),
    private val color:EmbThread? = null,
    private val startPoint:Point? = null,
    private val endPoint:Point? = null,
    private val nextStitch:Point? = null,
    private val trim_after:Boolean = false,
    private val stop_after:Boolean = false,
    private val lockStart:LockStitch? = InkLockStitch(),
    private val lockEnd:LockStitch? = InkLockStitch(),
    private val force_lock_stitches:Boolean = false,
) : EmbroideryElement {
    data class Rung(val a: Point, val b: Point, val points: List<Point> = listOf(a,b))

    override fun toStitchGroups(previousStitch:Stitch?):List<StitchGroup> {
        val layers=generateLayers(leftRail,rightRail,parameters,rungs,startPoint,endPoint,previousStitch?.point,nextStitch)
        val stitches=arrayListOf<Stitch>()
        for(layer in layers.filter { it.points.isNotEmpty() }) {
            val first=layer.points.first()
            val last=stitches.lastOrNull()
            val runningLength=px(max(.01,parameters.running_stitch_length_mm))
            if(last!=null && last.point.distance(first)>runningLength)
                stitches+=split(last.point,first,runningLength).map { Stitch(it,setOf("satin_column","satin_column_underlay")) }
            layer.points.forEachIndexed { i,q -> stitches+=Stitch(q,layer.tags+layer.stitchTags[i]) }
        }
        if(stitches.isEmpty())return emptyList()
        return listOf(StitchGroup(color,stitches,trimAfter=trim_after,stopAfter=stop_after,
            lockStart=lockStart,lockEnd=lockEnd,minStitchLengthMm=parameters.min_stitch_length_mm,
            minJumpLengthMm=parameters.min_jump_stitch_length_mm,forceLockStitches=force_lock_stitches))
    }
    companion object {
        private const val PX = 1.0 // Original literal thresholds are SVG px.
        private val factory = GeometryFactory()
        private fun line(points: List<Point>) = factory.createLineString(points.map { Coordinate(it.x,it.y) }.toTypedArray())
        private fun point(c: Coordinate) = Point(c.x,c.y)
        private fun clean(points: List<Point>): List<Point> {
            require(points.size <= 200_000 && points.all { it.x.isFinite() && it.y.isFinite() && abs(it.x)<1e6 && abs(it.y)<1e6 })
            return points.filterIndexed { i,p -> i==0 || p!=points[i-1] }
        }
        private class Rail(val points: List<Point>) {
            val geometry = line(points)
            val index = LengthIndexedLine(geometry)
            val length = geometry.length
            fun at(f:Double) = point(index.extractPoint(f.coerceIn(0.0,1.0)*length))
        }
        /** geometry.py:offset_points; contracts to the midpoint but never crosses it. */
        fun offsetPoints(a:Point,b:Point,offsetPx:Pair<Double,Double>,proportional:Pair<Double,Double> = 0.0 to 0.0): Pair<Point,Point> {
            val distance=a.distance(b)
            if(distance < .0001*PX) return a to b
            var x=offsetPx.first+distance*proportional.first;var y=offsetPx.second+distance*proportional.second
            if(x+y < -distance) {val scale=-distance/(x+y);x*=scale;y*=scale}
            val direction=(b-a)*(1/distance)
            return (a-direction*x) to (b+direction*y)
        }
        private fun orient(left:List<Point>,right:List<Point>,p:SatinParameters,snap:Boolean=true,directionRails:Pair<List<Point>,List<Point>>?=null): Pair<List<Point>,List<Point>> {
            // swap_satin_rails is applied before reverse_rails in the original rails property.
            val inputA=if(p.swap_satin_rails)right else left
            val inputB=if(p.swap_satin_rails)left else right
            val directionA=directionRails?.let { if(p.swap_satin_rails)it.second else it.first } ?: inputA
            val directionB=directionRails?.let { if(p.swap_satin_rails)it.first else it.second } ?: inputB
            val a=Rail(directionA);val b=Rail(directionB)
            val automatic=(0 until 10).sumOf { a.at(it/10.0).distance(b.at(it/10.0)) } >
                (0 until 10).sumOf { a.at(it/10.0).distance(b.at(1-it/10.0)) }
            val l=if(p.reverse_rails in setOf("first","both")) inputA.reversed() else inputA
            val r=if(p.reverse_rails in setOf("second","both") || p.reverse_rails=="automatic" && automatic) inputB.reversed() else inputB
            if(!snap) return l to r
            val precision=PrecisionModel(100000.0) // Shapely set_precision(..., 0.00001 SVG px).
            fun precise(path:List<Point>)=path.map { q->Coordinate(q.x,q.y).also(precision::makePrecise).let(::point) }
            return precise(l) to precise(r)
        }
        private fun push(points:List<Point>,start:Double,end:Double):List<Point> {
            if(points.size<2) return points
            var path=points
            fun extend(path:List<Point>,amount:Double):List<Point> {
                if(amount>=0) return path
                return listOf(path[0]+(path[0]-path[1])*(-amount/path[0].distance(path[1])))+path
            }
            path=extend(path,start);path=extend(path.reversed(),end).reversed()
            val rail=Rail(path);val s=max(0.0,start);val e=max(0.0,end)
            if(s+e >= rail.length-.5*PX || s==0.0 && e==0.0) return path
            return rail.index.extractLine(s,rail.length-max(e,1e-8*PX)).coordinates.map(::point)
        }
        private fun sections(left:List<Point>,right:List<Point>,rungs:List<Rung>):List<Pair<Rail,Rail>> {
            val a=Rail(left);val b=Rail(right)
            if(rungs.isEmpty()) return listOf(a to b)
            val cuts = listOf(arrayListOf<Double>(), arrayListOf<Double>())
            for (rung in rungs) {
                val crossing = line(rung.points)
                val intersections = crossing.intersection(factory.createMultiLineString(arrayOf(a.geometry,b.geometry)))
                // flattened_sections: upstream ignores rungs with more than two point intersections.
                if (intersections is MultiPoint && intersections.numGeometries > 2) continue
                for ((i,rail) in listOf(a,b).withIndex())
                    cuts[i] += rail.index.project(DistanceOp.nearestPoints(crossing,rail.geometry)[1])
            }
            // geometry.py:cut_multiple sorts each rail's cuts independently, preserving empty slots.
            fun cutMultiple(rail: Rail, distances: List<Double>): List<Rail?> {
                val segments = mutableListOf<Rail?>(rail)
                var distanceSoFar = 0.0
                var trailingEmpty = 0
                for (distance in distances.sorted()) {
                    val segment = requireNotNull(segments.removeAt(segments.lastIndex))
                    val d = distance - distanceSoFar
                    val (before,after) = when {
                        d <= 0.0 -> null to segment
                        d >= segment.length -> segment to null
                        else -> Rail(segment.index.extractLine(0.0,d).coordinates.map(::point)) to
                            Rail(segment.index.extractLine(d,segment.length).coordinates.map(::point))
                    }
                    segments += before
                    if (after == null) trailingEmpty++ else {
                        if (before != null) distanceSoFar += before.length
                        segments += after
                    }
                }
                repeat(trailingEmpty) { segments += null }
                return segments
            }
            return cutMultiple(a,cuts[0]).zip(cutMultiple(b,cuts[1])).mapNotNull { (x,y) ->
                if (x == null || y == null) null else x to y
            }
        }

        private fun separation(a:Point,b:Point,oldA:Point,oldB:Point):Double {
            val v=oldB-oldA
            if(v.norm()<.01*PX) return line(listOf(a,b)).distance(factory.createPoint(Coordinate(oldA.x,oldA.y)))
            val n=Point(-v.y,v.x)*(1/v.norm())
            return max(abs((a-oldA) scalar n),abs((b-oldB) scalar n))
        }
        private class ParametersPx(mm:SatinParameters) {
            val center_walk_underlay_stitch_length_px=px(max(.01,mm.center_walk_underlay_stitch_length_mm))
            val contour_underlay_inset_px=mm.contour_underlay_inset_mm.pixels()
            val contour_underlay_stitch_length_px=px(max(.01,mm.contour_underlay_stitch_length_mm))
            val contour_underlay_stitch_tolerance_px=px(max(.01,mm.contour_underlay_stitch_tolerance_mm))
            val max_stitch_length_px=mm.max_stitch_length_mm?.let(::px)
            val min_random_split_length_px=mm.min_random_split_length_mm?.let(::px)
            val min_stitch_length_px=px(mm.min_stitch_length_mm ?: .1)
            val pull_compensation_px=mm.pull_compensation_mm.pixels()
            val push_compensation_px=mm.push_compensation_mm.pixels()
            val running_stitch_length_px=px(max(.01,mm.running_stitch_length_mm))
            val running_stitch_tolerance_px=px(max(.01,mm.running_stitch_tolerance_mm))
            val short_stitch_distance_px=px(mm.short_stitch_distance_mm)
            val zigzag_spacing_px=px(max(.01,mm.zigzag_spacing_mm))
            val zigzag_underlay_inset_px=mm.zigzag_underlay_inset_mm?.pixels()
            val zigzag_underlay_max_stitch_length_px=mm.zigzag_underlay_max_stitch_length_mm?.let(::px)
            val zigzag_underlay_spacing_px=px(max(.01,mm.zigzag_underlay_spacing_mm))
            val center_walk_underlay=mm.center_walk_underlay
            val center_walk_underlay_position=mm.center_walk_underlay_position
            val center_walk_underlay_repeats=mm.center_walk_underlay_repeats
            val contour_underlay=mm.contour_underlay
            val contour_underlay_inset_percent=mm.contour_underlay_inset_percent
            val pull_compensation_percent=mm.pull_compensation_percent
            val random_width_decrease_percent=mm.random_width_decrease_percent
            val random_width_increase_percent=mm.random_width_increase_percent
            val random_zigzag_spacing_percent=mm.random_zigzag_spacing_percent
            val random_seed=mm.random_seed
            val random_split_jitter_percent=mm.random_split_jitter_percent
            val random_split_phase=mm.random_split_phase
            val running_stitch_position=mm.running_stitch_position
            val satin_method=mm.satin_method
            val short_stitch_inset=mm.short_stitch_inset
            val split_method=mm.split_method
            val split_staggers=mm.split_staggers
            val zigzag_underlay=mm.zigzag_underlay
            val zigzag_underlay_inset_percent=mm.zigzag_underlay_inset_percent
        }
        private class Processor(val p:ParametersPx,val mm:Pair<Double,Double>,val prop:Pair<Double,Double>,val random:Boolean) {
            var cycle=0
            private val seed=Prng.joinArgs(p.random_seed,"satin-points")
            fun process(a:Point,b:Point):Pair<Point,Point> {
                if(!random) return offsetPoints(a,b,mm,prop)
                val roll=Prng.uniformFloats(seed,cycle++)
                val decrease=p.random_width_decrease_percent;val increase=p.random_width_increase_percent
                return offsetPoints(a,b,mm,prop.first-decrease.first/100+roll[0]*(decrease.first+increase.first)/100 to
                    prop.second-decrease.second/100+roll[1]*(decrease.second+increase.second)/100)
            }
            fun multiple():Double = if(!random) 1.0 else max(1+(Prng.uniformFloats(seed,cycle++)[0]-.5)*2*max(0.0,p.random_zigzag_spacing_percent)/100,.01)
        }
        private class Generator(left:List<Point>,right:List<Point>,parameters:SatinParameters,val rungs:List<Rung>) {
            private val p=ParametersPx(parameters)
            val rails=orient(left,right,parameters).let { (a,b)->push(a,p.push_compensation_px.first,p.push_compensation_px.second) to push(b,p.push_compensation_px.first,p.push_compensation_px.second) }
            val sections=sections(rails.first,rails.second,rungs)
            val odd=p.center_walk_underlay && p.center_walk_underlay_repeats%2==1
            fun pairs(spacingInput:Double,mm:Pair<Double,Double> = 0.0 to 0.0,prop:Pair<Double,Double> = 0.0 to 0.0,random:Boolean=false):List<Pair<Point,Point>> {
                val spacing=max(.01,spacingInput)
                val processor=Processor(p,mm,prop,random)
                val result=arrayListOf<Pair<Point,Point>>()
                var oldA=sections.first().first.at(0.0);var oldB=sections.first().second.at(0.0)
                result+=processor.process(oldA,oldB)
                for((a,b) in sections) {
                    val step=spacing/maxOf(a.length,b.length,.01*PX)
                    var multiple=processor.multiple();var currentSpacing=spacing*multiple
                    var travel=(1-min(separation(a.at(0.0),b.at(0.0),oldA,oldB)/spacing,1.0))*step*multiple
                    var cursor=0.0;var iterations=0
                    while(cursor+travel<=1.0) {
                        iterations++
                        val x=a.at(cursor+travel);val y=b.at(cursor+travel)
                        if(iterations<=2) {
                            val distance=separation(x,y,oldA,oldB)
                            if(distance>.01*PX && abs((currentSpacing-distance)/currentSpacing)>.05) {
                                travel*=currentSpacing/distance
                                if(iterations==1) travel=min(travel,1-cursor)
                                continue
                            }
                        }
                        cursor+=travel;multiple=processor.multiple();travel=step*multiple;currentSpacing=spacing*multiple
                        oldA=x;oldB=y;result+=processor.process(x,y);iterations=0
                        require(result.size<=1_000_000) { "Satin exceeds stitch budget" }
                        if(cursor>=1.0) break
                    }
                }
                val (lastA,lastB)=sections.last()
                if(separation(lastA.at(1.0),lastB.at(1.0),oldA,oldB)>px(.1)) result+=processor.process(lastA.at(1.0),lastB.at(1.0))
                return result
            }
            fun shorten(pairs:List<Pair<Point,Point>>):List<Pair<Point,Point>> {
                if(p.short_stitch_distance_px==0.0 || p.short_stitch_inset.isEmpty()) return pairs
                var oldA:Point?=null;var oldB:Point?=null;var iA=0;var iB=0
                fun inset(a:Point,b:Point,index:Int):Double {
                    var inset=min(p.short_stitch_inset[index%p.short_stitch_inset.size]/100,.5)*a.distance(b)
                    if(p.split_method=="default" && !p.random_split_phase && p.max_stitch_length_px!=null) inset=min(inset,p.max_stitch_length_px/3)
                    return -inset
                }
                return pairs.map { (a,b)->
                    val da=if(oldA==null || a.distance(oldA!!)>=p.short_stitch_distance_px) {oldA=a;iA=0;0.0} else inset(a,b,iA++)
                    val db=if(oldB==null || b.distance(oldB!!)>=p.short_stitch_distance_px) {oldB=b;iB=0;0.0} else inset(b,a,iB++)
                    offsetPoints(a,b,da to db)
                }
            }
            private fun evenSplits(a:Point,b:Point,n:Int,sigma:Double,seed:String):List<Point> {
                if(n<=1) return emptyList()
                val jitter=Prng.nUniformFloats(n-1,seed)
                return (1 until n).map { it.toDouble()/n+(jitter[it-1]*2-1)*sigma/n }.sorted().map { a.interpolate(b,it.coerceIn(0.0,1.0)) }
            }
            private fun splits(a:Point,b:Point,sa:Point,sb:Point,row:Int,fromEnd:Boolean=false,count:Int?=null):Pair<List<Point>,Int?> {
                val length=p.max_stitch_length_px ?: return emptyList<Point>() to null
                val distance=a.distance(b)
                val seed=Prng.joinArgs(p.random_seed,"satin-split",row)
                if(p.split_method=="default") {
                    val minimum=min(length,p.min_random_split_length_px ?: length)
                    if(distance<=minimum) return emptyList<Point>() to 1
                    val sigma=p.random_split_jitter_percent.coerceIn(0.0,100.0)/100
                    if(p.random_split_phase) {
                        val d=sa.distance(sb);var progress=length*Prng.uniformFloats(seed,"phase")[0]
                        val points=arrayListOf<Point>()
                        val random=Prng.iterUniformFloats(seed).iterator()
                        while(progress<d) {
                            points+=sa.interpolate(sb,progress/d)
                            progress+=length*(1+sigma*(random.next()-.5)*2)
                            require(points.size<1_000_000)
                        }
                        if(points.size>1 && points.first().distance(a)<=p.min_stitch_length_px) points.removeAt(0)
                        if(points.size>1 && points.last().distance(b)<=p.min_stitch_length_px) points.removeAt(points.lastIndex)
                        return points to null
                    }
                    val n=count ?: ceil(distance/length).toInt()
                    require(n<=1_000_000)
                    return evenSplits(a,b,n,sigma,seed) to n
                }
                if(distance<=length) return emptyList<Point>() to null
                val x=if(fromEnd)b else a;val y=if(fromEnd)a else b
                val sx=if(fromEnd)sb else sa;val sy=if(fromEnd)sa else sb
                val v=y-x;val norm=v.norm()
                fun project(q:Point)=(((q-x) scalar v)/norm).coerceIn(0.0,norm)
                val low=project(sx);val high=project(sy)
                var travel=positiveMod(row/(if(p.split_method=="simple")1.0 else p.split_staggers),1.0)*length
                val out=arrayListOf<Point>()
                while(travel<distance) { if(travel>low && travel<high) out+=x.interpolate(y,travel/distance);travel+=length;require(out.size<1_000_000) }
                return (if(fromEnd)out.reversed() else out) to null
            }
            fun top():SatinLayer {
                val source=pairs(p.zigzag_spacing_px/(if(p.satin_method=="zigzag")2 else 1),p.pull_compensation_px,
                    p.pull_compensation_percent.first/100 to p.pull_compensation_percent.second/100,true)
                val regular=if(p.satin_method=="zigzag")source.mapIndexed { i,pair->if(i%2==0)pair.first else pair.second }.chunked(2).filter { it.size==2 }.map { it[0] to it[1] } else source
                val short=shorten(regular)
                val points=arrayListOf<Point>();val tags=arrayListOf<Set<String>>()
                fun add(q:Point,vararg tag:String) {
                    points+=q
                    tags+=setOf("satin_column")+tag.toSet()+(if(tag.any { it=="peak_a" || it=="peak_b" })setOf("peak_stitch") else emptySet())
                }
                fun appendSplit(q:List<Point>)=q.forEach { add(it,when(p.satin_method) {
                    "e_stitch"->"split_stitch";"zigzag"->"zigzag_split_stitch";else->"satin_split_stitch" }) }
                var previous:Point?=null;var previousShort:Point?=null;var count:Int?=null
                regular.indices.forEach { i->
                    val (a,b)=regular[i];val (sa,sb)=short[i]
                    when(p.satin_method) {
                        "satin_column","zigzag" -> {
                            if(previous!=null) appendSplit(splits(previous!!,a,previousShort!!,sa,2*i,true,if(p.satin_method=="satin_column")count else null).first)
                            add(sa,if(p.satin_method=="zigzag")"peak_a" else "satin_column_edge")
                            val (split,n)=splits(a,b,sa,sb,2*i+1);appendSplit(split);count=n
                            add(sb,if(p.satin_method=="zigzag")"peak_b" else "satin_column_edge")
                            previous=b;previousShort=sb
                        }
                        "e_stitch" -> {
                            if(previous!=null && p.max_stitch_length_px!=null && p.zigzag_spacing_px>p.max_stitch_length_px)
                                appendSplit(splits(previous!!,a,previous!!,a,0).first)
                            val split=splits(a,b,sa,sb,2*i+1).first
                            add(sa,"edge","left");appendSplit(split);add(sb,"edge");appendSplit(split.reversed());add(sa,"edge");previous=sa
                        }
                        "s_stitch" -> {
                            val split=splits(a,b,sa,sb,i).first
                            val row=listOf(sa)+split+sb
                            // v3.3.0 computes but does not append between-row splits; preserve source behavior.
                            (if(i%2==0)row.reversed() else row).forEach { add(it,"s_stitch") }
                        }
                    }
                }
                return SatinLayer(if(odd)points.reversed() else points,setOf("satin_column")+(if(p.satin_method in setOf("e_stitch","s_stitch"))setOf(p.satin_method) else emptySet()),if(odd)tags.reversed() else tags)
            }
            fun running(points:List<Point>,length:Double,tolerance:Double) =
                InkRunning.even(points.map { it.geometry() },max(.01,length),max(.01,tolerance)).map { it.svg() }
            fun center(position:Double=p.running_stitch_position,length:Double=p.running_stitch_length_px):List<Point> {
                val fraction=position.coerceIn(0.0,100.0)/100
                val points=pairs(max(.01,p.running_stitch_tolerance_px),prop=-fraction to -(1-fraction)).map { it.first }
                return running(points,length,p.running_stitch_tolerance_px).let { if(it.size==1) it+it.first() else it }
            }
            fun connect(a:List<Point>,b:List<Point>):List<Point> = if(a.isEmpty()) b else if(b.isEmpty())a else
                a+(if(a.last().distance(b.first())>max(.01,p.running_stitch_length_px))split(a.last(),b.first(),max(.01,p.running_stitch_length_px))+b.first() else emptyList())+b
            fun cut(points:List<Point>,end:Point):Pair<List<Point>,List<Point>> {
                if(points.size<2) return points to points
                val cutPair=pairs(p.zigzag_spacing_px).minBy { min(it.first.distance(end),it.second.distance(end)) }
                val cut=line(listOf(cutPair.first,cutPair.second))
                val rail=Rail(points)
                val near=DistanceOp.nearestPoints(rail.geometry,cut)[0]
                val position=rail.index.project(near)
                return rail.index.extractLine(0.0,position).coordinates.map(::point) to rail.index.extractLine(position,rail.length).coordinates.map(::point)
            }
            fun layers(top:SatinLayer,end:Point?):List<SatinLayer> {
                val layers=arrayListOf<SatinLayer>()
                fun layer(points:List<Point>,tag:String)=SatinLayer(points,setOf("satin_column","satin_column_underlay",tag))
                if(p.center_walk_underlay) {
                    val center=center(p.center_walk_underlay_position,p.center_walk_underlay_stitch_length_px)
                    val parts=if(end==null)listOf(center) else cut(center,end).let { (a,b)->if(odd)listOf(b,a.reversed()) else listOf(a,b.reversed()) }
                    for(part in parts) layers+=layer((0 until p.center_walk_underlay_repeats).flatMap { if(it%2==0)part else part.reversed() },"satin_center_walk")
                }
                if(p.contour_underlay) {
                    val mm=p.contour_underlay_inset_px;val pc=p.contour_underlay_inset_percent
                    val contour=pairs(max(.01,p.contour_underlay_stitch_tolerance_px),-mm.first to -mm.second,-pc.first/100 to -pc.second/100)
                    var a=running(contour.map { it.first },p.contour_underlay_stitch_length_px,p.contour_underlay_stitch_tolerance_px)
                    var b=running(contour.map { it.second },p.contour_underlay_stitch_length_px,p.contour_underlay_stitch_tolerance_px)
                    if(p.satin_method=="zigzag") {
                        fun peak(tag:String,last:Boolean):Point?=top.points.indices.let { ids->(if(last)ids.reversed() else ids).firstOrNull { tag in top.stitchTags[it] }?.let { top.points[it] } }
                        fun trimTo(points:List<Point>,q:Point?,atEnd:Boolean):List<Point> {
                            if(q==null || points.size<2)return points
                            val r=Rail(points);val index=r.index.project(Coordinate(q.x,q.y))
                            return r.index.extractLine(if(atEnd)0.0 else index,if(atEnd)index else r.length).coordinates.map(::point)
                        }
                        a=trimTo(a,peak("peak_a",true),true)
                        b=trimTo(trimTo(b,peak("peak_b",true),true),peak("peak_b",false),false)
                    }
                    a=push(a,mm.first,mm.second);b=push(b,mm.first,mm.second)
                    if(odd)a=a.reversed() else b=b.reversed()
                    if(end==null) layers+=layer(connect(a,b),"satin_contour_underlay") else {
                        val (a0,a1)=cut(a,end);val (b0,b1)=cut(b,end)
                        listOf(a0,b0,b1,a1).forEach { layers+=layer(it,"satin_contour_underlay") }
                    }
                }
                if(p.zigzag_underlay) {
                    val mm=p.zigzag_underlay_inset_px ?: (p.contour_underlay_inset_px.first/2 to p.contour_underlay_inset_px.second/2)
                    val pc=p.zigzag_underlay_inset_percent ?: (p.contour_underlay_inset_percent.first/2 to p.contour_underlay_inset_percent.second/2)
                    var pairs=pairs(max(.01,p.zigzag_underlay_spacing_px)/2,-mm.first to -mm.second,-pc.first/100 to -pc.second/100)
                    if(odd)pairs=pairs.reversed()
                    fun zigzagLayer(points:List<Point>):SatinLayer {
                        val result=arrayListOf<Point>();val tags=arrayListOf<Set<String>>()
                        for((i,q) in points.withIndex()) {
                            val maximum=p.zigzag_underlay_max_stitch_length_px
                            if(i>0 && maximum!=null) for(extra in split(points[i-1],q,maximum)) {
                                result+=extra;tags+=setOf("split_stitch")
                            }
                            result+=q;tags+=setOf("edge")
                        }
                        return SatinLayer(result,setOf("satin_column","satin_column_underlay","satin_zigzag_underlay"),tags)
                    }
                    fun join(a:SatinLayer,b:SatinLayer):SatinLayer {
                        if(a.points.isEmpty())return b
                        if(b.points.isEmpty())return a
                        val bridge=if(a.points.last().distance(b.points.first())>p.running_stitch_length_px)
                            split(a.points.last(),b.points.first(),p.running_stitch_length_px)+b.points.first() else emptyList()
                        return SatinLayer(a.points+bridge+b.points,a.tags+b.tags,
                            a.stitchTags+List(bridge.size){emptySet<String>()}+b.stitchTags)
                    }
                    val a=pairs.mapIndexed { i,q->if(i%2==0)q.first else q.second }
                    val b=pairs.mapIndexed { i,q->if(i%2==1)q.first else q.second }.reversed()
                    if(end==null)listOf(a,b).forEach { layers+=zigzagLayer(it) }
                    else {
                        val (a0,a1)=cut(a,end);val (b0,b1)=cut(b,end)
                        layers+=join(zigzagLayer(a0),zigzagLayer(b1))
                        layers+=join(zigzagLayer(b0),zigzagLayer(a1))
                    }
                }
                if(end==null) layers+=top else {
                    val (a,b)=cut(top.points,end)
                    layers+=top.copy(points=a,stitchTags=List(a.size){setOf("satin_column")})
                    layers+=top.copy(points=b.reversed(),stitchTags=List(b.size){setOf("satin_column")})
                    val ordered=layers.filterIndexed { i,_->i%2==0 }.toMutableList()
                    if(layers.size>1) ordered+=layer(centerConnector(ordered.last().points.last(),layers[1].points.first(),end),"satin_center_connection")
                    ordered+=layers.filterIndexed { i,_->i%2==1 }
                    return ordered
                }
                return layers
            }
            fun centerConnector(start:Point,target:Point,end:Point?=null):List<Point> {
                val path=center();val rail=Rail(path)
                fun project(q:Point):Double {
                    val pair=pairs(p.zigzag_spacing_px).minBy { min(it.first.distance(q),it.second.distance(q)) }
                    return rail.index.project(DistanceOp.nearestPoints(line(listOf(pair.first,pair.second)),rail.geometry)[1])
                }
                val from=project(end ?: start)
                val to=if(end!=null && !odd)rail.length else project(target)
                return connect(listOf(start),rail.index.extractLine(from,to).coordinates.map(::point))
            }
        }
        private fun ladder(subpaths:List<List<Point>>,parameters:SatinParameters,nodePaths:List<List<Point>>):Triple<List<Point>,List<Point>,List<Rung>> {
            val paths=subpaths.map(::clean)
            require(paths.size==nodePaths.size && paths.size>=2 && paths.all { it.size>=2 })
            val geometry=paths.map(::line)
            val candidates=if(paths.size<=2)listOf(0,1) else {
                val counts=geometry.indices.map { i->geometry.indices.count { j->i!=j && geometry[i].intersects(geometry[j]) } }
                val rails=geometry.indices.filter { (if(paths.size==3)counts[it]==1 else counts[it]>2) && geometry[it].length>.1*PX }
                if(rails.size==2)rails else geometry.indices.sortedByDescending { geometry[it].length }.take(2)
            }
            val a=paths[candidates[0]];val b=paths[candidates[1]]
            var rungs=paths.indices.filter { it !in candidates }.map { Rung(paths[it].first(),paths[it].last(),paths[it]) }
            if(rungs.isEmpty()) {
                val (nodesA,nodesB)=orient(nodePaths[candidates[0]],nodePaths[candidates[1]],parameters,snap=false,directionRails=a to b)
                val equal=nodesA.size==nodesB.size
                fun ends(nodes:List<Point>):List<Point> = if(nodes.size>2 || !equal)nodes.drop(1).dropLast(1)
                    else listOf(Rail(nodes).let { it.at(min(1.0,.2*PX/it.length)) })
                rungs=ends(nodesA).zip(ends(nodesB)).map { (x,y)->val middle=x.interpolate(y,.5);Rung(middle+(x-middle)*1.1,middle+(y-middle)*1.1) }
            }
            return Triple(a,b,rungs)
        }
        /** Raw geometric layers; all coordinates are SVG px. */
        fun generateFromSubpaths(subpaths:List<List<Point>>,parameters:SatinParameters=SatinParameters(),
            nodePaths:List<List<Point>> = subpaths,startPoint:Point?=null,endPoint:Point?=null):List<SatinLayer> {
            val (a,b,rungs)=ladder(subpaths,parameters,nodePaths)
            return generateLayers(a,b,parameters,rungs,startPoint,endPoint)
        }
        /** Font-parser entry point. Rail discovery and source-node rungs stay inside SatinColumn. */
        fun fromSubpaths(subpaths:List<List<Point>>,parameters:SatinParameters=SatinParameters(),
            nodePaths:List<List<Point>> = subpaths,color:EmbThread?=null,startPoint:Point?=null,endPoint:Point?=null,
            nextStitch:Point?=null,trim_after:Boolean=false,stop_after:Boolean=false,
            lockStart:LockStitch?=InkLockStitch(),lockEnd:LockStitch?=InkLockStitch(),force_lock_stitches:Boolean=false):SatinColumn {
            val (a,b,rungs)=ladder(subpaths,parameters,nodePaths)
            return SatinColumn(a,b,parameters,rungs,color,startPoint,endPoint,nextStitch,trim_after,stop_after,lockStart,lockEnd,force_lock_stitches)
        }
        fun generateLayers(left:List<Point>,right:List<Point>,parameters:SatinParameters=SatinParameters(),rungs:List<Rung> = emptyList(),
            startPoint:Point?=null,endPoint:Point?=null,previousStitch:Point?=null,nextStitch:Point?=null):List<SatinLayer> {
            val a=clean(left);val b=clean(right)
            rungs.forEach { require(clean(it.points).size>=2) { "Rung must have two finite distinct points" } }
            if(a.size<2 || b.size<2) return emptyList()
            val generator=Generator(a,b,parameters,rungs)
            val compensated=generator.pairs(px(max(.01,parameters.zigzag_spacing_mm)),parameters.pull_compensation_mm.pixels(),
                parameters.pull_compensation_percent.first/100 to parameters.pull_compensation_percent.second/100,true).let { if(it.size==1)it+it.first() else it }
            val boundary=factory.createMultiLineString(arrayOf(line(compensated.map { it.first }),line(compensated.map { it.second })))
            var end=endPoint
            if(end==null && nextStitch!=null && parameters.end_at_nearest_point) {
                end=point(DistanceOp.nearestPoints(boundary,factory.createPoint(Coordinate(nextStitch.x,nextStitch.y)))[0])
            }
            if(end!=null && generator.center().last().distance(end)<5*PX)end=null
            val top=generator.top()
            val layers=generator.layers(top,end).filter { it.points.isNotEmpty() }.toMutableList()
            var start=startPoint
            if(start==null && previousStitch!=null && parameters.start_at_nearest_point) {
                val center=line(generator.center())
                val previous=factory.createPoint(Coordinate(previousStitch.x,previousStitch.y))
                start=point(DistanceOp.nearestPoints(center,previous)[0])
                if(previousStitch.distance(start)>px(parameters.min_jump_stitch_length_mm ?: 3.0) && boundary.distance(previous)<px(parameters.min_jump_stitch_length_mm ?: 3.0))
                    start=point(DistanceOp.nearestPoints(boundary,previous)[0])
            }
            if(start!=null && layers.isNotEmpty()) layers.add(0,SatinLayer(generator.centerConnector(start,layers.first().points.first()),setOf("satin_column","satin_column_underlay")))
            if(end!=null)layers+=SatinLayer(listOf(end),setOf("satin_column"))
            return layers
        }
        /** auto_satin center_line/find_cut_points: original default uncompensated rail samples. */
        fun routingPairs(left:List<Point>,right:List<Point>,parameters:SatinParameters=SatinParameters(),rungs:List<Rung> = emptyList()):List<Pair<Point,Point>> =
            Generator(clean(left),clean(right),parameters,rungs).pairs(px(max(.01,parameters.zigzag_spacing_mm)))
        /** Exposed for exact rail/rung geometry verification, before machine quantization/safety policy. */
        fun plotPointsOnRails(left:List<Point>,right:List<Point>,parameters:SatinParameters=SatinParameters(),rungs:List<Rung> = emptyList()):List<Pair<Point,Point>> =
            Generator(clean(left),clean(right),parameters,rungs).pairs(px(max(.01,parameters.zigzag_spacing_mm)),parameters.pull_compensation_mm.pixels(),
                parameters.pull_compensation_percent.first/100 to parameters.pull_compensation_percent.second/100)
    }
}
