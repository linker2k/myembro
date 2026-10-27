// SPDX-License-Identifier: GPL-3.0-or-later
// Integration of the separately attributed Wu, Potrace, AutoTrace and Ink/Stitch ports.
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import io.github.linker2k.myembro.engine.geometry.*
import io.github.linker2k.myembro.engine.inkstitch.elements.*
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.*
import io.github.linker2k.myembro.engine.inkstitch.svg.Units
import io.github.linker2k.myembro.engine.inkstitch.svg.PIXELS_PER_MM
import io.github.linker2k.myembro.engine.inkstitch.svg.image.*
import io.github.linker2k.myembro.engine.inkstitch.utils.Point as StitchPoint
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.inkstitch.svg.StitchTypeRule
import io.github.linker2k.myembro.engine.sewout.*


/** Two-phase on-device color drawing conversion: tracing never generates stitches. */
object ColorDrawingPipeline {
    /** Smallest tested threshold preserving original colors and removing AA/specks in the
     * 2 original 30mm drawings × 5 area candidates (ColorCleanupSweep). Not a universal guarantee. */
    const val MINIMUM_REGION_AREA_MM2 = 3.0
    data class Layer(val paletteIndex:Int,val thread: io.github.linker2k.myembro.engine.model.EmbThread, val outline: RedworkOutline)
    class Preview internal constructor(val width:Int,val height:Int,private val labels:IntArray,
        val palette:List<Int>,val layers:List<Layer>,val canvasWidthMm:Double,val inkOutline:RedworkOutline,private val foregroundPixels:BooleanArray) {
        // Raw pixels are retained only as an independent background-hole measurement reference.
        internal val backgroundReferenceRegion:PolygonRegion by lazy {
            PictureForegroundMask.polygonize(width,height,foregroundPixels,canvasWidthMm*10/width)
        }
        internal val colorRegionUnion:PolygonRegion by lazy {
            PictureFillMask.union(layers.map { it.outline })
        }
        /** Show the resolved sewing threads, including an active owned-thread restriction. */
        fun previewPixels(): IntArray {
            val colors=layers.associate { it.paletteIndex to (it.thread.color or 0xff000000.toInt()) }
            return IntArray(labels.size) { colors[labels[it]] ?: 0 }
        }
        internal fun indices()=labels
    }
    data class Region(val thread:io.github.linker2k.myembro.engine.model.EmbThread,
        val widths:StitchTypeRule.Widths,val method:StitchTypeRule.Type,val groups:Int,val excluded:Boolean=false,
        /** Actual Potrace polygon rings, in object-local mm; holes are retained. */
        val paths:List<Polyline> = emptyList(), val outlineSkipped:Boolean=false)
    data class Result(val pattern:io.github.linker2k.myembro.engine.model.EmbPattern,val regions:List<Region>,
        val regionPatterns:Map<String,io.github.linker2k.myembro.engine.model.EmbPattern> = emptyMap()) {
        val excludedCount get()=regions.count { it.excluded }
        val skippedOutlineCount get()=regions.count { it.outlineSkipped }
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
        val selection=ColorRegionCleanup.selectBackground(width,height,pixels,colorCount)
        require(selection.visible.isNotEmpty()) { "No visible pixels" }
        val foreground=selection.foreground
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
        val foregroundPixels=BooleanArray(w*h)
        for(index in foreground) foregroundPixels[(index/width+1)*w+index%width+1]=true
        return Preview(w,h,labels,quantized.palette,layers,paddedWidth,ink,foregroundPixels)
    }

    fun generate(preview:Preview,hoop:HoopSpec=HoopSpec.H100,checkpoint:(String)->Unit={}):Result =
        generate(preview,hoop,expandMm=0.0,checkpoint=checkpoint)

    /** Optional Ink/Stitch fill expansion; no application default is changed here. */
    fun generate(preview:Preview,hoop:HoopSpec=HoopSpec.H100,expandMm:Double,checkpoint:(String)->Unit={}):Result {
        return generate(preview,hoop,true,checkpoint,expandMm=expandMm)
    }
    internal fun generate(preview:Preview,hoop:HoopSpec,captureRegions:Boolean,checkpoint:(String)->Unit={},includeFillOutlines:Boolean=true,expandMm:Double=0.0):Result {
        require(expandMm.isFinite())
        // 0 must retain the original, unbuffered/unclipped route byte-for-byte.
        val clipRegion=if(expandMm==0.0) null else PictureFillMask.closing(preview.colorRegionUnion,expandMm)
        val groups=mutableListOf<StitchGroup>();val regions=mutableListOf<Region>()
        val outlines=mutableListOf<ColorFillOutline.Outline>()
        for(layer in preview.layers) {
            checkpoint("stitches")
            val outline=layer.outline;val thread=layer.thread
                val region=PolygonRegion.fromPaths(outline.paths.flatMap { path ->
                    SvgPath.flatten(path,tolerance=Units.mmToPixels(0.1)).map { line ->
                        Polyline(line.points.map { Point(it.x*10/PIXELS_PER_MM,it.y*10/PIXELS_PER_MM) },true)
                    }
                },FillRule.EVEN_ODD)

                for(i in 0 until region.geometry.numGeometries) {
                    val shape=region.geometry.getGeometryN(i)
                    checkpoint("widths")
                    val widths=StitchTypeRule.widths(shape,10.0)
                    // Painted areas retain their polygon shape through the same default fill as
                    // silhouette. Only failed fill generation follows the existing running fallback.
                    val method=StitchTypeRule.Type.TATAMI
                    checkpoint("stitches")
                    val resolved=StitchTypeRule.generateRegion(method,if(groups.isEmpty()) groups else groups.dropLast(1)+groups.last().copy(trimAfter=true),hoop) { attempt ->
                        if(attempt==StitchTypeRule.Type.TATAMI) DefaultTatami.stitchGroups(PolygonRegion(shape),thread,expandMm=expandMm,
                            clipRegion=clipRegion)
                        else {
                            val polygon=shape as org.locationtech.jts.geom.Polygon
                            val rings=listOf(polygon.exteriorRing)+(0 until polygon.numInteriorRing).map(polygon::getInteriorRingN)
                            Stroke(rings.map { ring -> ring.coordinates.map { StitchPoint(it.x/10*PIXELS_PER_MM,it.y/10*PIXELS_PER_MM) } },thread).toStitchGroups(null)
                        }
                    }
                    val next=resolved.groups
                    if(next.isNotEmpty() && groups.isNotEmpty()) groups[groups.lastIndex]=groups.last().copy(trimAfter=true)
                    val tag="myembro.region:region-${regions.count { !it.excluded && it.groups>0 }}"
                    fun tagged(lock:LockStitch?) = LockStitch { stitches,position ->
                        (lock ?: InkLockStitch()).stitches(stitches,position).map { it.copy(tags=it.tags+tag) }
                    }
                    fun tagGroups(input:List<StitchGroup>) = if(captureRegions) input.map { group -> group.copy(
                        stitches=group.stitches.map { it.copy(tags=it.tags+tag) },
                        lockStart=tagged(group.lockStart),lockEnd=tagged(group.lockEnd)) } else input
                    groups+=tagGroups(next)
                    if(includeFillOutlines && resolved.type==StitchTypeRule.Type.TATAMI)
                        outlines+=ColorFillOutline.Outline(groups.size,regions.size,tagGroups(ColorFillOutline.stitchGroups(shape as org.locationtech.jts.geom.Polygon,thread)))

                    val polygon=shape as org.locationtech.jts.geom.Polygon
                    val sourcePaths=(listOf(polygon.exteriorRing)+(0 until polygon.numInteriorRing).map(polygon::getInteriorRingN))
                        .map { ring -> Polyline(ring.coordinates.map { Point(it.x/10.0,it.y/10.0) },true) }
                    regions+=Region(thread,widths,resolved.type ?: method,next.size,resolved.excluded,sourcePaths)
                }
            }
        val selection=ColorFillOutline.select(groups,outlines,hoop) { checkpoint("outline validation") }
        val selectedGroups=selection.groups.toList()
        groups.clear();groups.addAll(selectedGroups)
        for(regionIndex in selection.skipped) regions[regionIndex]=regions[regionIndex].copy(outlineSkipped=true)
        val owners=mutableListOf<String?>()
        val finished=if(groups.isEmpty()) io.github.linker2k.myembro.engine.model.EmbPattern(emptyList(),emptyList())
            else GeneratedPatternProcessor.fromGroups(groups,pecJumpTrims=true,lockAtThreadBoundaries=true,omitZeroLengthMotions=true,stitchOwners=owners)
        val pattern=io.github.linker2k.myembro.engine.model.EmbPattern(finished.stitches,finished.threads,
            finished.metadata+mapOf("myembro.excluded_regions" to regions.count { it.excluded }.toString(),
                "myembro.skipped_fill_outlines" to regions.count { it.outlineSkipped }.toString(),
                "myembro.fill_outline_checks" to selection.checks.toString()),finished.warnings)
        val threads=regions.filter { !it.excluded && it.groups>0 }.mapIndexed { i,r -> "region-$i" to r.thread }.toMap()
        val fragments=if(captureRegions && groups.isNotEmpty()) io.github.linker2k.myembro.engine.inkstitch.svg.RegionOrderPatterns.split(finished,owners,threads) else emptyMap()
        return Result(pattern,regions,fragments)
    }
}
