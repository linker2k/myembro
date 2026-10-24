// SPDX-License-Identifier: GPL-3.0-or-later
// Routing comparison ported from Ink/Stitch lib/elements/fill_stitch.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.lettering

import io.github.linker2k.myembro.engine.geometry.*
import io.github.linker2k.myembro.engine.inkstitch.elements.DefaultTatami
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.*
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.Stitch
import io.github.linker2k.myembro.engine.inkstitch.stitches.InkAutoFill
import io.github.linker2k.myembro.engine.inkstitch.svg.PIXELS_PER_MM
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.model.*
import io.github.linker2k.myembro.engine.sewout.*
import kotlinx.serialization.json.*
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.util.AffineTransformation
import org.locationtech.jts.operation.distance.DistanceOp
import java.awt.Font
import java.awt.font.FontRenderContext
import java.awt.geom.FlatteningPathIterator
import java.awt.geom.PathIterator
import java.io.File
import kotlin.math.*

object KoreanTatamiTrial {
    private val thread=EmbThread(0xff000000.toInt(),"Black 40wt")
    private val frc=FontRenderContext(null,true,true)
    private fun paths(font:Font,text:String):Pair<List<List<Pair<Double,Double>>>,List<Int>> {
        val glyphs=font.createGlyphVector(frc,text);val paths=mutableListOf<List<Pair<Double,Double>>>();val ids=mutableListOf<Int>()
        for(i in 0 until glyphs.numGlyphs) {
            val iterator=FlatteningPathIterator(glyphs.getGlyphOutline(i).getPathIterator(null),0.2)
            var p=mutableListOf<Pair<Double,Double>>();val v=DoubleArray(6)
            while(!iterator.isDone) {
                when(iterator.currentSegment(v)) {
                    PathIterator.SEG_MOVETO->p=mutableListOf(v[0] to v[1])
                    PathIterator.SEG_LINETO->p+=v[0] to v[1]
                    PathIterator.SEG_CLOSE->if(p.size>=3) {p+=p.first();paths+=p;ids+=i}
                };iterator.next()
            }
        }
        return paths to ids
    }
    private fun parts(g:Geometry):List<Polygon> = if(g is Polygon)listOf(g) else (0 until g.numGeometries).flatMap {parts(g.getGeometryN(it))}
    private fun region(paths:List<List<Pair<Double,Double>>>)=PolygonRegion.fromPaths(paths.map {p->Polyline(p.map {Point(it.first,it.second)},true)},FillRule.EVEN_ODD)
    private fun groups(paths:List<List<Pair<Double,Double>>>,ids:List<Int>,mode:String):List<StitchGroup> {
        val result=mutableListOf<StitchGroup>();var start:Point?=null
        val locks=LockStitches.fromParameters(LockStitchParameters())
        for(id in ids.distinct()) {
            if(result.isNotEmpty()) result[result.lastIndex]=result.last().copy(trimAfter=true)
            val shape=region(paths.filterIndexed {i,_->ids[i]==id})
            if(mode=="baseline") { result+=DefaultTatami.stitchGroups(shape,thread);continue }
            val initial=start
            val polygons=parts(shape.geometry).sortedBy {if(initial==null)it.envelopeInternal.minX else it.distance(it.factory.createPoint(org.locationtech.jts.geom.Coordinate(initial.x,initial.y)))}
            for((i,part) in polygons.withIndex()) {
                val end=polygons.getOrNull(i+1)?.let {DistanceOp.nearestPoints(part,it)[0].let {c->Point(c.x,c.y)}}
                for(underlay in listOf(true,false)) {
                    val settings=InkAutoFill.Settings(angleDegrees=if(underlay)90.0 else 0.0,rowSpacing=if(underlay)7.5 else 2.5,stitchLength=40.0,travelLength=25.0,staggers=4,underpath=mode!="source-underpath-off")
                    for(path in InkAutoFill.paths(PolygonRegion(part),settings,start,if(underlay)null else end)) {
                        if(path.isEmpty())continue
                        result+=StitchGroup(thread,path.map {Stitch(it.x*PIXELS_PER_MM/10,it.y*PIXELS_PER_MM/10)},lockStart=locks.start,lockEnd=locks.end)
                        start=path.last()
                    }
                }
            }
        }
        return result
    }
    /** Artifact-only P1/P4 lowering; no custom locks or changes to surviving needle coordinates. */
    private fun output(pattern:EmbPattern):EmbPattern {
        var current=pattern.stitches.takeWhile {it.command!=Command.END}
        var offsetX=0;var offsetY=0
        repeat(5) {
            val moves=current.filter {it.command==Command.STITCH||it.command==Command.JUMP}
            val minx=moves.minOf {it.x};val miny=moves.minOf {it.y}
            offsetX-=minx;offsetY-=miny
            val shifted=current.map {it.copy(x=it.x-minx,y=it.y-miny)}
            val next=mutableListOf<io.github.linker2k.myembro.engine.model.Stitch>();var x=0;var y=0
            for(s in shifted) {
                if(s.command==Command.STITCH||s.command==Command.JUMP) {
                    if(s.x==x&&s.y==y)continue
                    next+=s;x=s.x;y=s.y
                } else if(s.command==Command.TRIM) {
                    next+=s
                }
            }
            current=next.filterIndexed {i,s->s.command!=Command.TRIM || next.getOrNull(i+1)?.command==Command.JUMP}
        }
        return EmbPattern(current+current.last().copy(command=Command.END),listOf(thread),pattern.metadata+mapOf("trial.offsetX" to offsetX.toString(),"trial.offsetY" to offsetY.toString()))
    }
    private fun compose(dir: File) {
        val inputs=(1..6).map { PesReader.read(File(dir,"parts/KRSAMP$it.PES").readBytes()) }
        val bounds=inputs.map {p->p.stitches.filter {it.command==Command.STITCH||it.command==Command.JUMP}.let {points->intArrayOf(points.minOf {it.x},points.minOf {it.y},points.maxOf {it.x},points.maxOf {it.y})}}
        val right=899-bounds[5][2]
        val positions=listOf(0 to 0,right to 0,0 to 170,right to 170,0 to 390,right to (899-bounds[5][3]))
        // Axis-aligned sample rectangles must remain disjoint; no design rescaling or interleaving.
        for(i in inputs.indices) for(j in 0 until i) {
            val (x,y)=positions[i];val (u,v)=positions[j];val a=bounds[i];val b=bounds[j]
            check(x+a[2]<u || u+b[2]<x || y+a[3]<v || v+b[3]<y) { "Overlapping trial items $i $j" }
        }
        val stitches=mutableListOf<io.github.linker2k.myembro.engine.model.Stitch>()
        for(i in inputs.indices) {
            if(stitches.isNotEmpty())stitches+=stitches.last().copy(command=Command.TRIM)
            val (x,y)=positions[i]
            val moves=inputs[i].stitches.takeWhile {it.command!=Command.END}.map {it.copy(x=it.x+x,y=it.y+y)}
            // Input's existing initial move is preserved; no duplicate JUMP landing is added.
            val first=moves.first {it.command==Command.STITCH||it.command==Command.JUMP}
            if(first.command!=Command.JUMP)stitches+=first.copy(command=Command.JUMP)
            stitches+=moves
        }
        val combined=output(EmbPattern(stitches,listOf(thread)))
        val bytes=PesWriter.writeV6(combined);val decoded=PesReader.read(bytes)
        val points=decoded.stitches.filter {it.command==Command.STITCH||it.command==Command.JUMP}
        var previous=0 to 0;var zeros=0
        for(p in points) {if(previous==p.x to p.y)zeros++;previous=p.x to p.y}
        check(zeros==0 && points.minOf {it.x}==0 && points.minOf {it.y}==0)
        check(points.maxOf {it.x}<900 && points.maxOf {it.y}<900)
        check(decoded.threads.size==1 && decoded.colorChangeCount==0)
        File(dir,"KRSAMP1.PES").writeBytes(bytes)
        val layout=positions.mapIndexed {i,(x,y)->buildJsonObject {
            put("part",i+1);put("height_mm",listOf(15,15,20,20,25,25)[i]);put("kind",if(i%2==0)"fill" else "running")
            put("x_mm",x/10.0);put("y_mm",y/10.0);put("width_mm",bounds[i][2]/10.0);put("actual_height_mm",bounds[i][3]/10.0)
        }}
        File(dir,"layout.json").writeText(Json {prettyPrint=true}.encodeToString(JsonArray.serializer(),JsonArray(layout)))
        println("COMPOSED KRSAMP1.PES ${points.maxOf {it.x}/10.0}x${points.maxOf {it.y}/10.0}mm; zero=$zeros; threads=${decoded.threads.size}; stitches=${decoded.stitchCount}")
    }
    @JvmStatic fun main(args:Array<String>) {
        if(args[0]=="compose") {compose(File(args[1]));return}
        val out=File(args[1]).apply {mkdirs()};val font=Font.createFont(Font.TRUETYPE_FONT,File(args[0])).deriveFont(1000f)
        val (raw,ids)=paths(font,"정민");val reference=paths(font,"자").first.flatten().let {p->p.maxOf {it.second}-p.minOf {it.second}}
        val rows=mutableListOf<JsonObject>();var number=0
        for(height in listOf(15.0,20.0,25.0)) {
            val native=raw.map {p->p.map {(x,y)->x*height*10/reference to y*height*10/reference}}
            val shape=region(native).geometry
            val measure=StrokeWidthMeasurement.measure(AffineTransformation.scaleInstance(0.1,0.1).transform(shape))
            for(mode in listOf("baseline","source-underpath-on","source-underpath-off","running")) {
                val pattern=if(mode=="running"||mode=="baseline") LetteringEngine.fromContours(raw,height,sourceHeight=reference,contourCharacters=ids,outline=true,stitchStyle=if(mode=="running")StitchStyle.RUNNING else StitchStyle.FILL)
                    else GeneratedPatternProcessor.fromGroups(groups(native,ids,mode),omitZeroLengthMotions=true)
                val lowered=output(pattern);val bytes=PesWriter.writeV6(lowered)
                val decoded=PesReader.read(bytes);val motions=decoded.stitches.filter {it.command==Command.STITCH||it.command==Command.JUMP}
                val zeros=mutableListOf<Int>();var x=0;var y=0;var maxSquared=0;var repeats=0;var peak=0
                for((index,s) in decoded.stitches.withIndex()) if(s.command==Command.STITCH||s.command==Command.JUMP) {
                    if(s.x==x&&s.y==y)zeros+=index
                    repeats=if(s.x==x&&s.y==y)repeats+1 else 1;peak=max(peak,repeats)
                    if(s.command==Command.STITCH)maxSquared=max(maxSquared,(s.x-x).let {it*it}+(s.y-y).let {it*it})
                    x=s.x;y=s.y
                }
                val minx=motions.minOf {it.x};val miny=motions.minOf {it.y};val maxx=motions.maxOf {it.x};val maxy=motions.maxOf {it.y}
                val p=(0..3).sumOf {(bytes[8+it].toInt() and 255) shl(8*it)}
                fun u16(at:Int)=(bytes[at].toInt() and 255) or((bytes[at+1].toInt() and 255) shl 8)
                check(minx==0&&miny==0&&maxx<600&&maxy<600&&zeros.isEmpty()&&peak<=2&&maxSquared<=14400)
                check(u16(p+520)==maxx-minx&&u16(p+522)==maxy-miny)
                val name=if(mode=="baseline"||mode=="running") "KRSAMP${++number}" else "COMPARE-${height.toInt()}-$mode"
                File(out,"$name.PES").writeBytes(bytes)
                val report=SewoutCheck.checkPes(bytes,options=SewoutOptions(origin=DesignOrigin.APP_GENERATED))
                // sqrt(0.5²+0.5²) native-unit rounding allowance, rounded outward to0.72.
                val allowed=AffineTransformation.translationInstance(lowered.metadata.getValue("trial.offsetX").toDouble(),lowered.metadata.getValue("trial.offsetY").toDouble()).transform(shape).buffer(0.72)
                var bridgeCount=0;var bridgeLength=0.0;var cursor=org.locationtech.jts.geom.Coordinate(0.0,0.0)
                for(s in decoded.stitches) if(s.command==Command.STITCH||s.command==Command.JUMP) {
                    val target=org.locationtech.jts.geom.Coordinate(s.x.toDouble(),s.y.toDouble())
                    if(s.command==Command.STITCH && allowed.covers(allowed.factory.createPoint(cursor)) && allowed.covers(allowed.factory.createPoint(target))) {
                        val line=allowed.factory.createLineString(arrayOf(cursor,target))
                        if(!allowed.covers(line)) {
                            val outside=org.locationtech.jts.operation.overlayng.OverlayNGRobust.overlay(line,allowed,org.locationtech.jts.operation.overlayng.OverlayNG.DIFFERENCE).length/10
                            if(outside>0) {bridgeCount++;bridgeLength+=outside}
                        }
                    }
                    cursor=target
                }
                val jumps=decoded.stitches.withIndex().filter {it.value.command==Command.JUMP}
                var prior=io.github.linker2k.myembro.engine.model.Stitch(0,0,Command.JUMP)
                val jumpLength=decoded.stitches.sumOf {s->val d=if(s.command==Command.JUMP)hypot((s.x-prior.x).toDouble(),(s.y-prior.y).toDouble())/10 else 0.0;prior=s;d}
                rows+=buildJsonObject {
                    put("file","$name.PES");put("height_mm",height);put("mode",mode);put("width_mm",maxx/10.0);put("actual_height_mm",maxy/10.0)
                    put("sewn_outside_ink_bridge_count",bridgeCount);put("sewn_outside_ink_bridge_mm",bridgeLength)
                    put("stitches",decoded.stitchCount);put("jumps",jumps.size);put("jump_length_mm",jumpLength);put("trims",decoded.trimCount)
                    put("minimum_sampled_width_mm",measure.minimumSampledMm);put("p10_sampled_width_mm",measure.p10Mm);put("median_sampled_width_mm",measure.medianMm);put("maximum_sampled_width_mm",measure.maximumSampledMm);put("width_samples",measure.samples)
                    put("W",JsonNull);put("zero_motions",zeros.size);put("min_x",minx);put("min_y",miny);put("peak_repeat",peak);put("maximum_stitch_mm",sqrt(maxSquared.toDouble())/10)
                    put("kotlin_blockers",JsonArray(report.findings.filter {it.severity==Severity.BLOCKER}.map {JsonPrimitive(it.code.name)}))
                    put("density_peak",report.statistics.peakCellPenetrations)
                }
                println("TRIAL $name $height $mode stitches=${decoded.stitchCount} jumps=${jumps.size} trims=${decoded.trimCount} widthMin=${measure.minimumSampledMm} p10=${measure.p10Mm}")
            }
        }
        File(out,"measurements.json").writeText(Json {prettyPrint=true}.encodeToString(JsonArray.serializer(),JsonArray(rows)))
    }
}
