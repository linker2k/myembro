// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import io.github.linker2k.myembro.engine.digitizing.RunningSettings
import io.github.linker2k.myembro.engine.digitizing.RunningStitch
import io.github.linker2k.myembro.engine.digitizing.TatamiFill
import io.github.linker2k.myembro.engine.digitizing.TatamiSettings
import io.github.linker2k.myembro.engine.geometry.*
import io.github.linker2k.myembro.engine.geometry.Polyline as GeometryPath
import io.github.linker2k.myembro.engine.model.*
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.prep.PreparedGeometryFactory
import kotlin.math.*

enum class FillBorder { NONE, RUNNING, BEAN }

/** Public dimensions are mm; a positive pull compensation expands exteriors, preserving counters. */
data class FillOptions(
    val densityMm: Double = 0.4,
    val angleDeg: Double = 0.0,
    val pullCompensationMm: Double = 0.2,
    val underlay: Boolean = true,
    val border: FillBorder = FillBorder.RUNNING,
) {
    init {
        require(densityMm.isFinite() && densityMm in 0.3..2.0)
        require(angleDeg.isFinite() && angleDeg in -180.0..180.0)
        require(pullCompensationMm.isFinite() && pullCompensationMm in 0.0..2.0)
    }
}

internal data class OutlineGlyph(val contours: List<List<Pair<Double, Double>>>, val thread: EmbThread)

/** Even-odd filled glyphs. Public inputs use mm; needle positions use centered 0.1 mm units. */
object OutlineFill {
    fun generate(
        contours: List<List<Pair<Double, Double>>>,
        options: FillOptions = FillOptions(),
        thread: EmbThread = EmbThread(0xFF000000.toInt(), "Black"),
        satin: Boolean = false,
    ): EmbPattern = generateGlyphs(listOf(OutlineGlyph(contours, thread)), options, satin)

    internal fun generateGlyphs(glyphs: List<OutlineGlyph>, options: FillOptions, satin: Boolean): EmbPattern {
        val builder = FillStitches()
        for (glyph in glyphs) {
            val raw = PolygonRegion.fromPaths(glyph.contours.map { path ->
                GeometryPath(path.map { (x, y) -> Point(x * 10, y * 10) }, closed = true)
            }, FillRule.EVEN_ODD)
            if (raw.isEmpty) continue
            // Buffering must not close a small 'ㅇ' or the counters of B, even with compensation.
            fun shells(g: org.locationtech.jts.geom.Geometry): List<GeometryPath> = when (g) {
                is Polygon -> listOf(GeometryPath(g.exteriorRing.coordinates.map { Point(it.x, it.y) }, true))
                is org.locationtech.jts.geom.GeometryCollection -> (0 until g.numGeometries).flatMap { shells(g.getGeometryN(it)) }
                else -> emptyList()
            }
            val holes = PolygonRegion.fromPaths(shells(raw.geometry)).difference(raw)
            val region = raw.offset(options.pullCompensationMm * 10).difference(holes)
            val canSatin = satin && satinPossible(glyph.contours, region, options)
            if (satin && !canSatin) builder.warnings += "새틴을 적용하기 어려운 넓거나 복잡한 획은 태타미로 채웠습니다."
            builder.begin(region, glyph.thread)
            if (options.underlay) {
                val inset = raw.offset(-3.0).takeUnless { it.isEmpty } ?: raw
                inset.boundaries().forEach { builder.path(RunningStitch.sample(it, RunningSettings(25.0, 0.0)).points) }
            }
            val fill = TatamiFill.generate(region, TatamiSettings(
                angleDegrees = options.angleDeg,
                rowSpacing = options.densityMm * if (canSatin) 5 else 10,
                stitchLength = if (canSatin) 69.0 else 35.0,
                rowOffsets = if (canSatin) listOf(0.0) else listOf(0.0, 0.25, 0.5, 0.75),
                underlay = false, zigzag = canSatin,
            ), glyph.thread)
            // Re-route disconnected scanline runs inside this glyph, never through a counter.
            var run = arrayListOf<Point>()
            for (stitch in fill.stitches) {
                if (stitch.command == Command.STITCH) run += Point(stitch.x.toDouble(), stitch.y.toDouble())
                else if (run.isNotEmpty()) { builder.path(run); run = arrayListOf() }
            }
            if (run.isNotEmpty()) builder.path(run)
            if (options.border != FillBorder.NONE) region.boundaries().forEach {
                builder.path(RunningStitch.sample(it, RunningSettings(25.0, 0.0)).points, options.border == FillBorder.BEAN)
            }
        }
        return builder.finish()
    }

    private fun satinPossible(contours: List<List<Pair<Double, Double>>>, region: PolygonRegion, options: FillOptions): Boolean {
        val points = contours.flatten()
        val height = (points.maxOf { it.second } - points.minOf { it.second }).coerceAtLeast(0.1)
        val thickness = FontQuality.analyze(listOf(contours), height, true).minStrokeMm
        if (thickness !in 0.6..4.0) return false
        val scan = region.transformed(Affine2D.rotation(-options.angleDeg))
        val bounds = scan.geometry.envelopeInternal
        val spacing = options.densityMm * 5
        val rows = ceil(bounds.height / spacing)
        require(rows <= 100_000) { "Outline satin exceeds row limit" }
        for (row in 0 until rows.toInt()) {
            val y = bounds.minY + min(spacing / 2, bounds.height / 2) + row * spacing
            if (scan.horizontalSpans(y).any { it.second - it.first > 68.0 }) return false
        }
        return true
    }
}

/** Route connections along the shortest visible ring arc if a direct hidden run would cross a hole. */
private class HiddenRoutes(private val region: FillCoverage, boundaryRegion: PolygonRegion) {
    private val rings = boundaryRegion.boundaries().map { it.points }
    fun route(from: Point, to: Point): List<Point>? {
        if (from.distance(to) < 1e-9) return emptyList()
        if (region.coversSegment(from, to)) return listOf(to)
        var best: List<Point>? = null
        var length = Double.POSITIVE_INFINITY
        for (ring in rings) {
            fun nearest(p: Point): Pair<Int, Point> = ring.zipWithNext().mapIndexed { i, (a, b) ->
                val dx = b.x - a.x; val dy = b.y - a.y
                val t = if (dx * dx + dy * dy == 0.0) 0.0 else ((p.x - a.x) * dx + (p.y - a.y) * dy) / (dx * dx + dy * dy)
                i to a.interpolate(b, t.coerceIn(0.0, 1.0))
            }.minBy { it.second.distance(p) }
            val (i, a) = nearest(from)
            val (j, b) = nearest(to)
            if (!region.coversSegment(from, a) || !region.coversSegment(b, to)) continue
            val n = ring.lastIndex
            fun arc(start: Int, end: Int, a: Point, b: Point): List<Point> = buildList {
                add(a)
                var k = (start + 1) % n
                val stop = (end + 1) % n
                var count = 0
                while (k != stop && count++ < n) { add(ring[k]); k = (k + 1) % n }
                add(b)
            }
            val candidates = listOf(arc(i, j, a, b), arc(j, i, b, a).asReversed()) +
                if (i == j) listOf(listOf(a, b)) else emptyList()
            for (candidate in candidates) {
                val path = listOf(from) + candidate + to
                val distance = path.zipWithNext().sumOf { (p, q) -> p.distance(q) }
                if (distance < length && path.zipWithNext().all { (p, q) -> region.coversSegment(p, q) }) {
                    length = distance; best = path.drop(1)
                }
            }
        }
        return best
    }
}

/** Prepared polygon indexes make per-needle containment checks practical for flattened fonts. */
private class FillCoverage(region: PolygonRegion) {
    private val prepared = PreparedGeometryFactory.prepare(region.geometry)
    private val factory = GeometryFactory()
    fun coversSegment(a: Point, b: Point): Boolean = prepared.covers(
        factory.createLineString(arrayOf(Coordinate(a.x, a.y), Coordinate(b.x, b.y))))
}

/** Enforce 0.3..7 mm on sewn moves AFTER quantization; a tiny corner may require a jump. */
private class FillStitches {
    private val stitches = arrayListOf<Stitch>()
    private val threads = arrayListOf<EmbThread>()
    val warnings = linkedSetOf<String>()
    private lateinit var region: FillCoverage
    private lateinit var routes: HiddenRoutes
    private var first = true
    private var lastWasBean = false
    fun begin(region: PolygonRegion, thread: EmbThread) {
        this.region = FillCoverage(region.offset(0.75)) // <= sqrt(2)*0.05 mm machine-rounding envelope
        routes = HiddenRoutes(this.region, region)
        if (stitches.isNotEmpty()) stitches += stitches.last().copy(command = Command.TRIM)
        if (threads.isEmpty() || !threads.last().sameThread(thread)) {
            if (threads.isNotEmpty()) stitches += stitches.last().copy(command = Command.COLOR_CHANGE)
            threads += thread
        }
        first = true
    }
    private fun point(s: Stitch) = Point(s.x.toDouble(), s.y.toDouble())
    private fun jump(target: Stitch) {
        stitches += target.copy(command = Command.JUMP)
        stitches += target
        lastWasBean = false
    }
    private fun sew(p: Point, bean: Boolean = false) {
        val target = Stitch(p.x.roundToInt(), p.y.roundToInt(), Command.STITCH)
        val last = stitches.lastOrNull()
        if (last == null || first) { jump(target); first = false; return }
        val a = point(last); val b = point(target)
        val distance = a.distance(b)
        if (distance < 3.0) {
            // Move the preceding penetration to a nearby corner instead of either sewing
            // a sub-0.3 mm stitch or cutting the corner on the following movement.
            val before = stitches.getOrNull(stitches.lastIndex - 1)
            if (distance > 0 && !bean && !lastWasBean && before?.command == Command.STITCH &&
                point(before).distance(b) in 3.0..70.0 && region.coversSegment(point(before), b)
            ) stitches[stitches.lastIndex] = target
            return
        }
        if (!region.coversSegment(a, b)) { jump(target); return }
        val parts = ceil(distance / 68.0).toInt().coerceAtLeast(1)
        require(parts <= 1_000_000)
        for (i in 1..parts) {
            val next = a.interpolate(b, i.toDouble() / parts).let { Stitch(it.x.roundToInt(), it.y.roundToInt(), Command.STITCH) }
            val previous = stitches.last()
            if (point(previous).distance(point(next)) < 3.0) continue
            stitches += next
            if (bean) { stitches += previous; stitches += next }
            lastWasBean = bean
        }
        require(stitches.size <= 1_000_000) { "Filled lettering exceeds command limit" }
    }
    fun path(points: List<Point>, bean: Boolean = false) {
        if (points.isEmpty()) return
        if (!first) {
            val route = routes.route(point(stitches.last()), points.first())
            if (route == null) jump(Stitch(points.first().x.roundToInt(), points.first().y.roundToInt(), Command.STITCH))
            else route.forEach { destination ->
                val start = point(stitches.last())
                val parts = ceil(start.distance(destination) / 25.0).toInt().coerceAtLeast(1)
                for (i in 1..parts) sew(start.interpolate(destination, i.toDouble() / parts))
            }
        }
        points.forEach { sew(it, bean) }
    }
    fun finish(): EmbPattern {
        val sewn = stitches.filter { it.command == Command.STITCH }
        val cx = if (sewn.isEmpty()) 0 else (sewn.minOf { it.x }.toLong() + sewn.maxOf { it.x }).div(2).toInt()
        val cy = if (sewn.isEmpty()) 0 else (sewn.minOf { it.y }.toLong() + sewn.maxOf { it.y }).div(2).toInt()
        val centered = stitches.map { it.copy(x = it.x - cx, y = it.y - cy) }
        val last = centered.lastOrNull()
        return EmbPattern(centered + Stitch(last?.x ?: 0, last?.y ?: 0, Command.END), threads,
            mapOf("name" to "Filled lettering"), warnings.toList())
    }
}
