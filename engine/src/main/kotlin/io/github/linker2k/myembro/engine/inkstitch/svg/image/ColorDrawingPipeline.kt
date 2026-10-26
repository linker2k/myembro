// SPDX-License-Identifier: GPL-3.0-or-later
// Integration of the separately attributed Wu, Potrace, AutoTrace and Ink/Stitch ports.
package io.github.linker2k.myembro.engine.inkstitch.svg.image

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
import io.github.linker2k.myembro.engine.inkstitch.svg.StitchTypeRule
import io.github.linker2k.myembro.engine.lettering.StitchStyle
import io.github.linker2k.myembro.engine.sewout.*
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.util.AffineTransformation
import org.locationtech.jts.geom.prep.PreparedGeometryFactory
import java.util.Locale


/** Two-phase on-device color drawing conversion: tracing never generates stitches. */
object ColorDrawingPipeline {
    /** Smallest tested threshold preserving original colors and removing AA/specks in the
     * 2 original 30mm drawings × 5 area candidates (ColorCleanupSweep). Not a universal guarantee. */
    const val MINIMUM_REGION_AREA_MM2 = 3.0
    data class Layer(val paletteIndex:Int,val thread: io.github.linker2k.myembro.engine.model.EmbThread, val outline: RedworkOutline)
    class Preview internal constructor(val width:Int,val height:Int,private val labels:IntArray,
        val palette:List<Int>,val layers:List<Layer>,val canvasWidthMm:Double,val inkOutline:RedworkOutline) {
        fun previewPixels()=IntArray(labels.size) { if(labels[it]<0) 0 else palette[labels[it]] or 0xff000000.toInt() }
        internal fun indices()=labels
    }
    data class Region(val thread:io.github.linker2k.myembro.engine.model.EmbThread,
        val widths:StitchTypeRule.Widths,val method:StitchTypeRule.Type,val groups:Int,val excluded:Boolean=false,
        /** Actual Potrace polygon rings, in object-local mm; holes are retained. */
        val paths:List<Polyline> = emptyList())
    data class Result(val pattern:io.github.linker2k.myembro.engine.model.EmbPattern,val regions:List<Region>) {
        val excludedCount get()=regions.count { it.excluded }
    }

    /** Transparent pixels consume no thread. Straight RGB preserves the pigment of partial-alpha
     * PNG edges instead of introducing a white-composited extra thread. Border-label mode removes
     * opaque background before the requested N-color quantization.
     */
    fun trace(width:Int,height:Int,argb:IntArray,widthMm:Double,colorCount:Int,
        minAreaMm2:Double=MINIMUM_REGION_AREA_MM2,maxContours:Int=128,grayscale:Boolean=false,myThreads:io.github.linker2k.myembro.engine.io.MyThreads=io.github.linker2k.myembro.engine.io.MyThreads(),checkpoint:(String)->Unit={}):Preview {
        require(width in 1..512 && height in 1..512 && width*height==argb.size)
        require(widthMm.isFinite() && widthMm>0 && colorCount in 2..6)
        checkpoint("quantization")
        val pixels=if(grayscale) IntArray(argb.size) { Bt709Grayscale.argb(argb[it]) } else argb
        val opaque=pixels.indices.filter { pixels[it] ushr 24 != 0 }
        require(opaque.isNotEmpty()) { "No visible pixels" }
        val preliminary=ImageQuantizer.quantize(opaque.map { pixels[it] and 0xffffff }.toIntArray(),colorCount+1)
        val first=IntArray(argb.size) { -1 };val firstIndices=preliminary.copyIndices()
        opaque.forEachIndexed { i,p -> first[p]=firstIndices[i] }
        val background=if(opaque.size<argb.size) -1 else ColorRegionCleanup.borderMode(width,height,first)
        val foreground=opaque.filter { background<0 || first[it]!=background }
        require(foreground.isNotEmpty()) { "No foreground after border background removal" }
        val quantized=ImageQuantizer.quantize(foreground.map { pixels[it] and 0xffffff }.toIntArray(),colorCount)
        val original=IntArray(argb.size) { -1 };val q=quantized.copyIndices()
        foreground.forEachIndexed { i,p -> original[p]=q[i] }
        val cleaned=ColorRegionCleanup.mergeSmall(width,height,original,
            (widthMm/width)*(widthMm/width),minAreaMm2)
        // One explicit transparent border lets Potrace trace ink touching any crop edge.
        val w=width+2;val h=height+2
        val labels=IntArray(w*h) { p ->
            val x=p%w-1;val y=p/w-1
            if(x in 0 until width && y in 0 until height) cleaned[y*width+x] else -1
        }
        val paddedWidth=widthMm*w/width
        val layers=quantized.palette.mapIndexedNotNull { color,rgb ->
            checkpoint("tracing")
            if(labels.none { it==color }) null else {
                val thread=if(myThreads.active) myThreads.nearest(rgb) else if(grayscale) Bt709Grayscale.nearestThread(rgb) else io.github.linker2k.myembro.engine.inkstitch.svg.VectorArt.nearestThread(rgb)
                try {
                    Layer(color,thread,RedworkPipeline.trace(w,h,IntArray(labels.size) { if(labels[it]==color) 0 else 0xffffff },
                        paddedWidth,0,thread,minAreaMm2,maxContours,checkpoint=checkpoint))
                } catch(failure:IllegalArgumentException) {
                    if(failure.message?.startsWith("No contour remains")==true) null else throw failure
                }
            }
        }
        require(layers.isNotEmpty()) { "No color regions remain" }
        require(layers.sumOf { it.outline.contourCount }<=maxContours) { "PHOTO_TOO_COMPLEX" }
        val paths=layers.flatMap { it.outline.paths }
        val ink=RedworkOutline("",layers.sumOf { it.outline.contourCount },widthMm,widthMm*height/width,paths,
            layers.sumOf { it.outline.foregroundAreaMm2 })
        return Preview(w,h,labels,quantized.palette,layers,paddedWidth,ink)
    }

    fun generate(preview:Preview,hoop:HoopSpec=HoopSpec.H100,checkpoint:(String)->Unit={}):Result {
        val w=preview.width;val h=preview.height;val labels=preview.indices()
        val mmPerPixel=preview.canvasWidthMm/w
        val groups=mutableListOf<StitchGroup>();val regions=mutableListOf<Region>()
        for(layer in preview.layers) {
            checkpoint("stitches")
            val outline=layer.outline;val thread=layer.thread
            val color=layer.paletteIndex
                val region=PolygonRegion.fromPaths(outline.paths.flatMap { path ->
                    SvgPath.flatten(path,tolerance=Units.mmToPixels(0.1)).map { line ->
                        Polyline(line.points.map { Point(it.x*10/PIXELS_PER_MM,it.y*10/PIXELS_PER_MM) },true)
                    }
                },FillRule.EVEN_ODD)

                for(i in 0 until region.geometry.numGeometries) {
                    val shape=region.geometry.getGeometryN(i)
                    checkpoint("widths")
                    val widths=StitchTypeRule.widths(shape,10.0)
                    val width=widths.medianMm
                    val method=StitchTypeRule.classify(widths)
                    val style=when(method) {
                        StitchTypeRule.Type.TATAMI -> StitchStyle.FILL
                        StitchTypeRule.Type.SATIN -> StitchStyle.SATIN
                        StitchTypeRule.Type.RUNNING -> StitchStyle.RUNNING
                    }

                    checkpoint("stitches")
                    val resolved=StitchTypeRule.generateRegion(method,if(groups.isEmpty()) groups else groups.dropLast(1)+groups.last().copy(trimAfter=true),hoop) { attempt ->
                        if(attempt==StitchTypeRule.Type.TATAMI) DefaultTatami.stitchGroups(PolygonRegion(shape),thread)
                        else if(attempt==StitchTypeRule.Type.RUNNING && method!=StitchTypeRule.Type.RUNNING) {
                            val polygon=shape as org.locationtech.jts.geom.Polygon
                            val rings=listOf(polygon.exteriorRing)+(0 until polygon.numInteriorRing).map(polygon::getInteriorRingN)
                            Stroke(rings.map { ring -> ring.coordinates.map { StitchPoint(it.x/10*PIXELS_PER_MM,it.y/10*PIXELS_PER_MM) } },thread).toStitchGroups(null)
                        } else {
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
                        if(attempt==StitchTypeRule.Type.RUNNING) Stroke(paths,thread).toStitchGroups(null) else {
                            val sections=paths.map { path ->
                                val started=if(path.first()==path.last()) StrokeToSatin.setFirstNode(path,width*PIXELS_PER_MM) else path
                                StrokeToSatin.filteredRungs(requireNotNull(StrokeToSatin.convertPathToSatin(started,width*PIXELS_PER_MM)))
                            }
                            val routed=AutoSatin.route(sections,thread)
                            require(routed.discardedSatins==0)
                            routed.groups
                        }
                    }
                    }
                    val next=resolved.groups
                    if(next.isNotEmpty() && groups.isNotEmpty()) groups[groups.lastIndex]=groups.last().copy(trimAfter=true)
                    groups+=next

                    val polygon=shape as org.locationtech.jts.geom.Polygon
                    val sourcePaths=(listOf(polygon.exteriorRing)+(0 until polygon.numInteriorRing).map(polygon::getInteriorRingN))
                        .map { ring -> Polyline(ring.coordinates.map { Point(it.x/10.0,it.y/10.0) },true) }
                    regions+=Region(thread,widths,resolved.type ?: method,next.size,resolved.excluded,sourcePaths)
                }
            }
        val finished=if(groups.isEmpty()) io.github.linker2k.myembro.engine.model.EmbPattern(emptyList(),emptyList())
            else GeneratedPatternProcessor.fromGroups(groups,pecJumpTrims=true,lockAtThreadBoundaries=true,omitZeroLengthMotions=true)
        val pattern=io.github.linker2k.myembro.engine.model.EmbPattern(finished.stitches,finished.threads,
            finished.metadata+mapOf("myembro.excluded_regions" to regions.count { it.excluded }.toString()),finished.warnings)
        return Result(pattern,regions)
    }
}
