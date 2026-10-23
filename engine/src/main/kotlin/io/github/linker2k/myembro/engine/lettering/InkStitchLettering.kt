// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import io.github.linker2k.myembro.engine.digitizing.RunningSettings
import io.github.linker2k.myembro.engine.digitizing.RunningStitch
import io.github.linker2k.myembro.engine.digitizing.TatamiFill
import io.github.linker2k.myembro.engine.digitizing.TatamiSettings
import io.github.linker2k.myembro.engine.geometry.Point
import io.github.linker2k.myembro.engine.geometry.Polyline
import io.github.linker2k.myembro.engine.geometry.PolygonRegion
import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.engine.model.Stitch
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Sews text in an [InkStitchFont] with this engine's own satin, tatami and running-stitch
 * generators, driven by each element's `inkstitch:*` parameters (Ink/Stitch defaults when absent).
 * Layout follows Ink/Stitch lettering: glyph extents, `horiz_adv_x`, kerning pairs, word spacing
 * and leading from `font.json`. Geometry scales; stitch lengths and spacings stay in mm.
 */
object InkStitchLettering {
    /** One glyph placed on the page: font user units are mapped by x * scale + dx, y * scale + dy (mm). */
    data class Placement(val glyph: InkStitchGlyph, val dx: Double, val dy: Double, val scale: Double)

    /**
     * [heightMm] is the ink height of the font's reference glyph ('H'), as for every other font.
     * [thread] sews everything in one thread; null keeps the font's own colors.
     */
    fun render(
        font: InkStitchFont,
        text: String,
        heightMm: Double,
        thread: EmbThread? = null,
        letterSpacingMm: Double = 0.0,
        lineSpacing: Double = 1.0,
    ): EmbPattern {
        val placements = layout(font, text, heightMm, letterSpacingMm, lineSpacing)
        val sewer = Sewer(thread)
        for (p in placements) for (element in p.glyph.elements) sewer.add(element, p)
        return sewer.finish(mapOf("name" to text, "font" to font.info.name))
    }

    fun layout(font: InkStitchFont, text: String, heightMm: Double, letterSpacingMm: Double = 0.0, lineSpacing: Double = 1.0): List<Placement> {
        require(heightMm.isFinite() && heightMm > 0) { "Height must be positive" }
        require(letterSpacingMm.isFinite() && lineSpacing.isFinite() && lineSpacing > 0)
        val reference = font.referenceHeightMm ?: return emptyList()
        val mmPerUnit = InkStitchFont.MM_PER_PX * heightMm / reference
        val info = font.info
        val letterSpacing = letterSpacingMm / mmPerUnit
        val out = ArrayList<Placement>()
        var y = 0.0
        for (rawLine in text.split('\n')) {
            val line = when (info.letterCase) { "upper" -> rawLine.uppercase(); "lower" -> rawLine.lowercase(); else -> rawLine }
            var x = 0.0
            for (word in line.split(' ')) {
                var last: String? = null
                var i = 0
                while (i < word.length) {
                    var (glyph, length) = font.glyphAt(word, i)
                    if (glyph == null && info.defaultGlyph != " ") glyph = info.defaultGlyph?.let { font.glyphs[it] }
                    i += length
                    if (glyph == null) { x += info.wordSpacing; last = null; continue }
                    if (last != null) {
                        val kern = info.kerning["$last ${glyph.name}"] ?: info.kerning[last + glyph.name] ?: 0.0
                        x += glyph.minX - kern + letterSpacing
                    }
                    out += Placement(glyph, (x - glyph.minX) * mmPerUnit, (y - font.baseline) * mmPerUnit, mmPerUnit)
                    x += (info.advances[glyph.name] ?: info.defaultAdvance ?: glyph.maxX) - glyph.minX
                    last = glyph.name
                }
                x += info.wordSpacing
            }
            y += info.leading * lineSpacing
        }
        return out
    }

    /** Joins element runs: short gaps of one color are sewn across (Ink/Stitch "collapse"), others trimmed. */
    private class Sewer(private val thread: EmbThread?) {
        private val stitches = ArrayList<Stitch>()
        private val threads = ArrayList<EmbThread>()
        private var color: Int? = null

        fun add(element: InkStitchElement, p: Placement) {
            val params = element.params
            val minStitch = (params.number("min_stitch_length_mm") ?: 0.1).coerceIn(0.0, 2.0) * 10
            val collapse = (params.number("min_jump_stitch_length_mm") ?: COLLAPSE_MM).coerceIn(0.0, 10.0) * 10
            val maxLength = when (element) {
                is InkSatin -> params.number("max_stitch_length_mm")?.takeIf { it >= 0.5 }?.coerceAtMost(MAX_STITCH_MM) ?: MAX_STITCH_MM
                else -> MAX_STITCH_MM
            } * 10
            for (run in runs(element, p)) {
                val sewn = minimum(run, minStitch)
                if (sewn.size < 2) continue
                start(sewn.first(), element.color, collapse, maxLength)
                sewn.drop(1).forEach { sewTo(it, maxLength) }
            }
        }

        private fun start(first: Point, elementColor: Int, collapse: Double, maxLength: Double) {
            val target = EmbThread(elementColor)
            val last = stitches.lastOrNull()
            val changes = thread == null && color != null && color != elementColor
            if (last == null || changes) {
                if (last != null) { stitches += last.copy(command = Command.TRIM); stitches += last.copy(command = Command.COLOR_CHANGE) }
                threads += thread ?: target
                color = elementColor
                jump(first)
            } else if (hypot(first.x - last.x, first.y - last.y) <= collapse) {
                sewTo(first, maxLength)
            } else {
                stitches += last.copy(command = Command.TRIM)
                jump(first)
            }
        }

        private fun jump(p: Point) {
            val s = quantize(p)
            stitches += s.copy(command = Command.JUMP)
            stitches += s
        }

        private fun sewTo(p: Point, maxLength: Double) {
            val from = stitches.last()
            val distance = hypot(p.x - from.x, p.y - from.y)
            val parts = ceil(distance / maxLength).toInt().coerceAtLeast(1)
            for (i in 1..parts) {
                val s = quantize(Point(from.x + (p.x - from.x) * i / parts, from.y + (p.y - from.y) * i / parts))
                if (s.x != stitches.last().x || s.y != stitches.last().y) stitches += s
            }
        }

        fun finish(metadata: Map<String, String>): EmbPattern {
            val sewn = stitches.filter { it.command == Command.STITCH }
            if (sewn.isNotEmpty()) {
                val cx = (sewn.minOf { it.x } + sewn.maxOf { it.x }) / 2
                val cy = (sewn.minOf { it.y } + sewn.maxOf { it.y }) / 2
                for (i in stitches.indices) stitches[i] = stitches[i].let { it.copy(x = it.x - cx, y = it.y - cy) }
            }
            val last = stitches.lastOrNull()
            return EmbPattern(stitches + Stitch(last?.x ?: 0, last?.y ?: 0, Command.END), threads.toList(), metadata)
        }

        private fun quantize(p: Point): Stitch {
            require(p.x.isFinite() && p.y.isFinite() && kotlin.math.abs(p.x) < 1e8 && kotlin.math.abs(p.y) < 1e8) { "Lettering exceeds coordinate range" }
            return Stitch(p.x.roundToInt(), p.y.roundToInt(), Command.STITCH)
        }
    }

    /** Drops penetrations closer than [min] to the last kept one; the end point always stays. */
    private fun minimum(points: List<Point>, min: Double): List<Point> {
        if (points.size < 2 || min <= 0) return points
        val out = arrayListOf(points.first())
        for (i in 1 until points.lastIndex) if (points[i].distance(out.last()) >= min) out += points[i]
        if (points.last().distance(out.last()) >= min || out.size == 1) out += points.last()
        else out[out.lastIndex] = points.last()
        return out
    }

    /** Sewn runs of one element in 0.1 mm machine units (+y down). */
    internal fun runs(element: InkStitchElement, p: Placement): List<List<Point>> {
        fun place(q: Point) = Point((q.x * p.scale + p.dx) * 10, (q.y * p.scale + p.dy) * 10)
        val params = element.params
        return when (element) {
            is InkSatin -> {
                val satin = SatinOptions(
                    densityMm = (params.number("zigzag_spacing_mm") ?: 0.4).coerceAtLeast(0.2),
                    centerUnderlay = params.bool("center_walk_underlay", false),
                    zigzagUnderlay = params.bool("zigzag_underlay", false),
                    underlayStitchLengthMm = (params.number("center_walk_underlay_stitch_length_mm") ?: 3.0).coerceIn(0.5, 7.0),
                    underlayDensityMm = (params.number("zigzag_underlay_spacing_mm") ?: 3.0).coerceIn(0.2, 10.0),
                    autoDensity = false,
                )
                val contour = if (params.bool("contour_underlay", false)) (params.number("contour_underlay_inset_mm") ?: 0.4) else null
                val sections = element.sections.map { (a, b) ->
                    a.map { place(it).let { q -> q.x / 10 to q.y / 10 } } to b.map { place(it).let { q -> q.x / 10 to q.y / 10 } }
                }
                val points = SatinColumn.sectionPoints(sections, satin,
                    compensationPercent = params.mean("pull_compensation_percent") ?: 0.0,
                    contourInsetMm = contour, compensationMm = params.mean("pull_compensation_mm") ?: 0.0)
                listOf(points.map { (x, y) -> Point(x * 10, y * 10) })
            }
            is InkFill -> {
                val region = PolygonRegion.fromPaths(element.paths.map { Polyline(it.points.map(::place), true) }, element.rule)
                    .let { r -> params.number("expand_mm")?.takeIf { it != 0.0 }?.let { r.offset(it * 10) } ?: r }
                if (region.isEmpty) return emptyList()
                val angle = params.number("angle") ?: 0.0
                val spacing = (params.number("row_spacing_mm") ?: 0.25).coerceAtLeast(0.1) * 10
                val length = (params.number("max_stitch_length_mm") ?: 3.0).coerceIn(0.5, MAX_STITCH_MM) * 10
                val staggers = (params.number("staggers") ?: 4.0).roundToInt().coerceIn(1, 20)
                val offsets = (0 until staggers).map { it.toDouble() / staggers }
                val passes = ArrayList<List<Point>>()
                if (params.bool("fill_underlay", true)) {
                    val inset = (params.number("fill_underlay_inset_mm") ?: 0.0) * 10
                    val under = if (inset != 0.0) region.offset(-inset) else region
                    if (!under.isEmpty) passes += runsOf(TatamiFill.generate(under, TatamiSettings(
                        // Ink/Stitch angles turn counter-clockwise on screen; ours clockwise.
                        angleDegrees = -(params.number("fill_underlay_angle") ?: (angle + 90)),
                        rowSpacing = (params.number("fill_underlay_row_spacing_mm")?.times(10) ?: spacing * 3).coerceAtLeast(1.0),
                        stitchLength = params.number("fill_underlay_max_stitch_length_mm")?.coerceIn(0.5, MAX_STITCH_MM)?.times(10) ?: length,
                        rowOffsets = offsets, underlay = false)))
                }
                passes += runsOf(TatamiFill.generate(region, TatamiSettings(angleDegrees = -angle, rowSpacing = spacing,
                    stitchLength = length, rowOffsets = offsets, underlay = false)))
                passes
            }
            is InkRun -> {
                val length = (params.number("running_stitch_length_mm") ?: 2.5).coerceIn(0.3, MAX_STITCH_MM) * 10
                val repeats = (params.number("repeats") ?: 1.0).roundToInt().coerceIn(1, 10)
                val bean = (params.number("bean_stitch_repeats") ?: 0.0).roundToInt().coerceIn(0, 5)
                element.paths.map { path ->
                    val base = RunningStitch.sample(Polyline(path.points.map(::place), path.closed), RunningSettings(length)).points
                    val repeated = (0 until repeats).flatMap { r -> (if (r % 2 == 0) base else base.asReversed()).let { if (r == 0) it else it.drop(1) } }
                    if (bean == 0) repeated else buildList {
                        add(repeated.first())
                        for ((a, b) in repeated.zipWithNext()) { add(b); repeat(bean) { add(a); add(b) } }
                    }
                }
            }
        }
    }

    /** Sewn runs of a generated pattern, split at its jumps and trims. */
    private fun runsOf(pattern: EmbPattern): List<List<Point>> {
        val out = ArrayList<List<Point>>()
        var current = ArrayList<Point>()
        for (s in pattern.stitches) {
            if (s.command == Command.STITCH) current += Point(s.x.toDouble(), s.y.toDouble())
            else if (current.isNotEmpty()) { out += current; current = ArrayList() }
        }
        if (current.isNotEmpty()) out += current
        return out
    }

    /** Ink/Stitch's default collapse length: shorter jumps are sewn as stitches. */
    private const val COLLAPSE_MM = 3.0
    private const val MAX_STITCH_MM = 7.0
}
