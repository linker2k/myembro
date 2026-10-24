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
    data class Layer(val paletteIndex:Int,val thread: io.github.linker2k.myembro.engine.model.EmbThread, val outline: RedworkOutline)
    class Preview internal constructor(val width:Int,val height:Int,private val labels:IntArray,
        val palette:List<Int>,val layers:List<Layer>,val canvasWidthMm:Double,val inkOutline:RedworkOutline) {
        fun previewPixels()=IntArray(labels.size) { if(labels[it]<0) 0 else palette[labels[it]] or 0xff000000.toInt() }
        internal fun indices()=labels
    }
    data class Region(val thread:io.github.linker2k.myembro.engine.model.EmbThread,
        val widths:StitchTypeRule.Widths,val method:StitchTypeRule.Type,val groups:Int)
    data class Result(val pattern:io.github.linker2k.myembro.engine.model.EmbPattern,val regions:List<Region>)

    /** Fully transparent pixels consume no thread. Partial alpha is composited over white.
     * For an opaque source the top-left quantized color is the caller-selected background.
     */
    fun trace(width:Int,height:Int,argb:IntArray,widthMm:Double,colorCount:Int,
        minAreaMm2:Double=0.0,maxContours:Int=128,checkpoint:(String)->Unit={}):Preview {
        require(width in 1..512 && height in 1..512 && width*height==argb.size)
        require(widthMm.isFinite() && widthMm>0 && colorCount in 2..6)
        checkpoint("quantization")
        val opaque=argb.indices.filter { argb[it] ushr 24 != 0 }
        require(opaque.isNotEmpty()) { "No visible pixels" }
        fun rgb(p:Int):Int {
            val a=p ushr 24
            fun channel(shift:Int)=(((p ushr shift) and 255)*a+255*(255-a)+127)/255
            return (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }
        val quantized=ImageQuantizer.quantize(opaque.map { rgb(argb[it]) }.toIntArray(),colorCount)
        val original=IntArray(argb.size) { -1 };val q=quantized.copyIndices()
        opaque.forEachIndexed { i,p -> original[p]=q[i] }
        val background=if(opaque.size==argb.size) original[0] else -1
        // One explicit transparent border lets Potrace trace ink touching any crop edge.
        val w=width+2;val h=height+2
        val labels=IntArray(w*h) { p ->
            val x=p%w-1;val y=p/w-1
            if(x in 0 until width && y in 0 until height) original[y*width+x].takeIf { it!=background } ?: -1 else -1
        }
        val paddedWidth=widthMm*w/width
        val layers=quantized.palette.mapIndexedNotNull { color,rgb ->
            checkpoint("tracing")
            if(labels.none { it==color }) null else {
                val thread=io.github.linker2k.myembro.engine.inkstitch.svg.VectorArt.nearestThread(rgb)
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

    fun generate(preview:Preview,checkpoint:(String)->Unit={}):Result {
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

                    regions+=Region(thread,widths,method,next.size)
                }
            }
        require(groups.isNotEmpty())
        val pattern=GeneratedPatternProcessor.fromGroups(groups,pecJumpTrims=true,lockAtThreadBoundaries=true,omitZeroLengthMotions=true)
        return Result(pattern,regions)
    }
}
