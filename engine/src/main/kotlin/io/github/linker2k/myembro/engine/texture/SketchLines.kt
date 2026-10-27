// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from DrawingBotV3 pfm/{PFMSketchLinesBasic,AbstractSketchPFM}.java and
// api/IPlottingTools.java, plotting/PlottingTools.java, pfm/helpers/PFMRenderPipe.java
// @0e7c900638def19b9a8d16445099f3f1129aee52 (GPL-3.0), Copyright DrawingBotV3 contributors.
package io.github.linker2k.myembro.engine.texture

import java.util.Random
import java.util.concurrent.CancellationException

/** Register.java:297–342 defaults. Pixel lengths and segment counts, never mm or stitch counts.
 * Restricted backend: radiusMin == radiusMax == 1, rendered pen width == 1F, ARGBY/Bresenham.
 */
data class SketchSettings(
    val seed: Int = 0,
    val startAngleMin: Int = -72, val startAngleMax: Int = -52,
    val enableShading: Boolean = false, val shadingThreshold: Float = .5f,
    val drawingDeltaAngle: Float = 360f, val shadingDeltaAngle: Float = 180f,
    val unlimitedTests: Boolean = false, val lineTests: Int = 20,
    val minLineLength: Int = 2, val maxLineLength: Int = 40,
    val maxLines: Int = -1, val lineDensity: Float = .75f,
    val squiggleMinLength: Int = 25, val squiggleMaxLength: Int = 500,
    val squiggleMaxDeviation: Float = .25f, val shouldLiftPen: Boolean = true,
    val eraseMin: Float = 50f, val eraseMax: Float = 125f, val tone: Float = .5f,
) {
    init {
        require(startAngleMin in -360..360 && startAngleMax in -360..360)
        require(shadingThreshold.isFinite() && shadingThreshold in 0f..1f)
        require(drawingDeltaAngle.isFinite() && drawingDeltaAngle in -360f..360f)
        require(shadingDeltaAngle.isFinite() && shadingDeltaAngle in -360f..360f)
        require(lineTests in 1..3200)
        require(minLineLength in 2..Short.MAX_VALUE && maxLineLength in 2..Short.MAX_VALUE)
        require(maxLines >= -1 && lineDensity.isFinite() && lineDensity in 0f..1f)
        require(squiggleMinLength in 0..Short.MAX_VALUE && squiggleMaxLength in 1..Short.MAX_VALUE)
        require(squiggleMaxDeviation.isFinite() && squiggleMaxDeviation in 0f..1f)
        require(eraseMin.isFinite() && eraseMin in 1f..255f && eraseMax.isFinite() && eraseMax in 1f..255f)
        require(tone.isFinite() && tone in 0f..1f)
    }
    internal fun normalized() = copy(minLineLength = minOf(minLineLength, maxLineLength),
        maxLineLength = maxOf(minLineLength, maxLineLength),
        squiggleMinLength = minOf(squiggleMinLength, squiggleMaxLength),
        squiggleMaxLength = maxOf(squiggleMinLength, squiggleMaxLength),
        eraseMin = minOf(eraseMin, eraseMax), eraseMax = maxOf(eraseMin, eraseMax))
}

/** Uses precisely the Java bounded RNG calls used upstream; requires the engine's JVM 17.
 * No Kotlin RNG, no replacement with nextInt(bound) + origin (different power-of-two sequence).
 */
class SketchRandom(seed: Int) {
    private val random = Random(seed.toLong())
    fun int(origin: Int, bound: Int): Int = when {
        origin == bound -> origin
        origin > bound -> random.nextInt(bound, origin)
        else -> random.nextInt(origin, bound)
    }
    fun float(origin: Float, bound: Float): Float = when {
        origin == bound -> origin
        origin > bound -> random.nextFloat(bound, origin)
        else -> random.nextFloat(origin, bound)
    }
}

data class SketchLine(val start: PixelPoint, val end: PixelPoint, val sampledArgb: Int, val squiggle: Int, val linking: Boolean = false)
data class SketchProgress(val lineProgress: Double, val luminanceProgress: Double, val actual: Double)
enum class SketchTermination { LUMINANCE, LINE_LIMIT, NO_DRAWABLE_PIXELS, FAILED_SEARCH, CANCELLED }
data class SketchResult(val lines: List<SketchLine>, val termination: SketchTermination, val progress: SketchProgress)

/** A fresh task per invocation; no shared mutable state. Returns plotting lines, NEVER an exportable PES. */
object SketchLines {
    const val DESIRED_LUMINANCE = 253.5f
    fun progress(initial: Double, average: Double, lineCount: Int, settings: SketchSettings): SketchProgress {
        val line = if (settings.maxLines == -1) 0.0 else lineCount.toDouble() / settings.maxLines
        val lum = if (average >= DESIRED_LUMINANCE) 1.0 else
            (average - initial) / ((DESIRED_LUMINANCE - initial) * settings.lineDensity)
        return SketchProgress(line, lum, maxOf(line, lum))
    }

    fun render(width: Int, height: Int, argb: IntArray, clipMask: BooleanArray? = null,
               settings: SketchSettings = SketchSettings(), isCancelled: () -> Boolean = { false },
               onProgress: (SketchProgress) -> Unit = {}): SketchResult {
        val s = settings.normalized()
        val raster = SketchRaster(width, height, argb, clipMask)
        val lines = mutableListOf<SketchLine>()
        var p = SketchProgress(0.0, 0.0, 0.0)
        if (raster.pixelCount == 0) return SketchResult(emptyList(), SketchTermination.NO_DRAWABLE_PIXELS, p)
        val initial = raster.averageLuminance()
        val random = SketchRandom(s.seed)
        fun checkpoint() { if (isCancelled() || Thread.currentThread().isInterrupted) throw CancellationException() }
        val sampler = SketchLineSampler(raster, ::checkpoint)
        val cache = DarkestAreaCache(raster)
        var current = PixelPoint(0, 0)
        var squiggle = 0
        var fails = 0
        fun update(): Boolean {
            p = progress(initial, raster.averageLuminance(), lines.size, s)
            onProgress(p)
            return p.actual >= 1
        }
        fun finished() = SketchResult(lines.toList(),
            if (p.lineProgress >= 1) SketchTermination.LINE_LIMIT else SketchTermination.LUMINANCE, p)
        fun emit(start: PixelPoint, end: PixelPoint, linking: Boolean = false) {
            val x = raster.luminance(current.x, current.y) / 255.0
            val y = (x * x * x * s.tone) + (x * (1 - s.tone))
            val erase = (s.eraseMin + y * (s.eraseMax - s.eraseMin)).toInt()
            val sums = IntArray(4)
            var count = 0
            SketchBresenham.line(start, end) { px, py ->
                if (raster.within(px, py)) {
                    val color = raster.referenceArgb(px, py)
                    for (c in 0..3) sums[c] += (color ushr (24 - c * 8)) and 255
                    raster.brighten(px, py, erase)
                    count++
                }
            }
            var sampled = 0
            if (count != 0) for (c in 0..3) sampled = sampled or ((sums[c] / count) shl (24 - c * 8))
            lines += SketchLine(start, end, sampled, squiggle, linking)
        }
        try {
            checkpoint()
            if (update()) return finished()
            while (true) {
                checkpoint()
                val last = current
                current = cache.next() ?: return SketchResult(lines.toList(), SketchTermination.NO_DRAWABLE_PIXELS, p)
                // Preserve the source's AND test for links, rather than silently changing it to OR.
                if (!s.shouldLiftPen && squiggle > 0 && last.x != current.x && last.y != current.y) emit(last, current, true)
                val darkness = raster.luminance(current.x, current.y).toFloat()
                val allowable = if (!s.shouldLiftPen) DESIRED_LUMINANCE else darkness + maxOf(1f, DESIRED_LUMINANCE * s.squiggleMaxDeviation)
                var failed = false
                var segment = 0
                while (!s.shouldLiftPen || segment < s.squiggleMaxLength) {
                    checkpoint()
                    val shading = s.enableShading && s.shadingThreshold < p.luminanceProgress
                    val angle = random.float(s.startAngleMin.toFloat(), s.startAngleMax.toFloat())
                    val length = random.int(s.minLineLength, s.maxLineLength)
                    val found = sampler.find(current, s.minLineLength, length, if (s.unlimitedTests) -1 else s.lineTests,
                        angle, if (shading) s.shadingDeltaAngle else s.drawingDeltaAngle, shading)
                    if (found == null) { failed = true; break }
                    if (s.shouldLiftPen && segment >= s.squiggleMinLength &&
                        (found.average > allowable || found.average > raster.averageLuminance())) break
                    if (!s.shouldLiftPen && found.average > allowable) { failed = true; break }
                    emit(current, found.destination)
                    current = found.destination
                    if (update()) return finished()
                    segment++
                }
                squiggle++
                if (failed) { raster.whiten(current.x, current.y); fails++ } else fails = 0
                if (fails >= 1000) return SketchResult(lines.toList(), SketchTermination.FAILED_SEARCH, p)
            }
        } catch (_: CancellationException) {
            return SketchResult(lines.toList(), SketchTermination.CANCELLED, p)
        } finally { cache.close() }
    }
}
