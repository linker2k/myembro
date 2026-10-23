// SPDX-License-Identifier: GPL-3.0-or-later
// Original integration of the separately attributed Wu, Potrace and Ink/Stitch ports.
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import io.github.linker2k.myembro.engine.geometry.SvgPath
import io.github.linker2k.myembro.engine.inkstitch.elements.Stroke
import io.github.linker2k.myembro.engine.inkstitch.elements.StrokeParameters
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor
import io.github.linker2k.myembro.engine.inkstitch.svg.Units
import io.github.linker2k.myembro.engine.inkstitch.utils.Point
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
     * Traces region boundaries, not a skeleton/centerline. Both sides of a thick band remain contours.
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
        fun point(p: CurvePoint) = Point((p.x - width / 2.0) * scale, (height / 2.0 - p.y) * scale)
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
        // Same geometry boundary and 0.01px flattening tolerance used by StrokeFixtureTest.
        val flattened = paths.flatMap { path ->
            SvgPath.flatten(path, tolerance = 0.01).map { line -> line.points.map { Point(it.x, it.y) } }
        }
        // All Ink/Stitch running/tie/filter settings remain upstream defaults, including 2.5mm/0.2mm.
        val groups = Stroke(flattened, thread, StrokeParameters(), elementId = "redwork").toStitchGroups(null)
        val pattern = GeneratedPatternProcessor.fromGroups(groups)
        val svgWidth = Units.mmToPixels(widthMm)
        val svgHeight = svgWidth * height / width
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="${-svgWidth / 2} ${-svgHeight / 2} $svgWidth $svgHeight" width="${widthMm}mm" height="${widthMm * height / width}mm"><path fill="none" stroke="#${thread.hex.removePrefix("#")}" d="${paths.joinToString(" ")}"/></svg>"""
        return RedworkResult(pattern, svg, contours.size, PesV6Writer.write(pattern))
    }
}
