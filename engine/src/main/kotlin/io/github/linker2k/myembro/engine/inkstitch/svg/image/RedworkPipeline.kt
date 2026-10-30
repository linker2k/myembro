// SPDX-License-Identifier: GPL-3.0-or-later
// Original integration of the separately attributed Wu, Potrace and Ink/Stitch ports.
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.StitchGroup
import org.locationtech.jts.geom.Polygon
import io.github.linker2k.myembro.engine.geometry.SvgPath
import io.github.linker2k.myembro.engine.inkstitch.elements.DefaultTatami
import io.github.linker2k.myembro.engine.geometry.PolygonRegion
import io.github.linker2k.myembro.engine.geometry.Polyline
import io.github.linker2k.myembro.engine.geometry.FillRule
import io.github.linker2k.myembro.engine.geometry.Point as RegionPoint
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor
import io.github.linker2k.myembro.engine.inkstitch.svg.Units
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.io.PesWriteSettings
import io.github.linker2k.myembro.engine.io.PesVersion
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.model.EmbThread

class RedworkResult internal constructor(
    val pattern: EmbPattern,
    val outlineSvg: String,
    val contourCount: Int,
    private val pes: ByteArray,
) {
    fun copyPesBytes(): ByteArray = pes.copyOf()
}

class RedworkOutline internal constructor(
    val outlineSvg: String, val contourCount: Int, val widthMm: Double, val heightMm: Double,
    val paths: List<String>, val foregroundAreaMm2: Double,
)

/** Engine-only first redwork integration. Input is top-down RGB24, not ARGB or a decoded photograph. */
object RedworkPipeline {
    /**
     * Caller supplies a known background pixel; no unvalidated automatic polarity heuristic.
     * Traces boundaries into filled regions with holes, then source-default tatami/underlay.
     * Filled silhouettes include the same optional two-pass staggered outline as color drawings.
     * widthMm is the full bitmap width; aspect ratio and placement about the hoop center are preserved.
     */
    fun generate(
        width: Int, height: Int, rgb: IntArray, widthMm: Double, backgroundPixelIndex: Int,
        thread: EmbThread, hoop: HoopSpec = HoopSpec.H100,
    ): RedworkResult {
        return generate(trace(width,height,rgb,widthMm,backgroundPixelIndex,thread),thread,hoop=hoop)
    }

    /** Photo preview stops here: no JTS fill graph, stitches, locks, PES or sewout analysis. */
    fun trace(width: Int, height: Int, rgb: IntArray, widthMm: Double, backgroundPixelIndex: Int,
        thread: EmbThread, minAreaMm2: Double = 0.0, maxContours: Int = Int.MAX_VALUE,
        onQuantized: (IntArray) -> Unit = {},
        checkpoint: (String) -> Unit = {},
    ): RedworkOutline {
        require(width in 1..4096 && height in 1..4096 && width.toLong() * height == rgb.size.toLong())
        require(widthMm.isFinite() && widthMm > 0 && widthMm <= 1000)
        require(minAreaMm2.isFinite() && minAreaMm2 >= 0 && maxContours > 0)
        require(backgroundPixelIndex in rgb.indices)
        checkpoint("quantization")
        val quantized = ImageQuantizer.quantize(rgb, 2)
        val labels = quantized.copyIndices()
        val background = labels[backgroundPixelIndex]
        onQuantized(IntArray(rgb.size) { if (labels[it] == background) 0xFFFFFF else 0 })
        require(quantized.palette.size == 2) { "The bitmap must contain distinguishable line and background colors" }
        val bottomUp = BooleanArray(rgb.size) { index ->
            labels[(height - 1 - index / width) * width + index % width] != background
        }
        return traceBinary(width, height, bottomUp, widthMm, thread, minAreaMm2, maxContours, checkpoint)
    }

    /** Explicit dark-ink mask: no Wu, no background-pixel polarity inference. */
    fun traceMask(width: Int, height: Int, mask: ByteArray, widthMm: Double,
        thread: EmbThread, minAreaMm2: Double = 0.0, maxContours: Int = Int.MAX_VALUE,
        onQuantized: (IntArray) -> Unit = {}, checkpoint: (String) -> Unit = {}): RedworkOutline {
        require(width in 1..4096 && height in 1..4096 && width.toLong() * height == mask.size.toLong())
        require(widthMm.isFinite() && widthMm > 0 && widthMm <= 1000)
        require(minAreaMm2.isFinite() && minAreaMm2 >= 0 && maxContours > 0)
        require(mask.all { it == 0.toByte() || it == 255.toByte() })
        checkpoint("quantization")
        onQuantized(IntArray(mask.size) { if (mask[it] == 0.toByte()) 0 else 0xffffff })
        require(mask.any { it == 0.toByte() } && mask.any { it == 255.toByte() }) { "No distinguishable foreground and background" }
        val bottomUp = BooleanArray(mask.size) { i -> mask[(height - 1 - i / width) * width + i % width] == 0.toByte() }
        return traceBinary(width, height, bottomUp, widthMm, thread, minAreaMm2, maxContours, checkpoint)
    }

    private fun traceBinary(width: Int, height: Int, bottomUp: BooleanArray, widthMm: Double,
        thread: EmbThread, minAreaMm2: Double, maxContours: Int, checkpoint: (String) -> Unit): RedworkOutline {
        checkpoint("tracing")
        val contours = BinaryContourTracer.trace(width, height, bottomUp, maxOf(2, kotlin.math.ceil(minAreaMm2 / (widthMm / width) / (widthMm / width)).toInt()))
        require(contours.isNotEmpty()) { "No contour remains after Potrace area filtering" }
        require(contours.size <= maxContours) { "PHOTO_TOO_COMPLEX: reduce detail" }
        val scale = Units.mmToPixels(widthMm) / width
        fun point(p: CurvePoint) = RegionPoint((p.x - width / 2.0) * scale, (height / 2.0 - p.y) * scale)
        val paths = contours.map { contour ->
            checkpoint("tracing")
            val curve = CurveOptimizer.optimize(CurveFitter.fit(contour))
            buildString {
                fun coordinate(p: CurvePoint) { val v = point(p); append(v.x).append(' ').append(v.y) }
                append("M "); coordinate(curve.segments.last().controls[2])
                for (segment in curve.segments) {
                    if (segment.tag == 1) {
                        append(" C "); coordinate(segment.controls[0]); append(' ')
                        coordinate(segment.controls[1]); append(' '); coordinate(segment.controls[2])
                    } else {
                        append(" L "); coordinate(segment.controls[1]); append(" L "); coordinate(segment.controls[2])
                    }
                }
                append(" Z")
            }
        }
        val svgWidth = Units.mmToPixels(widthMm)
        val svgHeight = svgWidth * height / width
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="${-svgWidth / 2} ${-svgHeight / 2} $svgWidth $svgHeight" width="${widthMm}mm" height="${widthMm * height / width}mm"><path fill-rule="evenodd" fill="#${thread.hex.removePrefix("#")}" d="${paths.joinToString(" ")}"/></svg>"""
        checkpoint("preview_ready")
        return RedworkOutline(svg,contours.size,widthMm,widthMm * height / width,paths,
            contours.sumOf { if(it.positive) it.area else -it.area } * (widthMm/width) * (widthMm/width))
    }

    fun generate(outline: RedworkOutline, thread: EmbThread, photoMode: Boolean = false, style: PhotoStyle = PhotoStyle.SILHOUETTE, hoop: HoopSpec = HoopSpec.H100, checkpoint: (String) -> Unit = {}): RedworkResult {
        checkpoint("stitches")
        val paths = outline.paths
        var route: PhotoOutline.Route? = null
        var fillOutlines: ColorFillOutline.Selection? = null
        val groups = if(style==PhotoStyle.OUTLINE) {
            route=PhotoOutline.route(PhotoOutline.paths(outline))
            PhotoOutline.groups(route,thread)
        } else {
            // Convert SVG-pixel contours to the native 0.1mm PolygonRegion contract.
            // Even-odd preserves background holes and foreground islands without a new heuristic.
            val region = PolygonRegion.fromPaths(paths.flatMap { path ->
                // Photo output is quantized to 0.1mm by PES. Avoid sub-quantum tessellation
                // in the fill routing graph; the generic port path retains its existing tolerance.
                SvgPath.flatten(path, tolerance = if(photoMode) Units.mmToPixels(0.1) else 0.01).map { line ->
                    Polyline(line.points.map { RegionPoint(it.x * 10 / Units.mmToPixels(1.0),
                        it.y * 10 / Units.mmToPixels(1.0)) }, true)
                }
            }, FillRule.EVEN_ODD)
            silhouetteGroups(region, thread, hoop, fit={ fitPhotoPattern(it,outline,photoMode) }) { checkpoint("outline validation") }.also { fillOutlines = it }.groups
        }
        require(groups.isNotEmpty()) { "No fill remains at the original tatami defaults" }
        val finished = GeneratedPatternProcessor.fromGroups(groups)
        val fitted=fitPhotoPattern(finished,outline,photoMode)
        val pattern = EmbPattern(fitted.stitches, fitted.threads,
            fitted.metadata + mapOf(
                "myembro.fill.backend" to if(style==PhotoStyle.OUTLINE) "inkstitch-bean-outline" else "inkstitch-default-tatami-underlay",
                "myembro.photo.style" to style.name,
            ) + (fillOutlines?.let { mapOf("myembro.skipped_fill_outlines" to it.skipped.size.toString(),
                "myembro.fill_outline_checks" to it.checks.toString()) } ?: emptyMap()) + (route?.let { mapOf("myembro.photo.jump_before_mm" to Units.pixelsToMm(it.before).toString(),
                "myembro.photo.jump_after_mm" to Units.pixelsToMm(it.after).toString()) } ?: emptyMap()), finished.warnings)
        checkpoint("stitches_ready=${pattern.stitchCount}")
        checkpoint("pes")
        return RedworkResult(pattern, outline.outlineSvg, outline.contourCount, PesWriter.write(pattern, PesWriteSettings(version=PesVersion.V6,hoop=hoop)))
    }

    /** The existing photo fit, shared by optional-outline validation and final output. */
    internal fun fitPhotoPattern(finished:EmbPattern,outline:RedworkOutline,photoMode:Boolean):EmbPattern {
        val bounds=finished.stitches
        val minX=bounds.minOf { it.x }; val maxX=bounds.maxOf { it.x }
        val minY=bounds.minOf { it.y }; val maxY=bounds.maxOf { it.y }
        val factor=if(photoMode) minOf(1.0,outline.widthMm*10/(maxX-minX).coerceAtLeast(1),
            outline.heightMm*10/(maxY-minY).coerceAtLeast(1)) else 1.0
        val stitches=if(photoMode) bounds.map { it.copy(
            x=kotlin.math.round((it.x-minX)*factor).toInt()-kotlin.math.floor((maxX-minX)*factor/2).toInt(),
            y=kotlin.math.round((it.y-minY)*factor).toInt()-kotlin.math.floor((maxY-minY)*factor/2).toInt()) }
            else bounds
        return EmbPattern(stitches,finished.threads,finished.metadata,finished.warnings)
    }

    /** Keep each island's original tatami and immediately following unexpanded outline. */
    internal fun silhouetteGroups(region: PolygonRegion, thread: EmbThread, hoop: HoopSpec,
        fit:(EmbPattern)->EmbPattern={ it }, checkpoint: () -> Unit = {}): ColorFillOutline.Selection {
        checkpoint()
        val fills=mutableListOf<io.github.linker2k.myembro.engine.inkstitch.stitch_plan.StitchGroup>()
        val ordered=PhotoIslandOrder.polygons(region).mapIndexedNotNull { index,polygon ->
            checkpoint()
            val next=DefaultTatami.stitchGroups(PolygonRegion(polygon),thread)
            if(next.isEmpty()) null else {
                if(fills.isNotEmpty()) fills[fills.lastIndex]=fills.last().copy(trimAfter=true)
                fills+=next
                ColorFillOutline.Outline(fills.size,index,ColorFillOutline.stitchGroups(polygon,thread))
            }
        }
        // Match final generation exactly; fitting maps coordinates without changing owner indices.
        return ColorFillOutline.select(fills,ordered,hoop,prepareOutput={ candidate,owners ->
            ColorFillOutline.PreparedOutput(fit(GeneratedPatternProcessor.fromGroups(candidate,stitchOwners=owners)),
                PesWriteSettings(version=PesVersion.V6,hoop=hoop))
        },checkpoint=checkpoint)
    }

}
