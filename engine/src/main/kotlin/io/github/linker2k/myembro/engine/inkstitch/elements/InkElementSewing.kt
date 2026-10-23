// SPDX-License-Identifier: GPL-3.0-or-later
// Temporary myembro bridge, not an Ink/Stitch port: sews Ink/Stitch elements with this engine's
// existing generators until the satin (embro-letter), fill (embro-model) and stroke/ties
// (embro-worker) ports are merged; then [InkElementSewing.runs] and the sewer are replaced by calls
// to those ports and the shared stitch plan.
package io.github.linker2k.myembro.engine.inkstitch.elements

import io.github.linker2k.myembro.engine.digitizing.RunningSettings
import io.github.linker2k.myembro.engine.digitizing.RunningStitch
import io.github.linker2k.myembro.engine.digitizing.TatamiFill
import io.github.linker2k.myembro.engine.digitizing.TatamiSettings
import io.github.linker2k.myembro.engine.geometry.Point
import io.github.linker2k.myembro.engine.geometry.Polyline
import io.github.linker2k.myembro.engine.geometry.PolygonRegion
import io.github.linker2k.myembro.engine.lettering.SatinColumn
import io.github.linker2k.myembro.engine.lettering.SatinOptions
import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.engine.model.Stitch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Sews [InkStitchElement]s in order, as the app's 0.1 mm [EmbPattern]. */
object InkElementSewing {
    /** Element user units (px) to mm: x * scale + dx. */
    data class Mapping(val dx: Double = 0.0, val dy: Double = 0.0, val scale: Double = 25.4 / 96)

    /** Joins element runs: short gaps of one color are sewn across (Ink/Stitch "collapse"), others trimmed. */
    class Sewer(private val thread: EmbThread?, private val metadata: InkStitchMetadata = InkStitchMetadata()) {
        private val stitches = ArrayList<Stitch>()
        private val threads = ArrayList<EmbThread>()
        private var color: Int? = null
        /** The previous element asked for a trim (`trim_after` or a trim command). */
        private var trimPending = false

        fun add(element: InkStitchElement, p: Mapping) {
            val params = element.params
            val minStitch = (params.number("min_stitch_length_mm") ?: metadata.minStitchLenMm).coerceIn(0.0, 2.0) * 10
            val collapse = (params.number("min_jump_stitch_length_mm") ?: metadata.collapseLenMm).coerceIn(0.0, 10.0) * 10
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
            if (params.bool("stop_after", false) && stitches.isNotEmpty()) stitches += stitches.last().copy(command = Command.STOP)
            if (params.bool("trim_after", false) && stitches.isNotEmpty()) trimPending = true
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
            } else if (!trimPending && hypot(first.x - last.x, first.y - last.y) <= collapse) {
                sewTo(first, maxLength)
            } else {
                stitches += last.copy(command = Command.TRIM)
                jump(first)
            }
            trimPending = false
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
            if (trimPending && last != null) stitches += last.copy(command = Command.TRIM)
            return EmbPattern(stitches + Stitch(last?.x ?: 0, last?.y ?: 0, Command.END), threads.toList(), metadata)
        }

        private fun quantize(p: Point): Stitch {
            require(p.x.isFinite() && p.y.isFinite() && kotlin.math.abs(p.x) < 1e8 && kotlin.math.abs(p.y) < 1e8) { "Design exceeds coordinate range" }
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
    internal fun runs(element: InkStitchElement, p: Mapping): List<List<Point>> {
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
                    pullCompensationMm = (params.mean("pull_compensation_mm") ?: 0.0).coerceIn(0.0, 5.0),
                )
                TemporarySatin.sections(element)?.flatMap { (a, b) ->
                    val left = a.map { place(it).let { q -> q.x / 10 to q.y / 10 } }
                    val right = b.map { place(it).let { q -> q.x / 10 to q.y / 10 } }
                    uncentered(left, right, satin)
                }.orEmpty()
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

    /**
     * The old generator's satin for one rail pair, back at the rails' own position: its pattern is
     * centred, and its last stitch is the compensated end of the (possibly reversed) right rail.
     */
    private fun uncentered(left: List<Pair<Double, Double>>, right: List<Pair<Double, Double>>, options: SatinOptions): List<List<Point>> {
        if (left.size < 2 || right.size < 2) return emptyList()
        val pattern = SatinColumn.generate(left, right, options)
        val last = pattern.stitches.lastOrNull { it.command == Command.STITCH } ?: return emptyList()
        fun d(a: Pair<Double, Double>, b: Pair<Double, Double>) = hypot(a.first - b.first, a.second - b.second)
        val reversed = d(left.first(), right.last()) + d(left.last(), right.first()) < d(left.first(), right.first()) + d(left.last(), right.last())
        val p = left.last()
        val q = if (reversed) right.first() else right.last()
        val width = d(p, q)
        val k = if (width > 1e-9) options.pullCompensationMm / width else 0.0
        val dx = (q.first + (q.first - p.first) * k) * 10 - last.x
        val dy = (q.second + (q.second - p.second) * k) * 10 - last.y
        return runsOf(pattern).map { run -> run.map { Point(it.x + dx, it.y + dy) } }
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

    private const val MAX_STITCH_MM = 7.0
}

/**
 * Temporary stand-in for the satin port's rail orientation and rung cuts (SatinColumn
 * reverse_rails / swap_satin_rails / implied rungs / flattened_sections), only to feed this engine's
 * old satin generator. Removed when [InkElementSewing] calls the embro-letter port with rails and rungs.
 */
internal object TemporarySatin {
    fun sections(satin: InkSatin): List<Pair<List<Point>, List<Point>>>? {
        val params = satin.params
        if (satin.rails.size == 1) {
            val (l, r) = SatinColumn.rails(satin.rails[0].map { it.x to it.y }, satin.strokeWidth)
            if (l.size < 2) return null
            return listOf(l.map { Point(it.first, it.second) } to r.map { Point(it.first, it.second) })
        }
        if (satin.rails.size != 2) return null
        var rail0 = satin.rails[0]
        var rail1 = satin.rails[1]
        var nodes0 = satin.railNodes.getOrNull(0).orEmpty()
        var nodes1 = satin.railNodes.getOrNull(1).orEmpty()
        if (params["swap_satin_rails"]?.lowercase() == "true") { rail0 = rail1.also { rail1 = rail0 }; nodes0 = nodes1.also { nodes1 = nodes0 } }
        val (reverse0, reverse1) = reverse(params["reverse_rails"] ?: "automatic", rail0, rail1)
        if (reverse0) { rail0 = rail0.asReversed(); nodes0 = nodes0.asReversed() }
        if (reverse1) { rail1 = rail1.asReversed(); nodes1 = nodes1.asReversed() }
        val cuts0 = ArrayList<Double>()
        val cuts1 = ArrayList<Double>()
        if (satin.rungs.isEmpty()) {
            // Implicit rungs at the nodes; the ends are not cut.
            if (nodes0.size == nodes1.size && nodes0.size > 2) {
                nodes0.drop(1).dropLast(1).forEach { cuts0 += project(rail0, it) }
                nodes1.drop(1).dropLast(1).forEach { cuts1 += project(rail1, it) }
            }
        } else {
            for (rung in satin.rungs) {
                if (crossings(rung, rail0).size + crossings(rung, rail1).size > 2) continue
                cuts0 += nearest(rung, rail0)
                cuts1 += nearest(rung, rail1)
            }
        }
        val pieces0 = cut(rail0, cuts0.sorted())
        val pieces1 = cut(rail1, cuts1.sorted())
        return pieces0.zip(pieces1).filter { (a, b) -> a.size > 1 || b.size > 1 }.ifEmpty { null }
    }

    private fun reverse(choice: String, a: List<Point>, b: List<Point>): Pair<Boolean, Boolean> = when (choice) {
        "first" -> true to false
        "second" -> false to true
        "both" -> true to true
        "none" -> false to false
        else -> {
            var same = 0.0; var flipped = 0.0
            for (i in 0 until 10) {
                val t = i / 10.0
                same += at(a, t).distance(at(b, t))
                flipped += at(a, t).distance(at(b, 1 - t))
            }
            false to (same > flipped)
        }
    }

    private fun length(p: List<Point>) = p.zipWithNext().sumOf { (a, b) -> a.distance(b) }

    private fun at(p: List<Point>, t: Double): Point {
        val total = length(p)
        var left = t * total
        for ((a, b) in p.zipWithNext()) {
            val d = a.distance(b)
            if (left <= d && d > 0) return a.interpolate(b, left / d)
            left -= d
        }
        return p.last()
    }

    /** Arc length along [line] of the point nearest [q]. */
    private fun project(line: List<Point>, q: Point): Double = closest(line, q).first

    /** Arc length along [line] of the point nearest [q], and the distance to it. */
    private fun closest(line: List<Point>, q: Point): Pair<Double, Double> {
        var best = Double.POSITIVE_INFINITY; var bestAt = 0.0; var run = 0.0
        for ((a, b) in line.zipWithNext()) {
            val dx = b.x - a.x; val dy = b.y - a.y
            val len2 = dx * dx + dy * dy
            val t = if (len2 > 0) (((q.x - a.x) * dx + (q.y - a.y) * dy) / len2).coerceIn(0.0, 1.0) else 0.0
            val d = hypot(a.x + dx * t - q.x, a.y + dy * t - q.y)
            if (d < best) { best = d; bestAt = run + t * sqrt(len2) }
            run += sqrt(len2)
        }
        return bestAt to best
    }

    /** Arc lengths along [rail] where [rung] crosses it. */
    private fun crossings(rung: List<Point>, rail: List<Point>): List<Double> {
        val out = ArrayList<Double>()
        var run = 0.0
        for ((c, d) in rail.zipWithNext()) {
            val len = c.distance(d)
            for ((a, b) in rung.zipWithNext()) {
                val rx = b.x - a.x; val ry = b.y - a.y; val sx = d.x - c.x; val sy = d.y - c.y
                val den = rx * sy - ry * sx
                if (abs(den) < 1e-12) continue
                val t = ((c.x - a.x) * sy - (c.y - a.y) * sx) / den
                val u = ((c.x - a.x) * ry - (c.y - a.y) * rx) / den
                if (t in 0.0..1.0 && u in 0.0..1.0) out += run + u * len
            }
            run += len
        }
        return out
    }

    /** Where a rung meets a rail: its crossing, else the rail point nearest the rung's nearer end. */
    private fun nearest(rung: List<Point>, rail: List<Point>): Double =
        crossings(rung, rail).firstOrNull() ?: rung.map { closest(rail, it) }.minBy { it.second }.first

    /** Pieces of [line] between consecutive arc-length [cuts]. */
    private fun cut(line: List<Point>, cuts: List<Double>): List<List<Point>> {
        val total = length(line)
        val bounds = listOf(0.0) + cuts.map { it.coerceIn(0.0, total) } + total
        return bounds.zipWithNext().map { (from, to) ->
            val piece = ArrayList<Point>()
            piece += at(line, if (total > 0) from / total else 0.0)
            var run = 0.0
            for (i in 1 until line.size) {
                run += line[i - 1].distance(line[i])
                if (run > from + 1e-9 && run < to - 1e-9) piece += line[i]
            }
            piece += at(line, if (total > 0) to / total else 0.0)
            piece.filterIndexed { i, p -> i == 0 || p != piece[i - 1] }
        }
    }
}
