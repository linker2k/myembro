// SPDX-License-Identifier: GPL-3.0-or-later
// Application adapter for DrawingBotV3 Free plotting output. NOT part of the original algorithm.
// Running/locks use the separately attributed Ink/Stitch ports, unchanged defaults.
package io.github.linker2k.myembro.engine.texture

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.geometry.PICTURE_MIN_WIDTH_MM
import io.github.linker2k.myembro.engine.inkstitch.elements.Stroke
import io.github.linker2k.myembro.engine.inkstitch.elements.StrokeParameters
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor
import io.github.linker2k.myembro.engine.inkstitch.svg.Units
import io.github.linker2k.myembro.engine.inkstitch.svg.VectorArt
import io.github.linker2k.myembro.engine.inkstitch.svg.image.Bt709Grayscale
import io.github.linker2k.myembro.engine.inkstitch.svg.image.ImageQuantizer
import io.github.linker2k.myembro.engine.inkstitch.svg.image.PhotoPreparationPolicy
import io.github.linker2k.myembro.engine.inkstitch.utils.Point
import io.github.linker2k.myembro.engine.io.PecThreads
import io.github.linker2k.myembro.engine.io.PesWriteSettings
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.engine.sewout.*
import java.util.concurrent.CancellationException

/** Post-correction raster -> original plot lines -> independent embroidery adaptation.
 * No resampling/filtering is performed here. The caller supplies the texture physical-pitch raster; correction/hoop policy stays shared.
 */
object ThreadTextureAdapter {
    data class Run(val thread: EmbThread, val squiggle: Int, val pixels: List<PixelPoint>, val sourceLineIndices: List<Int>)
    data class Result(val pattern: EmbPattern, val sketch: SketchResult, val runs: List<Run>,
                      val mmPerPixel: Double, val hoop: HoopSpec, val sewout: SewoutReport) {
        /** Candidates with blockers never produce bytes; the normal writer validates again. */
        fun pes(): ByteArray {
            require(!sewout.hasBlockingIssues && sewout.analysisComplete) {
                "SEWOUT_BLOCKED: " + sewout.findingSeverities.filterValues { it == Severity.BLOCKER }.keys.joinToString()
            }
            return PesWriter.write(pattern, PesWriteSettings(hoop = hoop))
        }
    }

    /** Existing Wu quantizer + existing Brother red-mean matcher; actual distinct count can be < N. */
    fun brotherPalette(argb: IntArray, colors: Int): List<EmbThread> {
        require(colors in 2..6) { "Photo color count must be 2..6" }
        val visible = argb.filter { it ushr 24 != 0 }.map { it and 0xffffff }.toIntArray()
        require(visible.isNotEmpty()) { "No visible pixels" }
        return ImageQuantizer.quantize(visible, colors).palette.map { VectorArt.nearestThread(it) }
            .distinctBy { it.color and 0xffffff }
    }

    /** Stable tie order follows the supplied palette, using the existing PEC red-mean distance. */
    fun threadFor(sampledArgb: Int, palette: List<EmbThread>): EmbThread {
        require(palette.isNotEmpty())
        return palette.minBy { PecThreads.distance(sampledArgb, it.color) }
    }

    /** Preserve only consecutive same-thread pieces of one squiggle; never bridge a removed color. */
    fun runs(sketch: SketchResult, palette: List<EmbThread>): List<Run> {
        require(palette.isNotEmpty())
        val runs = mutableListOf<Run>()
        var points = mutableListOf<PixelPoint>()
        var indices = mutableListOf<Int>()
        var thread: EmbThread? = null
        var squiggle = -1
        fun flush() {
            if (points.isNotEmpty()) runs += Run(requireNotNull(thread), squiggle, points.toList(), indices.toList())
            points = mutableListOf(); indices = mutableListOf()
        }
        for ((index, line) in sketch.lines.withIndex()) {
            val next = threadFor(line.sampledArgb, palette)
            if (points.isNotEmpty() && (squiggle != line.squiggle || thread != next || points.last() != line.start || line.linking)) flush()
            if (points.isEmpty()) { thread = next; squiggle = line.squiggle; points += line.start }
            points += line.end; indices += index
        }
        flush()
        // User-directed light -> dark order, not a verified DrawingBot/Photo Stitch color algorithm.
        return runs.sortedWith(compareByDescending<Run> { Bt709Grayscale.argb(it.thread.color) and 255 }
            .thenBy { palette.indexOf(it.thread) })
    }

    fun generate(width: Int, height: Int, correctedArgb: IntArray, widthMm: Double,
                 palette: List<EmbThread>, hoop: HoopSpec = HoopSpec.H100, detail: Int = 1,
                 pitchMmPerPixel: Double = TextureRasterPolicy.pitchForDetail(detail),
                 isCancelled: () -> Boolean = { false }, onProgress: (SketchProgress) -> Unit = {}): Result {
        require(width > 0 && height > 0 && width.toLong() * height == correctedArgb.size.toLong())
        require(widthMm.isFinite() && widthMm in PICTURE_MIN_WIDTH_MM..PhotoPreparationPolicy.photoMaxWidthMm(hoop)) { "Width outside existing photo policy" }
        val heightMm = widthMm * height / width
        require(heightMm * 10 <= SewoutLimits.hoopMaxYUnits0p1mm(hoop)) { "Photo raster aspect exceeds selected hoop" }
        require(width <= TextureRasterPolicy.edge(widthMm, pitchMmPerPixel) && height <= TextureRasterPolicy.edge(heightMm, pitchMmPerPixel)) { "Texture raster is finer than the measured physical-pitch policy" }
        require(palette.isNotEmpty() && palette.size <= 6 && palette.all { thread -> PecThreads.SET.filterNotNull().any { it.sameColor(thread) } }) { "Expected an existing N-color Brother palette" }
        val mask = BooleanArray(correctedArgb.size) { correctedArgb[it] ushr 24 != 0 }
        // Existing photo policy: fully transparent pixels consume no thread. No per-color masks.
        val plotting = IntArray(correctedArgb.size) { if (mask[it]) correctedArgb[it] else 0x00ffffff }
        val sketch = SketchLines.render(width, height, plotting, mask, isCancelled = isCancelled, onProgress = onProgress)
        if (sketch.termination == SketchTermination.CANCELLED) throw CancellationException("Texture generation cancelled")
        require(sketch.termination != SketchTermination.FAILED_SEARCH) { "GENERATION_FAILED: plotting search exhausted" }
        return fromSketch(sketch, width, height, widthMm, palette, hoop, isCancelled)
    }

    /** Independent adapter boundary; [SketchResult] remains in original processing pixels. */
    internal fun fromSketch(sketch: SketchResult, width: Int, height: Int, widthMm: Double,
                            palette: List<EmbThread>, hoop: HoopSpec,
                            isCancelled: () -> Boolean = { false }): Result {
        val scale = widthMm / width
        val ordered = runs(sketch, palette)
        val parameters = StrokeParameters(trim_after = true)
        val groups = ordered.flatMap { run ->
            if (isCancelled() || Thread.currentThread().isInterrupted) throw CancellationException()
            val path = run.pixels.map { p -> Point(Units.mmToPixels((p.x - width / 2.0) * scale),
                Units.mmToPixels((p.y - height / 2.0) * scale)) }
            Stroke(listOf(path), run.thread, parameters).toStitchGroups(null)
        }
        val raw = GeneratedPatternProcessor.fromGroups(groups, pecJumpTrims = true,
            lockAtThreadBoundaries = true, omitZeroLengthMotions = true)
        val pattern = EmbPattern(raw.stitches, raw.threads, raw.metadata + mapOf(
            "name" to "Thread texture", "myembro.texture" to "drawingbot-free-default-v1",
            "myembro.texture.mm_per_pixel" to scale.toString(),
            "myembro.texture.color_order" to "light-to-dark-unconfirmed",
        ), raw.warnings)
        val report = SewoutCheck.check(pattern, options = SewoutOptions(hoop = hoop, origin = DesignOrigin.APP_GENERATED))
        return Result(pattern, sketch, ordered, scale, hoop, report)
    }
}
