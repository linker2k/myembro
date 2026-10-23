// SPDX-License-Identifier: GPL-3.0-or-later
// Original integration of the separately attributed Wu, Potrace and Ink/Stitch ports.
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import io.github.linker2k.myembro.engine.geometry.SvgPath
import io.github.linker2k.myembro.engine.inkstitch.elements.DefaultTatami
import io.github.linker2k.myembro.engine.geometry.PolygonRegion
import io.github.linker2k.myembro.engine.geometry.Polyline
import io.github.linker2k.myembro.engine.geometry.FillRule
import io.github.linker2k.myembro.engine.geometry.Point as RegionPoint
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor
import io.github.linker2k.myembro.engine.inkstitch.svg.Units
import io.github.linker2k.myembro.engine.io.PesV6Writer
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

/** Engine-only first redwork integration. Input is top-down RGB24, not ARGB or a decoded photograph. */
object RedworkPipeline {
    /**
     * Caller supplies a known background pixel; no unvalidated automatic polarity heuristic.
     * Traces boundaries into filled regions with holes, then source-default tatami/underlay.
     * No running outline or centerline approximation is generated.
     * widthMm is the full bitmap width; aspect ratio and placement about the hoop center are preserved.
     */
    fun generate(
        width: Int, height: Int, rgb: IntArray, widthMm: Double, backgroundPixelIndex: Int,
        thread: EmbThread,
    ): RedworkResult {
        require(width in 1..4096 && height in 1..4096 && width.toLong() * height == rgb.size.toLong())
        require(widthMm.isFinite() && widthMm > 0 && widthMm <= 1000)
        require(backgroundPixelIndex in rgb.indices)
        val quantized = ImageQuantizer.quantize(rgb, 2)
        require(quantized.palette.size == 2) { "The bitmap must contain distinguishable line and background colors" }
        val labels = quantized.copyIndices()
        val background = labels[backgroundPixelIndex]
        val bottomUp = BooleanArray(rgb.size) { index ->
            labels[(height - 1 - index / width) * width + index % width] != background
        }
        val contours = BinaryContourTracer.trace(width, height, bottomUp)
        require(contours.isNotEmpty()) { "No contour remains after original Potrace turdsize=2 filtering" }
        val scale = Units.mmToPixels(widthMm) / width
        fun point(p: CurvePoint) = RegionPoint((p.x - width / 2.0) * scale, (height / 2.0 - p.y) * scale)
        val paths = contours.map { contour ->
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
        // Convert SVG-pixel contours to the native 0.1mm PolygonRegion contract.
        // Even-odd preserves background holes and foreground islands without a new heuristic.
        val region = PolygonRegion.fromPaths(paths.flatMap { path ->
            SvgPath.flatten(path, tolerance = 0.01).map { line ->
                Polyline(line.points.map { RegionPoint(it.x * 10 / Units.mmToPixels(1.0),
                    it.y * 10 / Units.mmToPixels(1.0)) }, true)
            }
        }, FillRule.EVEN_ODD)
        val groups = DefaultTatami.stitchGroups(region, thread)
        require(groups.isNotEmpty()) { "No fill remains at the original tatami defaults" }
        val finished = GeneratedPatternProcessor.fromGroups(groups)
        val pattern = EmbPattern(finished.stitches, finished.threads,
            finished.metadata + ("myembro.fill.backend" to "inkstitch-default-tatami-underlay"), finished.warnings)
        val svgWidth = Units.mmToPixels(widthMm)
        val svgHeight = svgWidth * height / width
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="${-svgWidth / 2} ${-svgHeight / 2} $svgWidth $svgHeight" width="${widthMm}mm" height="${widthMm * height / width}mm"><path fill-rule="evenodd" fill="#${thread.hex.removePrefix("#")}" d="${paths.joinToString(" ")}"/></svg>"""
        return RedworkResult(pattern, svg, contours.size, PesV6Writer.write(pattern))
    }
}
