// SPDX-License-Identifier: GPL-3.0-or-later
// Integration of the separately attributed Wu, Potrace, AutoTrace and Ink/Stitch ports.
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import io.github.linker2k.myembro.engine.geometry.*
import io.github.linker2k.myembro.engine.color.DesignColor
import io.github.linker2k.myembro.engine.color.ThreadAssignment
import io.github.linker2k.myembro.engine.inkstitch.elements.*
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.*
import io.github.linker2k.myembro.engine.inkstitch.svg.Units
import io.github.linker2k.myembro.engine.inkstitch.svg.PIXELS_PER_MM
import io.github.linker2k.myembro.engine.inkstitch.svg.image.*
import io.github.linker2k.myembro.engine.inkstitch.utils.Point as StitchPoint
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.inkstitch.svg.StitchTypeRule
import io.github.linker2k.myembro.engine.inkstitch.svg.SmallRegionStitches
import io.github.linker2k.myembro.engine.sewout.*


/** Two-phase on-device color drawing conversion: tracing never generates stitches. */
object ColorDrawingPipeline {
    /** Legacy cleanup threshold, retained for API compatibility. User policy now preserves small paint;
     * it is no longer used for merging colors or removing contours. */
    const val MINIMUM_REGION_AREA_MM2 = 3.0
    /** User approved 2026-10-27: /tmp/myembro-expand2/table-closing.md, proxy p50 0.55mm,
     * gap 8.96→5.78%, unchanged extent and PASS gates; 0.20mm² background-hole loss accepted.
     * Applies only to color-picture fill, with the existing Potrace-union closing clip.
     */
    const val DEFAULT_EXPAND_MM = 0.2
    /** Unchanged mobile work budget (docs/qa/photo-silhouette-performance.md) for 1024px regions:
     * 120/127 sample-pack inputs fit at 99.9mm; the largest (275) took 143s to generate on a PC
     * and exceeded the 30,000-stitch sew-out limit (tools/color-trace/README.md). */
    const val MAX_REGION_CONTOURS = 128
    data class Layer(val paletteIndex:Int,val thread: io.github.linker2k.myembro.engine.model.EmbThread, val outline: RedworkOutline)
    class Preview internal constructor(val width:Int,val height:Int,private val labels:IntArray,
        val palette:List<Int>,val layers:List<Layer>,val canvasWidthMm:Double,val inkOutline:RedworkOutline,private val foregroundPixels:BooleanArray, val assignmentWarnings:List<ThreadAssignment.Shortage> = emptyList()) {
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
        require(minAreaMm2.isFinite() && minAreaMm2>=0)
        // Legacy area argument remains source-compatible; paint is no longer merged away by area.
        return preview(width,height,original,quantized.palette,widthMm,minAreaMm2,maxContours,grayscale,myThreads,checkpoint,binaryMask=false)
    }

    /** Color drawing from the sample-pack color regions ([SampleColorRegions]) at up to 1024px;
     * contours, thread assignment and stitches follow the same route as [trace]. */
    fun traceRegions(width:Int,height:Int,argb:IntArray,widthMm:Double,colorCount:Int,
        maxContours:Int=MAX_REGION_CONTOURS,myThreads:io.github.linker2k.myembro.engine.io.MyThreads=io.github.linker2k.myembro.engine.io.MyThreads(),checkpoint:(String)->Unit={}):Preview {
        val regions=SampleColorRegions.labels(width,height,argb,widthMm,colorCount,checkpoint=checkpoint)
        require(regions.palette.isNotEmpty()) { "No foreground after border background removal" }
        // 1026² padded masks exceed Wu's 1M-pixel input; the explicit mask tracer needs no quantization.
        return preview(width,height,regions.labels,regions.palette,widthMm,MINIMUM_REGION_AREA_MM2,maxContours,false,myThreads,checkpoint,binaryMask=true)
    }

    private fun preview(width:Int,height:Int,cleaned:IntArray,palette:List<Int>,widthMm:Double,minAreaMm2:Double,maxContours:Int,
        grayscale:Boolean,myThreads:io.github.linker2k.myembro.engine.io.MyThreads,checkpoint:(String)->Unit,binaryMask:Boolean):Preview {
        // One explicit transparent border lets Potrace trace ink touching any crop edge.
        val w=width+2;val h=height+2
        val labels=IntArray(w*h) { p ->
            val x=p%w-1;val y=p/w-1
            if(x in 0 until width && y in 0 until height) cleaned[y*width+x] else -1
        }
        val paddedWidth=widthMm*w/width
        val counts=IntArray(palette.size)
        for(label in labels) if(label>=0) counts[label]++
        val assignment=ThreadAssignment.assign(palette.mapIndexedNotNull { i,rgb ->
            if(counts[i]==0) null else DesignColor(rgb,counts[i]*(widthMm/width)*(widthMm/width)) },
            myThreads,if(grayscale) Bt709Grayscale.threads else PecThreads.SET.filterNotNull())
        val layers=palette.mapIndexedNotNull { color,rgb ->
            checkpoint("tracing")
            if(labels.none { it==color }) null else {
                val thread=assignment.threadFor(rgb)
                try {
                    Layer(color,thread,if(binaryMask) RedworkPipeline.traceMask(w,h,ByteArray(labels.size) { if(labels[it]==color) 0 else -1 },
                        paddedWidth,thread,minAreaMm2,maxContours,checkpoint=checkpoint,preserveSmallContours=true)
                    else RedworkPipeline.trace(w,h,IntArray(labels.size) { if(labels[it]==color) 0 else 0xffffff },
                        paddedWidth,0,thread,minAreaMm2,maxContours,checkpoint=checkpoint,preserveSmallContours=true))
                } catch(failure:IllegalArgumentException) {
                    if(failure.message?.startsWith("No contour remains")==true) null else throw failure
                }
            }
        }
        require(layers.isNotEmpty()) { "No color regions remain" }
        require(layers.sumOf { it.outline.contourBudgetCount }<=maxContours) { "PHOTO_TOO_COMPLEX" }
        val paths=layers.flatMap { it.outline.paths }
        val ink=RedworkOutline("",layers.sumOf { it.outline.contourCount },widthMm,widthMm*height/width,paths,
            layers.sumOf { it.outline.foregroundAreaMm2 })
        val foregroundPixels=BooleanArray(w*h)
        for(index in cleaned.indices) if(cleaned[index]>=0) foregroundPixels[(index/width+1)*w+index%width+1]=true
        return Preview(w,h,labels,palette,layers,paddedWidth,ink,foregroundPixels,assignment.warnings)
    }

    fun generate(preview:Preview,hoop:HoopSpec=HoopSpec.H100,checkpoint:(String)->Unit={}):Result =
        generate(preview,hoop,expandMm=DEFAULT_EXPAND_MM,checkpoint=checkpoint)

    /** Explicit amount override, including 0 for the original main 2007a4f route. */
    fun generate(preview:Preview,hoop:HoopSpec=HoopSpec.H100,expandMm:Double,checkpoint:(String)->Unit={}):Result {
        return generate(preview,hoop,true,checkpoint,expandMm=expandMm)
    }
    internal fun generate(preview:Preview,hoop:HoopSpec,captureRegions:Boolean,checkpoint:(String)->Unit={},includeFillOutlines:Boolean=true,expandMm:Double=DEFAULT_EXPAND_MM):Result {
        require(expandMm.isFinite())
        // Quantize in the original crop's top-left 0.1mm phase, then retain a centered local frame.
        // At 99.9mm, rounding both +/-49.95 separately can create a 100.0mm span. This is a
        // sub-grid origin phase, not another hoop margin, shrink or stitch-generator parameter.
        fun phase(units:Double)=units-kotlin.math.round(units)
        val phaseX=phase(preview.canvasWidthMm*10*(preview.width-2)/preview.width/2)
        val phaseY=phase(preview.canvasWidthMm*10*(preview.height-2)/preview.width/2)
        val shift=org.locationtech.jts.geom.util.AffineTransformation.translationInstance(phaseX,phaseY)
        val clipRegion=if(expandMm==0.0) null else PolygonRegion(shift.transform(PictureFillMask.closing(preview.colorRegionUnion,expandMm).geometry))
        val groups=mutableListOf<StitchGroup>();val regions=mutableListOf<Region>()
        val outlines=mutableListOf<ColorFillOutline.Outline>()
        // Cache the existing ordered polygons once; no change to stitch generation.
        val layerShapes = preview.layers.map { layer ->
            checkpoint("stitches")
            val outline=layer.outline
                val region=PolygonRegion.fromPaths(outline.paths.flatMap { path ->
                    SvgPath.flatten(path,tolerance=Units.mmToPixels(0.1)).map { line ->
                        Polyline(line.points.map { Point(it.x*10/PIXELS_PER_MM+phaseX,it.y*10/PIXELS_PER_MM+phaseY) },true)
                    }
                },FillRule.EVEN_ODD)

            layer to PhotoIslandOrder.polygons(region)
        }
        val totalRegions = layerShapes.sumOf { it.second.size }
        var completedRegions = 0
        var generatedStitches = 0
        checkpoint("regions=0/$totalRegions;stitches=0")
        for((layer, shapes) in layerShapes) {
            val thread=layer.thread
                for(shape in shapes) {
                    checkpoint("widths")
                    val widths=StitchTypeRule.widths(shape,10.0)
                    // Painted areas retain their polygon shape through the same default fill as
                    // silhouette. Only failed fill generation follows the existing running fallback.
                    val method=SmallRegionStitches.type(widths.maxMm,StitchTypeRule.Type.TATAMI)
                    checkpoint("stitches")
                    fun running() : List<StitchGroup> {
                        val polygon=shape as org.locationtech.jts.geom.Polygon
                        val rings=listOf(polygon.exteriorRing)+(0 until polygon.numInteriorRing).map(polygon::getInteriorRingN)
                        return Stroke(rings.map { ring -> ring.coordinates.map { StitchPoint(it.x/10*PIXELS_PER_MM,it.y/10*PIXELS_PER_MM) } },thread).toStitchGroups(null)
                    }
                    val attempted=if(method==StitchTypeRule.Type.RUNNING) SmallRegionStitches.running(running(),hoop)
                    else StitchTypeRule.generateRegion(method,if(groups.isEmpty()) groups else groups.dropLast(1)+groups.last().copy(trimAfter=true),hoop) { attempt ->
                        if(attempt==StitchTypeRule.Type.TATAMI) DefaultTatami.stitchGroups(PolygonRegion(shape),thread,expandMm=expandMm,
                            clipRegion=clipRegion) { checkpoint(it.checkpoint) }
                        else running()
                    }
                    val resolved=if(attempted.excluded) SmallRegionStitches.running(running(),hoop) else attempted
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
                    completedRegions++
                    generatedStitches += next.sumOf { it.stitches.size }
                    checkpoint("regions=$completedRegions/$totalRegions;stitches=$generatedStitches")
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
                "myembro.tiny_regions_omitted" to regions.count { it.excluded }.toString(),
                "myembro.skipped_fill_outlines" to regions.count { it.outlineSkipped }.toString(),
                "myembro.fill_outline_checks" to selection.checks.toString()),finished.warnings + preview.assignmentWarnings.map { it.message })
        val threads=regions.filter { !it.excluded && it.groups>0 }.mapIndexed { i,r -> "region-$i" to r.thread }.toMap()
        val fragments=if(captureRegions && groups.isNotEmpty()) io.github.linker2k.myembro.engine.inkstitch.svg.RegionOrderPatterns.split(finished,owners,threads) else emptyMap()
        return Result(pattern,regions,fragments)
    }
}
