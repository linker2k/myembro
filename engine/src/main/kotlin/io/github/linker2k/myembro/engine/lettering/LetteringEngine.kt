// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.engine.model.Stitch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

enum class TextAlignment { LEFT, CENTER, RIGHT }

/** Public dimensions are mm; EmbPattern coordinates are 0.1 mm, with +y down. */
object LetteringEngine {
    /**
     * [heightMm] is cap height; [spacing] is extra character spacing in mm. Each line
     * uses [alignment] inside the longest line box. Baselines are [lineSpacing] cap heights
     * apart (default 1.5). [arcDeg] bends the baseline; [arcRadiusMm], when set, replaces
     * angle magnitude with a physical radius, retaining the angle's direction.
     * [characterThreads] indices count code points after CR/LF and tab normalization.
     * Single-line Hershey defaults to [StitchStyle.BEAN]; select RUNNING for one pass.
     * SATIN offsets each bent stroke by [satinOptions]' width; its separate maximum
     * stitch length replaces [stitchLengthMm] for this style only.
     * Unsupported Unicode code points become '?' and are reported in warnings.
     */
    fun generate(
        text: String,
        fontId: String = HersheyFont.DEFAULT_ID,
        heightMm: Double = 10.0,
        spacing: Double = 1.0,
        arcDeg: Double = 0.0,
        thread: EmbThread = EmbThread(0xFF000000.toInt(), "Black"),
        stitchLengthMm: Double = 2.5,
        trimBetweenStrokes: Boolean = true,
        lineSpacing: Double = 1.5,
        alignment: TextAlignment = TextAlignment.CENTER,
        arcRadiusMm: Double? = null,
        characterThreads: Map<Int, EmbThread> = emptyMap(),
        stitchStyle: StitchStyle = StitchStyle.BEAN,
        satinOptions: SatinOptions = SatinOptions(),
    ): EmbPattern {
        require(stitchStyle != StitchStyle.FILL) { "FILL requires closed outline contours" }
        validateOptions(heightMm, stitchLengthMm, arcDeg)
        require(spacing.isFinite() && spacing >= 0) { "spacing must be finite and non-negative" }
        require(lineSpacing.isFinite() && lineSpacing >= 1.0) { "lineSpacing must be at least 1" }
        validateRadius(arcRadiusMm)
        require(characterThreads.keys.all { it >= 0 })
        val font = HersheyFont.load(fontId)
        val scale = heightMm * 10 / font.capHeight
        val gap = spacing * 10
        val builder = RunningStitches(stitchLengthMm, trimBetweenStrokes, stitchStyle = stitchStyle, satinOptions = satinOptions)
        val missing = linkedSetOf<Int>()
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val allGlyphs = lines.map { line -> line.replace("\t", "    ").codePoints().toArray().map { code ->
                val glyph = if (code <= Char.MAX_VALUE.code) font[code.toChar()] else null
                glyph ?: run {
                    missing += code
                    checkNotNull(font['?']) { "Font has no replacement glyph" }
                }
            }
        }
        val widths = allGlyphs.map { glyphs -> glyphs.sumOf { it.advance * scale } + gap * (glyphs.size - 1).coerceAtLeast(0) }
        val maximum = widths.maxOrNull() ?: 0.0
        var characterIndex = 0
        allGlyphs.forEachIndexed { lineIndex, glyphs ->
            val width = widths[lineIndex]
            val offset = when (alignment) {
                TextAlignment.LEFT -> -(maximum - width) / 2
                TextAlignment.CENTER -> 0.0
                TextAlignment.RIGHT -> (maximum - width) / 2
            }
            var cursor = -width / 2 + offset
            val lineY = lineIndex * heightMm * 10 * lineSpacing
            glyphs.forEach { glyph ->
                glyph.strokes.forEach { stroke ->
                    val points = stroke.map { point ->
                        (cursor + (point.x - glyph.left) * scale) to ((point.y - font.baseline) * scale)
                    }
                    builder.stroke(bend(points, maximum, arcDeg, arcRadiusMm).map { (x, y) -> x to (y + lineY) }, characterThreads[characterIndex] ?: thread)
                }
                cursor += glyph.advance * scale + gap
                characterIndex++
            }
            characterIndex++ // newline occupies one code point
        }
        return builder.finish(
            thread, mapOf("name" to text, "font" to fontId),
            missing.map { "Unsupported lettering character U+${it.toString(16).uppercase().padStart(4, '0')}; replaced with '?'" },
        ).withStitchStyle(stitchStyle, if (stitchStyle == StitchStyle.SATIN) satinOptions.maxStitchLengthMm else stitchLengthMm)
    }

    /**
     * Converts ordered, already laid out outline polylines into running stitches.
     * Input coordinates can be pixels or font units; +y is down and [baseline] is in
     * those units. [sourceHeight] defaults to the overall ink height; supply a font's
     * reference height to keep character size consistent across strings/multiple lines.
     * Closed contours must repeat their first point; open contours stay open.
     *
     * Curves must already be flattened (e.g. Android Path.approximate). Nearly collinear
     * short segments are merged within [mergeToleranceMm], preserving contour endpoints
     * and sharp corners. Set [simplify] to false to keep all input vertices. Optional
     * [minSegmentMm] merges short edges within the same geometric tolerance. Closed
     * loops and open endpoints remain intact. The final sewn bounds are centered on (0, 0).
     * Optional [contourThreads] and source-unit [contourBaselines] must each match the raw
     * contour count; baselines preserve line offsets after bending each line separately.
     * [arcRadiusMm] sets a physical radius, with the sign of [arcDeg] choosing above/below.
     * [stitchStyle] defaults to RUNNING for outlines; use BEAN for imported single-line paths.
     * FILL groups [contourCharacters] and fills each glyph with even-odd holes, using
     * [fillOptions]. Missing owners treat all contours as one glyph. SATIN uses narrow
     * outline zigzags with tatami fallback when [outline] is true; otherwise it offsets
     * centerline contours using [satinOptions], preserving the existing stroke API.
     */
    fun fromContours(
        contours: List<List<Pair<Double, Double>>>,
        heightMm: Double,
        stitchLengthMm: Double = 2.5,
        arcDeg: Double = 0.0,
        thread: EmbThread = EmbThread(0xFF000000.toInt(), "Black"),
        trimBetweenStrokes: Boolean = true,
        baseline: Double = 0.0,
        sourceHeight: Double? = null,
        mergeToleranceMm: Double = 0.05,
        simplify: Boolean = true,
        minSegmentMm: Double = 0.0,
        arcRadiusMm: Double? = null,
        contourThreads: List<EmbThread> = emptyList(),
        contourBaselines: List<Double> = emptyList(),
        stitchStyle: StitchStyle = StitchStyle.RUNNING,
        satinOptions: SatinOptions = SatinOptions(),
        fillOptions: FillOptions = FillOptions(),
        contourCharacters: List<Int> = emptyList(),
        outline: Boolean = false,
    ): EmbPattern {
        validateOptions(heightMm, stitchLengthMm, arcDeg)
        validateRadius(arcRadiusMm)
        require(contourThreads.isEmpty() || contourThreads.size == contours.size) { "One thread per contour is required" }
        require(contourBaselines.isEmpty() || contourBaselines.size == contours.size) { "One baseline per contour is required" }
        require(contourBaselines.all { it.isFinite() })
        require(contourCharacters.isEmpty() || contourCharacters.size == contours.size)
        require(contourCharacters.all { it >= 0 })
        require(baseline.isFinite()) { "baseline must be finite" }
        require(sourceHeight == null || sourceHeight.isFinite() && sourceHeight > 0) { "sourceHeight must be finite and positive" }
        require(mergeToleranceMm.isFinite() && mergeToleranceMm >= 0) { "mergeToleranceMm must be finite and non-negative" }
        require(contours.all { contour -> contour.all { (x, y) -> x.isFinite() && y.isFinite() } }) {
            "Contour coordinates must be finite"
        }
        require(minSegmentMm.isFinite() && minSegmentMm >= 0) { "minSegmentMm must be finite and non-negative" }
        val paths = contours.filter { it.size >= 2 }
        val builder = RunningStitches(stitchLengthMm, trimBetweenStrokes, mergeToleranceMm, simplify, minSegmentMm, stitchStyle, satinOptions)
        if (paths.isEmpty()) return builder.finish(thread).withStitchStyle(stitchStyle, stitchLengthMm)
        val points = paths.flatten()
        val minX = points.minOf { it.first }
        val maxX = points.maxOf { it.first }
        val inkHeight = points.maxOf { it.second } - points.minOf { it.second }
        // A horizontal line has no ink height; use its width as the reference extent.
        val reference = sourceHeight ?: inkHeight.takeIf { it > 0 } ?: (maxX - minX).takeIf { it > 0 } ?: 1.0
        val scale = heightMm * 10 / reference
        val centerX = minX / 2 + maxX / 2
        val width = (maxX - minX) * scale
        require(scale.isFinite() && width.isFinite()) { "Contour range is too large" }
        if (stitchStyle == StitchStyle.FILL || stitchStyle == StitchStyle.SATIN && outline) {
            val glyphs = linkedMapOf<Int, MutableList<List<Pair<Double, Double>>>>()
            val glyphThreads = linkedMapOf<Int, EmbThread>()
            contours.forEachIndexed { index, contour ->
                val character = contourCharacters.getOrNull(index) ?: 0
                val lineBaseline = contourBaselines.getOrNull(index) ?: baseline
                val normalized = contour.map { (x, y) -> (x - centerX) * scale to (y - lineBaseline) * scale }
                val lineY = (lineBaseline - baseline) * scale
                glyphs.getOrPut(character) { arrayListOf() } += bend(normalized, width, arcDeg, arcRadiusMm)
                    .map { (x, y) -> x / 10 to (y + lineY) / 10 }
                glyphThreads.putIfAbsent(character, contourThreads.getOrNull(index) ?: thread)
            }
            return OutlineFill.generateGlyphs(glyphs.map { (character, paths) -> OutlineGlyph(paths, glyphThreads.getValue(character), heightMm) },
                fillOptions, stitchStyle == StitchStyle.SATIN)
        }
        contours.forEachIndexed { index, contour ->
            if (contour.size < 2) return@forEachIndexed
            val lineBaseline = contourBaselines.getOrNull(index) ?: baseline
            val normalized = contour.map { (x, y) -> (x - centerX) * scale to (y - lineBaseline) * scale }
            val lineY = (lineBaseline - baseline) * scale
            builder.stroke(bend(normalized, width, arcDeg, arcRadiusMm).map { (x, y) -> x to y + lineY },
                contourThreads.getOrNull(index) ?: thread)
        }
        return builder.finish(thread).withStitchStyle(stitchStyle, if (stitchStyle == StitchStyle.SATIN) satinOptions.maxStitchLengthMm else stitchLengthMm)
    }

    private fun validateOptions(heightMm: Double, stitchLengthMm: Double, arcDeg: Double) {
        require(heightMm.isFinite() && heightMm > 0) { "heightMm must be finite and positive" }
        require(arcDeg.isFinite() && abs(arcDeg) <= 360) { "arcDeg must be within -360..360" }
        require(stitchLengthMm.isFinite() && stitchLengthMm >= 0.2) {
            "stitchLengthMm must be finite and at least 0.2 mm (coordinate resolution)"
        }
    }

    private fun validateRadius(radius: Double?) {
        require(radius == null || radius.isFinite() && radius > 0) { "arcRadiusMm must be finite and positive" }
    }

    /** Map baseline x to a circle, with y along its normal. Sample bent edges every 2°. */
    private fun bend(points: List<Pair<Double, Double>>, width: Double, arcDeg: Double, radiusMm: Double? = null): List<Pair<Double, Double>> {
        val radians = Math.toRadians(arcDeg)
        if (abs(radians) < 1e-6 || width <= 0 || points.isEmpty()) return points
        val radius = radiusMm?.let { it * 10 * if (arcDeg < 0) -1 else 1 } ?: (width / radians)
        fun map(point: Pair<Double, Double>): Pair<Double, Double> {
            val (x, y) = point
            val angle = x / radius
            return (radius - y) * sin(angle) to (2 * radius * sin(angle / 2) * sin(angle / 2) + y * cos(angle))
        }
        val result = arrayListOf(map(points.first()))
        points.zipWithNext().forEach { (a, b) ->
            val parts = ceil(abs((b.first - a.first) / radius) / Math.toRadians(2.0)).toInt().coerceAtLeast(1)
            for (part in 1..parts) {
                val t = part.toDouble() / parts
                result += map((a.first + (b.first - a.first) * t) to (a.second + (b.second - a.second) * t))
            }
        }
        return result
    }
}

/** Shared machine-coordinate sampler, keeping jumps separate from sewn segments. */
internal class RunningStitches(
    stitchLengthMm: Double,
    private val trimBetweenStrokes: Boolean,
    private val mergeToleranceMm: Double = 0.05,
    private val simplifyPaths: Boolean = true,
    private val minSegmentMm: Double = 0.0,
    private val stitchStyle: StitchStyle = StitchStyle.RUNNING,
    private val satinOptions: SatinOptions = SatinOptions(),
) {
    private val maxLength = (if (stitchStyle == StitchStyle.SATIN) satinOptions.maxStitchLengthMm else stitchLengthMm) * 10
    private val stitches = arrayListOf<Stitch>()
    private val threads = arrayListOf<EmbThread>()

    private fun quantize(x: Double, y: Double): Stitch {
        require(x.isFinite() && y.isFinite() && abs(x) < Int.MAX_VALUE / 2.0 && abs(y) < Int.MAX_VALUE / 2.0) {
            "Lettering exceeds coordinate range"
        }
        return Stitch(x.roundToInt(), y.roundToInt(), Command.STITCH)
    }

    fun stroke(points: List<Pair<Double, Double>>, thread: EmbThread? = null) {
        val reduced = if (simplifyPaths) {
            // Public simplification works in mm; this builder receives 0.1 mm units.
            ContourSimplifier.simplify(
                listOf(points.map { (x, y) -> x / 10 to y / 10 }), mergeToleranceMm, minSegmentMm,
            ).single().map { (x, y) -> x * 10 to y * 10 }
        } else points
        val path = if (stitchStyle == StitchStyle.SATIN) {
            SatinColumn.strokePoints(reduced.map { (x, y) -> x / 10 to y / 10 }, satinOptions)
                .map { (x, y) -> x * 10 to y * 10 }
        } else reduced
        val sampled = path.map { (x, y) -> quantize(x, y) }
        if (sampled.isEmpty()) return
        val changesColor = thread != null && threads.isNotEmpty() && !threads.last().sameThread(thread)
        if (stitches.isNotEmpty() && (trimBetweenStrokes || changesColor)) stitches += stitches.last().copy(command = Command.TRIM)
        if (thread != null && (threads.isEmpty() || changesColor)) {
            if (changesColor) stitches += stitches.last().copy(command = Command.COLOR_CHANGE)
            threads += thread
        }
        stitches += sampled.first().copy(command = Command.JUMP)
        stitches += sampled.first()
        sampled.drop(1).forEach(::sewTo)
    }

    private fun sewTo(target: Stitch) {
        val previous = stitches.last()
        val distance = hypot((target.x - previous.x).toDouble(), (target.y - previous.y).toDouble())
        if (distance == 0.0) return
        val parts = ceil(distance / maxLength).toInt().coerceAtLeast(1)
        for (part in 1..parts) {
            val t = part.toDouble() / parts
            val next = quantize(previous.x + (target.x - previous.x) * t, previous.y + (target.y - previous.y) * t)
            val last = stitches.last()
            if (hypot((next.x - last.x).toDouble(), (next.y - last.y).toDouble()) > maxLength) {
                // Resolve the occasional integer rounding overshoot (min length is 2 units).
                stitches += quantize((last.x + next.x) / 2.0, (last.y + next.y) / 2.0)
            }
            if (stitches.last() != next) stitches += next
        }
    }

    fun finish(thread: EmbThread, metadata: Map<String, String> = emptyMap(), warnings: List<String> = emptyList()): EmbPattern {
        if (stitches.isNotEmpty()) {
            val sewn = stitches.filter { it.command == Command.STITCH }
            val cx = (sewn.minOf { it.x } + sewn.maxOf { it.x }) / 2
            val cy = (sewn.minOf { it.y } + sewn.maxOf { it.y }) / 2
            for (i in stitches.indices) stitches[i] = stitches[i].let { it.copy(x = it.x - cx, y = it.y - cy) }
        }
        val last = stitches.lastOrNull()
        stitches += Stitch(last?.x ?: 0, last?.y ?: 0, Command.END)
        return EmbPattern(stitches.toList(), if (last == null) emptyList() else threads.ifEmpty { listOf(thread) }.toList(), metadata, warnings)
    }

}
