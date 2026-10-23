// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/lettering/font.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
// Ported from Ink/Stitch lib/elements/fill_stitch.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
// Ported from Ink/Stitch lib/elements/stroke.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.lettering

import io.github.linker2k.myembro.engine.geometry.Point
import io.github.linker2k.myembro.engine.geometry.PolygonRegion
import io.github.linker2k.myembro.engine.geometry.Polyline
import io.github.linker2k.myembro.engine.inkstitch.elements.SatinColumn
import io.github.linker2k.myembro.engine.inkstitch.elements.SatinParameters
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor
import io.github.linker2k.myembro.engine.inkstitch.stitches.InkAutoFill
import io.github.linker2k.myembro.engine.inkstitch.stitches.InkRunning
import io.github.linker2k.myembro.engine.inkstitch.svg.Units
import io.github.linker2k.myembro.engine.inkstitch.utils.Point as SvgPoint
import io.github.linker2k.myembro.engine.lettering.TextAlignment
import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.engine.model.Stitch
import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Text in an [InkStitchFont], laid out as Ink/Stitch lettering does (glyph extents, `horiz_adv_x`,
 * kerning pairs, word spacing, leading, back-and-forth lines) and sewn by the shared Ink/Stitch ports
 * with each element's `inkstitch:*` parameters: satin columns by the satin port, running stitch and
 * tatami fill by the stitch ports, then the common generated-pattern processor (ties, collapse, minimum
 * lengths). Geometry scales with the text; stitch lengths and spacings stay in mm.
 */
object InkStitchLettering {
    /** One glyph placed on the page: font px are mapped by x * scale + dx, y * scale + dy (mm). */
    data class Placement(val glyph: InkStitchGlyph, val dx: Double, val dy: Double, val scale: Double) {
        fun mm(q: Point) = Point(q.x * scale + dx, q.y * scale + dy)
    }

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
        lineHeightMm: Double? = null,
        alignment: TextAlignment = TextAlignment.LEFT,
        backAndForth: Boolean = true,
    ): EmbPattern {
        val placements = layout(font, text, heightMm, letterSpacingMm, lineHeightMm, alignment, backAndForth)
        val sewer = Sewer(thread)
        for (p in placements) for (element in p.glyph.elements) sewer.add(element, p)
        return GeneratedPatternProcessor.finish(sewer.pattern(mapOf("name" to text, "font" to font.info.name)))
    }

    /**
     * Lines step by [lineHeightMm], or by the font's `leading` when null. [alignment] moves each
     * line by its own ink-box width as Ink/Stitch's text_align does. With [backAndForth] (Ink/Stitch's
     * default) every second line of a reversible font is sewn right to left; positions stay.
     */
    fun layout(
        font: InkStitchFont, text: String, heightMm: Double, letterSpacingMm: Double = 0.0,
        lineHeightMm: Double? = null, alignment: TextAlignment = TextAlignment.LEFT, backAndForth: Boolean = true,
    ): List<Placement> {
        require(heightMm.isFinite() && heightMm > 0) { "Height must be positive" }
        require(letterSpacingMm.isFinite() && (lineHeightMm == null || lineHeightMm.isFinite() && lineHeightMm > 0))
        val reference = font.referenceHeightMm ?: return emptyList()
        val mmPerUnit = InkStitchFont.MM_PER_PX * heightMm / reference
        val info = font.info
        val letterSpacing = letterSpacingMm / mmPerUnit
        val out = ArrayList<Placement>()
        var y = 0.0
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFC)
        for ((index, rawLine) in normalized.lines().withIndex()) {
            val line = rawLine.trim()
            val placed = ArrayList<Placement>()
            var x = 0.0
            var left = Double.POSITIVE_INFINITY
            var right = Double.NEGATIVE_INFINITY
            for (rawWord in line.split(' ')) {
                val word = when (info.letterCase) { "upper" -> rawWord.uppercase(); "lower" -> rawWord.lowercase(); else -> rawWord }
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
                    placed += Placement(glyph, (x - glyph.minX) * mmPerUnit, (y - font.baseline) * mmPerUnit, mmPerUnit)
                    left = min(left, x); right = max(right, x + glyph.maxX - glyph.minX)
                    x += (info.advances[glyph.name] ?: info.defaultAdvance ?: glyph.maxX) - glyph.minX
                    last = glyph.name
                }
                x += info.wordSpacing
            }
            val width = if (right > left) (right - left) * mmPerUnit else 0.0
            val shift = when (alignment) {
                TextAlignment.LEFT -> 0.0
                TextAlignment.CENTER -> -width / 2
                TextAlignment.RIGHT -> -width
            }
            val shifted = placed.map { it.copy(dx = it.dx + shift) }
            out += if (backAndForth && info.reversible && index % 2 == 1) shifted.asReversed() else shifted
            y += lineHeightMm?.let { it / mmPerUnit } ?: info.leading
        }
        return out
    }

    /** Sewn runs of each element in order; color changes and requested trims between them, jumps otherwise. */
    private class Sewer(private val thread: EmbThread?) {
        private val stitches = ArrayList<Stitch>()
        private val threads = ArrayList<EmbThread>()
        private var color: Int? = null
        private var trimPending = false

        fun add(element: InkStitchElement, p: Placement) {
            val previous = stitches.lastOrNull()?.let { SvgPoint(Units.mmToPixels(it.x / 10.0), Units.mmToPixels(it.y / 10.0)) }
            for (run in runs(element, p, previous)) {
                if (run.isEmpty()) continue
                start(run.first(), element.color)
                run.drop(1).forEach { stitches += machine(it) }
            }
            if (element.params.bool("stop_after", false) && stitches.isNotEmpty()) stitches += stitches.last().copy(command = Command.STOP)
            if (element.params.bool("trim_after", false) && stitches.isNotEmpty()) trimPending = true
        }

        private fun start(first: Point, elementColor: Int) {
            val last = stitches.lastOrNull()
            if (last == null || (thread == null && color != elementColor)) {
                if (last != null) { stitches += last.copy(command = Command.TRIM); stitches += last.copy(command = Command.COLOR_CHANGE) }
                threads += thread ?: EmbThread(elementColor)
                color = elementColor
            } else if (trimPending) stitches += last.copy(command = Command.TRIM)
            trimPending = false
            val s = machine(first)
            stitches += s.copy(command = Command.JUMP)
            stitches += s
        }

        fun pattern(metadata: Map<String, String>): EmbPattern {
            val sewn = stitches.filter { it.command == Command.STITCH }
            if (sewn.isNotEmpty()) {
                val cx = (sewn.minOf { it.x } + sewn.maxOf { it.x }) / 2
                val cy = (sewn.minOf { it.y } + sewn.maxOf { it.y }) / 2
                for (i in stitches.indices) stitches[i] = stitches[i].let { it.copy(x = it.x - cx, y = it.y - cy) }
            }
            val last = stitches.lastOrNull()
            if (trimPending && last != null) stitches += last.copy(command = Command.TRIM)
            return EmbPattern(stitches + Stitch(last?.x ?: 0, last?.y ?: 0, Command.END), threads.toList(), metadata)
        }

        private fun machine(p: Point): Stitch {
            require(p.x.isFinite() && p.y.isFinite() && kotlin.math.abs(p.x) < 1e6 && kotlin.math.abs(p.y) < 1e6) { "Lettering exceeds coordinate range" }
            return Stitch((p.x * 10).roundToInt(), (p.y * 10).roundToInt(), Command.STITCH)
        }
    }

    /** Stitches of one placed element in mm, one list per sewn run. */
    internal fun runs(element: InkStitchElement, p: Placement, previous: SvgPoint? = null): List<List<Point>> {
        val params = element.params
        fun px(q: Point) = p.mm(q).let { SvgPoint(Units.mmToPixels(it.x), Units.mmToPixels(it.y)) }
        return when (element) {
            is InkSatin -> {
                val column = SatinColumn.fromSubpaths(element.subpaths.map { it.map(::px) }, satinParameters(params),
                    element.nodePaths.map { it.map(::px) })
                listOf(column.toStitchGroups(previous?.let { io.github.linker2k.myembro.engine.inkstitch.stitch_plan.Stitch(it) })
                    .flatMap { g -> g.stitches.map { Point(Units.pixelsToMm(it.x), Units.pixelsToMm(it.y)) } })
            }
            is InkRun -> {
                // stroke.py running stitch: even spacing, repeats back and forth, then bean repeats.
                val length = (params.number("running_stitch_length_mm") ?: 2.5).coerceAtLeast(0.1)
                val tolerance = (params.number("running_stitch_tolerance_mm") ?: 0.2).coerceAtLeast(0.01)
                val repeats = (params.number("repeats") ?: 1.0).roundToInt().coerceIn(1, 20)
                val bean = params.values("bean_stitch_repeats").map { it.roundToInt().coerceIn(0, 10) }.ifEmpty { listOf(0) }
                element.paths.map { path ->
                    val base = InkRunning.even(path.points.map(p::mm), length, tolerance)
                    val repeated = (0 until repeats).flatMap { r -> (if (r % 2 == 0) base else base.asReversed()).let { if (r == 0) it else it.drop(1) } }
                    if (bean.all { it == 0 }) repeated else InkRunning.bean(repeated, bean)
                }
            }
            is InkFill -> {
                // fill_stitch.py defaults: row spacing 0.25 mm, max stitch 3 mm, 4 staggers, underlay at angle + 90
                // with 3× row spacing. Ink/Stitch angles turn counter-clockwise on screen; the tatami port's clockwise.
                val region = PolygonRegion.fromPaths(element.paths.map { Polyline(it.points.map { q -> p.mm(q).let { m -> Point(m.x * 10, m.y * 10) } }, true) }, element.rule)
                if (region.isEmpty) return emptyList()
                val angle = params.number("angle") ?: 0.0
                val spacing = (params.number("row_spacing_mm") ?: 0.25).coerceAtLeast(0.05)
                val length = (params.number("max_stitch_length_mm") ?: 3.0).coerceAtLeast(0.1)
                val staggers = (params.number("staggers") ?: 4.0).roundToInt().coerceIn(1, 20)
                val out = ArrayList<List<Point>>()
                if (params.bool("fill_underlay", true)) {
                    out += InkAutoFill.paths(region, InkAutoFill.Settings(
                        angleDegrees = -(params.number("fill_underlay_angle") ?: (angle + 90)),
                        rowSpacing = (params.number("fill_underlay_row_spacing_mm") ?: spacing * 3) * 10,
                        stitchLength = (params.number("fill_underlay_max_stitch_length_mm") ?: length) * 10, staggers = staggers))
                }
                out += InkAutoFill.paths(region, InkAutoFill.Settings(angleDegrees = -angle, rowSpacing = spacing * 10,
                    stitchLength = length * 10, staggers = staggers))
                out.map { run -> run.map { Point(it.x / 10, it.y / 10) } }
            }
        }
    }

    /** SatinColumn parameters from `inkstitch:*` attributes; absent ones keep Ink/Stitch's defaults. */
    internal fun satinParameters(params: InkStitchParams): SatinParameters {
        val d = SatinParameters()
        fun n(name: String, default: Double) = params.number(name) ?: default
        fun b(name: String, default: Boolean) = params.bool(name, default)
        return d.copy(
            zigzag_spacing_mm = n("zigzag_spacing_mm", d.zigzag_spacing_mm).coerceAtLeast(0.01),
            pull_compensation_mm = params.pair("pull_compensation_mm") ?: d.pull_compensation_mm,
            pull_compensation_percent = params.pair("pull_compensation_percent") ?: d.pull_compensation_percent,
            reverse_rails = params["reverse_rails"]?.takeIf { it in setOf("automatic", "none", "first", "second", "both") } ?: d.reverse_rails,
            swap_satin_rails = b("swap_satin_rails", d.swap_satin_rails),
            short_stitch_inset = params.values("short_stitch_inset").ifEmpty { d.short_stitch_inset },
            short_stitch_distance_mm = n("short_stitch_distance_mm", d.short_stitch_distance_mm),
            split_method = params["split_method"]?.takeIf { it in setOf("default", "simple", "staggered") } ?: d.split_method,
            max_stitch_length_mm = params.number("max_stitch_length_mm")?.takeIf { it > 0 } ?: d.max_stitch_length_mm,
            running_stitch_length_mm = n("running_stitch_length_mm", d.running_stitch_length_mm),
            center_walk_underlay = b("center_walk_underlay", d.center_walk_underlay),
            center_walk_underlay_stitch_length_mm = n("center_walk_underlay_stitch_length_mm", d.center_walk_underlay_stitch_length_mm).coerceAtLeast(0.01),
            center_walk_underlay_repeats = params.number("center_walk_underlay_repeats")?.roundToInt()?.coerceIn(1, 100) ?: d.center_walk_underlay_repeats,
            center_walk_underlay_position = n("center_walk_underlay_position", d.center_walk_underlay_position),
            contour_underlay = b("contour_underlay", d.contour_underlay),
            contour_underlay_stitch_length_mm = n("contour_underlay_stitch_length_mm", d.contour_underlay_stitch_length_mm).coerceAtLeast(0.01),
            contour_underlay_inset_mm = params.pair("contour_underlay_inset_mm") ?: d.contour_underlay_inset_mm,
            zigzag_underlay = b("zigzag_underlay", d.zigzag_underlay),
            zigzag_underlay_spacing_mm = n("zigzag_underlay_spacing_mm", d.zigzag_underlay_spacing_mm).coerceAtLeast(0.01),
            zigzag_underlay_inset_mm = params.pair("zigzag_underlay_inset_mm") ?: d.zigzag_underlay_inset_mm,
            zigzag_underlay_max_stitch_length_mm = params.number("zigzag_underlay_max_stitch_length_mm")?.takeIf { it > 0 } ?: d.zigzag_underlay_max_stitch_length_mm,
            min_stitch_length_mm = params.number("min_stitch_length_mm") ?: d.min_stitch_length_mm,
            min_jump_stitch_length_mm = params.number("min_jump_stitch_length_mm") ?: d.min_jump_stitch_length_mm,
        )
    }
}

/**
 * Installed Ink/Stitch fonts by id, for [io.github.linker2k.myembro.engine.edit.TextObject]s that
 * name one. The app sets [loader] (parsing takes up to a second, so results are kept).
 */
object InkStitchFontRegistry {
    @Volatile var loader: ((String) -> InkStitchFont?)? = null
    private val fonts = java.util.concurrent.ConcurrentHashMap<String, InkStitchFont>()

    operator fun get(id: String): InkStitchFont? = fonts[id] ?: loader?.invoke(id)?.also { fonts[id] = it }
    fun put(id: String, font: InkStitchFont) { fonts[id] = font }
    fun invalidate(id: String) { fonts.remove(id) }
}
