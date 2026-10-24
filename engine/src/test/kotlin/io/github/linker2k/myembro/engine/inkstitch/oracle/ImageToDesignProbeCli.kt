// SPDX-License-Identifier: GPL-3.0-or-later
// Experimental integration only; algorithms remain in their separately attributed Wu,
// Potrace, AutoTrace and Ink/Stitch ports. Never connected to the app.
package io.github.linker2k.myembro.engine.inkstitch.oracle

import io.github.linker2k.myembro.engine.geometry.*
import io.github.linker2k.myembro.engine.inkstitch.elements.*
import io.github.linker2k.myembro.engine.inkstitch.elements.utils.StrokeToSatin
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.*
import io.github.linker2k.myembro.engine.inkstitch.stitches.AutoSatin
import io.github.linker2k.myembro.engine.inkstitch.svg.Units
import io.github.linker2k.myembro.engine.inkstitch.svg.PIXELS_PER_MM
import io.github.linker2k.myembro.engine.inkstitch.svg.image.*
import io.github.linker2k.myembro.engine.inkstitch.svg.image.autotrace.*
import io.github.linker2k.myembro.engine.inkstitch.utils.Point as StitchPoint
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.lettering.TatamiLettering
import io.github.linker2k.myembro.engine.lettering.StitchStyle
import io.github.linker2k.myembro.engine.sewout.*
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.util.AffineTransformation
import org.locationtech.jts.geom.prep.PreparedGeometryFactory
import kotlinx.serialization.json.*
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO

/** Representative experiment, not approval of automatic width selection on arbitrary images. */
object ImageToDesignProbeCli {
    @JvmStatic fun main(args:Array<String>) {
        require(args.size in 2..3) { "INPUT_DIRECTORY OUTPUT_DIRECTORY [COLORS=4]" }
        val colorCount=args.getOrNull(2)?.toInt() ?: 4
        require(colorCount in 2..6)
        val out=File(args[1]).apply { mkdirs() }
        val rows=File(args[0]).listFiles()!!.filter { it.extension=="png" }.sortedBy { it.name }.map { file ->
            val start=System.nanoTime()
            val image=ImageIO.read(file); val w=image.width; val h=image.height
            require(w in 1..512 && h in 1..512)
            val argb=image.getRGB(0,0,w,h,null,0,w)
            // Explicit transparent PNG contract: exclude fully transparent pixels, reject partial alpha.
            require(argb.all { it ushr 24 == 0 || it ushr 24 == 255 })
            val opaque=argb.indices.filter { argb[it] ushr 24 == 255 }
            require(opaque.isNotEmpty() && argb[0] ushr 24 == 0)
            val quantized=ImageQuantizer.quantize(opaque.map { argb[it] and 0xffffff }.toIntArray(), colorCount)
            val labels=IntArray(argb.size) { -1 }; val indices=quantized.copyIndices()
            opaque.forEachIndexed { i,p -> labels[p]=indices[i] }
            val quantizedAt=System.nanoTime()
            val groups=mutableListOf<StitchGroup>(); val regions=mutableListOf<JsonObject>()
            val mmPerPixel=30.0/w
            var traceNanos=0L; var widthNanos=0L; var stitchNanos=0L
            quantized.palette.forEachIndexed { color,rgb ->
                val thread=PecThreads.SET.filterNotNull().minBy { PecThreads.distance(rgb,it.color) }
                val traceStart=System.nanoTime()
                val outline=RedworkPipeline.trace(w,h,IntArray(labels.size) { if(labels[it]==color) 0 else 0xffffff },30.0,0,thread)
                val region=PolygonRegion.fromPaths(outline.paths.flatMap { path ->
                    SvgPath.flatten(path,tolerance=Units.mmToPixels(0.1)).map { line ->
                        Polyline(line.points.map { Point(it.x*10/PIXELS_PER_MM,it.y*10/PIXELS_PER_MM) },true)
                    }
                },FillRule.EVEN_ODD)
                traceNanos+=System.nanoTime()-traceStart
                for(i in 0 until region.geometry.numGeometries) {
                    val shape=region.geometry.getGeometryN(i)
                    val widthStart=System.nanoTime()
                    val width=StrokeWidthMeasurement.measure(AffineTransformation.scaleInstance(.1,.1).transform(shape)).medianMm
                    val style=TatamiLettering.methodForWidth(width)
                    widthNanos+=System.nanoTime()-widthStart
                    val stitchStart=System.nanoTime()
                    val next=if(style==StitchStyle.FILL) DefaultTatami.stitchGroups(PolygonRegion(shape),thread) else {
                        val prepared=PreparedGeometryFactory.prepare(shape)
                        val bitmap=ByteArray(labels.size) { p ->
                            val inside=labels[p]==color && prepared.covers(shape.factory.createPoint(Coordinate(
                                (p%w+.5-w/2.0)*mmPerPixel*10,(p/w+.5-h/2.0)*mmPerPixel*10)))
                            if(inside) 0 else 255.toByte()
                        }
                        val fit=AutoTraceFit.fit(AutoTraceCenterline.trace(w,h,AutoTraceThinning.thin(w,h,bitmap)))
                        val paths=fit.map { path ->
                            fun v(s:AutoTraceFit.Segment,n:Int,axis:Int):String {
                                val p=s.v[n]
                                return String.format(Locale.ROOT,"%.6g",if(axis==0) p.x else h.toFloat()-p.y)
                            }
                            val d=buildString {
                                val first=path.segments.first(); append("M ${v(first,0,0)} ${v(first,0,1)} ")
                                for(s in path.segments) if(s.degree==1) append("L ${v(s,3,0)} ${v(s,3,1)} ")
                                else append("C ${v(s,1,0)} ${v(s,1,1)} ${v(s,2,0)} ${v(s,2,1)} ${v(s,3,0)} ${v(s,3,1)} ")
                            }
                            SvgPath.flatten(d,Affine2D.scale(mmPerPixel*PIXELS_PER_MM,mmPerPixel*PIXELS_PER_MM),tolerance=.1)
                                .single().points.map { StitchPoint(it.x-w/2.0*mmPerPixel*PIXELS_PER_MM,it.y-h/2.0*mmPerPixel*PIXELS_PER_MM) }
                        }
                        require(paths.isNotEmpty()) { "No centerline: input not silently discarded" }
                        if(style==StitchStyle.RUNNING) Stroke(paths,thread).toStitchGroups(null) else {
                            val sections=paths.map { path ->
                                val started=if(path.first()==path.last()) StrokeToSatin.setFirstNode(path,width*PIXELS_PER_MM) else path
                                StrokeToSatin.filteredRungs(requireNotNull(StrokeToSatin.convertPathToSatin(started,width*PIXELS_PER_MM)))
                            }
                            val routed=AutoSatin.route(sections,thread)
                            require(routed.discardedSatins==0)
                            routed.groups
                        }
                    }
                    require(next.isNotEmpty())
                    if(groups.isNotEmpty()) groups[groups.lastIndex]=groups.last().copy(trimAfter=true)
                    groups+=next
                    stitchNanos+=System.nanoTime()-stitchStart
                    regions+=buildJsonObject { put("palette_index",color);put("thread",thread.hex);put("median_width_mm",width);put("style",style.name);put("groups",next.size) }
                }
            }
            val pattern=GeneratedPatternProcessor.fromGroups(groups,pecJumpTrims=true,lockAtThreadBoundaries=true,omitZeroLengthMotions=true)
            val bytes=PesWriter.write(pattern,PesVersion.V6)
            File(out,file.nameWithoutExtension+".pes").writeBytes(bytes)
            val viewport=PecViewportCheck.check(bytes,HoopSpec.H100)
            val report=SewoutCheck.checkPes(bytes,options=SewoutOptions(origin=DesignOrigin.APP_GENERATED))
            buildJsonObject {
                put("input",file.name);put("requested_colors",colorCount);put("palette_colors",quantized.palette.size)
                put("regions",JsonArray(regions));put("region_count",regions.size);put("stitch_count",pattern.stitchCount)
                put("quantize_ms",(quantizedAt-start)/1e6);put("trace_ms",traceNanos/1e6);put("width_ms",widthNanos/1e6);put("stitch_ms",stitchNanos/1e6)
                put("total_ms",(System.nanoTime()-start)/1e6);put("viewport_valid",viewport.valid)
                put("gate",report.verdict.name);put("blocking",report.hasBlockingIssues)
                put("findings",buildJsonObject { report.findingCounts.forEach { (code,count) -> put(code.name,count) } })
            }.also { println(it) }
        }
        File(out,"results.json").writeText(Json { prettyPrint=true }.encodeToString(JsonArray.serializer(),JsonArray(rows)))
    }
}
