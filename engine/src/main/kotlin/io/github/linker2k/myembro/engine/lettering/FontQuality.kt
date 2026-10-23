// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import io.github.linker2k.myembro.engine.geometry.FillRule
import io.github.linker2k.myembro.engine.geometry.Point
import io.github.linker2k.myembro.engine.geometry.PolygonRegion
import io.github.linker2k.myembro.engine.geometry.Polyline
import java.util.Locale
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryCollection
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.geom.Polygon

enum class Grade { GOOD, CAUTION, UNSUITABLE }

/** What sets the recommended height (docs/lettering.md, "글꼴 품질"). */
enum class QualityLimit {
    /** Satin needs ink at least [FontQuality.INK_MIN_MM] wide. */
    INK,
    /** Space between outline strokes, or between parallel single-line strokes. */
    GAP,
    /** Small closed loops of single-line fonts. */
    LOOP,
    /** Nothing measured is tighter than the script's detail floor. */
    DETAIL,
}

data class FontQualityReport(
    /** Smallest of the measured 10th percentiles at the analyzed height; +Infinity when nothing was measured. */
    val minStrokeMm: Double,
    /** Finite and at least the detail floor, in 0.5 mm steps (except for hand-built reports). */
    val recommendedMinHeightMm: Double,
    val stitchCount: Int,
    val jumpCount: Int,
    /** Number of 0.25 mm boundary sections tighter than their limit at the analyzed height. */
    val tightSpots: Int,
    val grade: Grade,
    val reasons: List<String>,
    /** Length-weighted 10th percentile of local stroke width (outline fonts). */
    val inkP10Mm: Double? = null,
    /** Length-weighted 10th percentile of the space between strokes. */
    val gapP10Mm: Double? = null,
    /** Length-weighted 10th percentile of small loop diameters (single-line fonts). */
    val loopP10Mm: Double? = null,
    val limitedBy: QualityLimit = QualityLimit.DETAIL,
)

/**
 * Embroidery suitability of glyph geometry. A heuristic from local measurements, not a guarantee.
 *
 * Outline fonts: overlapping contours are merged by the nonzero rule first (static instances of
 * variable fonts keep their overlaps). From boundary samples every 1/[STEPS_PER_HEIGHT] of the
 * height, a ray along the inward normal gives the local stroke width and one along the outward
 * normal the local gap. Samples within [END_FACTOR] stroke widths of a stroke end (tapered brush
 * tips, serif ends) and gaps within [END_FACTOR] gap widths of the corner that closes them (ink
 * traps, joins) are left out. Of the rest the length-weighted 10th percentile counts, so one thin
 * spot cannot decide the result.
 *
 * Single-line fonts: stroke width is the thread's, so only the space between strokes counts.
 * Crossings and joins are left out, as are parts within [END_FACTOR] gaps of them and strokes
 * bundled into one bolder line ([BUNDLE_FRACTION]). Gaps that
 * close around a short path of ink are loops and need [LOOP_MIN_MM].
 *
 * The recommended height is the largest of the heights that bring each percentile to its limit
 * and the script's detail floor, rounded up to 0.5 mm. It is never 0 or infinite.
 */
object FontQuality {
    /** Bump when the analysis changes; stored reports of older versions are recomputed. */
    const val VERSION = 6

    const val INK_MIN_MM = 1.0
    const val GAP_MIN_MM = 0.6
    const val LINE_GAP_MIN_MM = 1.0
    const val LOOP_MIN_MM = 1.5
    const val HANGUL_DETAIL_MM = 7.0
    const val LATIN_DETAIL_MM = 5.0

    /** Fixed samples per script: dense syllables for Hangul; counters, bowls and joins for Latin. */
    const val HANGUL_SAMPLE = "가나다한글뷁쀍"
    const val LATIN_SAMPLE = "aegsRBQ&@"

    private const val PERCENTILE = 0.10
    private const val END_FACTOR = 1.5
    private const val STEPS_PER_HEIGHT = 200.0
    /** Gaps wider than this fraction of the height are open space, not a constraint. */
    private const val MAX_GAP_FRACTION = 0.5
    /** Single-line strokes closer than this fraction of the height touch (Hershey "A" bar: 1.4 %). */
    private const val CONTACT_FRACTION = 0.025
    /**
     * Single-line strokes running closer than this fraction of the height are one line by design:
     * Hershey's medium, bold and gothic weights draw each line with 2–3 parallel pen strokes
     * 3–4.5 % of the height apart, meant to merge into a bolder line.
     */
    private const val BUNDLE_FRACTION = 0.05
    /** Along-ink path / gap below this: the gap closes around a short loop (a circle is π/2). */
    private const val LOOP_PATH_RATIO = 2.6
    private const val TIGHT_SECTION_MM = 0.25

    /** The fixed analysis sample of a font: Hangul when it has Hangul, otherwise Latin. */
    fun sample(hangul: Boolean): String = if (hangul) HANGUL_SAMPLE else LATIN_SAMPLE

    /**
     * A font's badge from its 10 mm and 20 mm reports: good if fine at 10 mm, caution if usable
     * at 20 mm, otherwise unsuitable; at most caution when it lacks Hangul or Latin.
     */
    fun fontGrade(at10: FontQualityReport, at20: FontQualityReport, missingScript: Boolean): Grade {
        val grade = when {
            at10.grade == Grade.GOOD -> Grade.GOOD
            at20.grade != Grade.UNSUITABLE -> Grade.CAUTION
            else -> Grade.UNSUITABLE
        }
        return if (missingScript && grade == Grade.GOOD) Grade.CAUTION else grade
    }

    fun detailFloorMm(text: String): Double =
        if (text.any { it in '가'..'힣' || it in 'ᄀ'..'ᇿ' || it in '㄰'..'㆏' }) HANGUL_DETAIL_MM
        else LATIN_DETAIL_MM

    /**
     * Outer list = independent glyphs, middle list = contours/strokes, inner list = points, all in
     * mm at [heightMm]. Outline contours are closed implicitly.
     */
    fun analyze(
        glyphContours: List<List<List<Pair<Double, Double>>>>,
        heightMm: Double,
        outline: Boolean,
        detailFloorMm: Double = LATIN_DETAIL_MM,
        threadWeight: ThreadWeight = ThreadWeight.WT40,
    ): FontQualityReport {
        require(heightMm.isFinite() && heightMm > 0) { "heightMm must be finite and positive" }
        require(detailFloorMm.isFinite() && detailFloorMm > 0) { "detailFloorMm must be finite and positive" }
        require(glyphContours.all { glyph -> glyph.all { path -> path.all { (x, y) -> x.isFinite() && y.isFinite() } } }) {
            "Glyph coordinates must be finite"
        }
        val ink = Measures()
        val gap = Measures()
        val loop = Measures()
        var stitchCount = 0
        var jumpCount = 0
        var hasGeometry = false
        for (glyph in glyphContours) {
            val paths = glyph.map { path -> path.filterIndexed { i, p -> i == 0 || p != path[i - 1] } }.filter { it.size >= 2 }
            if (paths.isEmpty()) continue
            hasGeometry = true
            // sourceHeight=heightMm preserves the caller's mm coordinates.
            val pattern = LetteringEngine.fromContours(paths, heightMm, sourceHeight = heightMm)
            stitchCount = Math.addExact(stitchCount, pattern.stitchCount)
            jumpCount = Math.addExact(jumpCount, pattern.jumpCount)
            if (outline) measureOutline(paths, heightMm, ink, gap) else measureStrokes(paths, heightMm, gap, loop)
        }
        val floorMm = ceilHalf(detailFloorMm)
        if (!hasGeometry) return FontQualityReport(
            Double.POSITIVE_INFINITY, floorMm, 0, 0, 0, Grade.UNSUITABLE,
            listOf("분석할 글자 외곽선이나 획이 없습니다."),
        )
        // Finer thread lays narrower columns, so every feature limit shrinks with the preset.
        val scale = threadWeight.featureScale
        val inkLimit = INK_MIN_MM * scale
        val gapLimit = (if (outline) GAP_MIN_MM else LINE_GAP_MIN_MM) * scale
        val loopLimit = LOOP_MIN_MM * scale
        // A percentile can be tiny but never 0: keep the recommendation finite.
        val smallest = heightMm * 1e-3
        val inkP10 = ink.percentile()?.coerceAtLeast(smallest)
        val gapP10 = gap.percentile()?.coerceAtLeast(smallest)
        val loopP10 = loop.percentile()?.coerceAtLeast(smallest)
        val needs = listOfNotNull(
            inkP10?.let { QualityLimit.INK to heightMm * inkLimit / it },
            gapP10?.let { QualityLimit.GAP to heightMm * gapLimit / it },
            loopP10?.let { QualityLimit.LOOP to heightMm * loopLimit / it },
        )
        val (limit, need) = needs.maxByOrNull { it.second }?.takeIf { it.second > floorMm } ?: (QualityLimit.DETAIL to floorMm)
        val recommended = ceilHalf(need)
        val grade = when {
            heightMm >= recommended - 1e-9 -> Grade.GOOD
            heightMm * 2 >= recommended -> Grade.CAUTION
            else -> Grade.UNSUITABLE
        }
        val tight = ink.below(inkLimit) + gap.below(gapLimit) + loop.below(loopLimit)
        val reasons = buildList {
            inkP10?.let { add("얇은 획(하위 10%): ${mm(it)}mm (새틴 최소 ${mm(inkLimit)}mm, ${threadWeight.wt}wt).") }
            gapP10?.let { add("좁은 획 간격(하위 10%): ${mm(it)}mm (최소 ${mm(gapLimit)}mm).") }
            loopP10?.let { add("작은 고리 지름(하위 10%): ${mm(it)}mm (최소 ${mm(loopLimit)}mm).") }
            val cause = when (limit) {
                QualityLimit.INK -> "획 두께"
                QualityLimit.GAP -> "획 사이 간격"
                QualityLimit.LOOP -> "고리 크기"
                QualityLimit.DETAIL -> "글자 세부 표현"
            }
            add("권장 높이 ${mm(recommended)}mm는 $cause 기준입니다.")
            if (heightMm < recommended - 1e-9) add("글자 높이를 ${mm(recommended)}mm 이상으로 키워 보세요.")
            else add("현재 크기에서 권장 획 두께·간격을 충족합니다.")
        }
        return FontQualityReport(
            minStrokeMm = listOfNotNull(inkP10, gapP10, loopP10).minOrNull() ?: Double.POSITIVE_INFINITY,
            recommendedMinHeightMm = recommended,
            stitchCount = stitchCount,
            jumpCount = jumpCount,
            tightSpots = (tight / TIGHT_SECTION_MM).roundToInt(),
            grade = grade,
            reasons = reasons,
            inkP10Mm = inkP10,
            gapP10Mm = gapP10,
            loopP10Mm = loopP10,
            limitedBy = limit,
        )
    }

    private fun ceilHalf(value: Double) = ceil(value * 2 - 1e-9) / 2

    private fun mm(value: Double) = String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')

    /**
     * Length-weighted samples of one measurement over all measured boundary (or stroke) length:
     * length facing nothing nearby counts as unconstrained, so a few tight spots in one glyph
     * do not decide the percentile alone.
     */
    private class Measures {
        private val values = ArrayList<Double>()
        private val weights = ArrayList<Double>()
        private var open = 0.0
        fun add(value: Double, weight: Double) { values += value; weights += weight }
        fun addOpen(weight: Double) { open += weight }
        /** Null when nothing was measured or the percentile falls in unconstrained length. */
        fun percentile(): Double? {
            if (values.isEmpty()) return null
            val order = values.indices.sortedBy { values[it] }
            val target = (weights.sum() + open) * PERCENTILE
            var sum = 0.0
            for (i in order) {
                sum += weights[i]
                if (sum >= target) return values[i]
            }
            return null
        }
        fun below(limit: Double): Double = values.indices.sumOf { if (values[it] < limit - 1e-9) weights[it] else 0.0 }
    }

    // ---- Outline fonts ----

    /** One edge of a merged boundary ring; the ink is on its left. */
    private class Edge(val ring: Int, val ax: Double, val ay: Double, val bx: Double, val by: Double, val arc: Double) {
        val length = hypot(bx - ax, by - ay)
    }

    private class Hit(val distance: Double, val ring: Int, val arc: Double)

    /** A boundary point with its inward normal. */
    private class RingSample(val x: Double, val y: Double, val nx: Double, val ny: Double, val arc: Double, val weight: Double, val edge: Int)

    private fun measureOutline(paths: List<List<Pair<Double, Double>>>, heightMm: Double, ink: Measures, gap: Measures) {
        val rings = mergedRings(paths)
        if (rings.isEmpty()) return
        val edges = ArrayList<Edge>()
        val ringLength = DoubleArray(rings.size)
        val ringEdges = rings.mapIndexed { r, ring ->
            val first = edges.size
            var arc = 0.0
            for (i in ring.indices) {
                val a = ring[i]
                val b = ring[(i + 1) % ring.size]
                edges += Edge(r, a.first, a.second, b.first, b.second, arc)
                arc += edges.last().length
            }
            ringLength[r] = arc
            first until edges.size
        }
        val step = heightMm / STEPS_PER_HEIGHT
        val maxGap = heightMm * MAX_GAP_FRACTION
        for ((r, range) in ringEdges.withIndex()) {
            val samples = ArrayList<RingSample>()
            for (e in range) {
                val edge = edges[e]
                if (edge.length <= 0.0) continue
                val ux = (edge.bx - edge.ax) / edge.length
                val uy = (edge.by - edge.ay) / edge.length
                val n = ceil(edge.length / step).toInt().coerceAtLeast(1)
                for (k in 0 until n) {
                    val t = (k + 0.5) / n
                    samples += RingSample(
                        edge.ax + (edge.bx - edge.ax) * t, edge.ay + (edge.by - edge.ay) * t,
                        -uy, ux, edge.arc + edge.length * t, edge.length / n, e,
                    )
                }
            }
            if (samples.isEmpty()) continue
            val length = ringLength[r]
            fun along(a: Double, b: Double) = abs(a - b).let { min(it, length - it) }
            val widths = samples.map { s -> cast(edges, s, s.nx, s.ny, Double.POSITIVE_INFINITY) }
            for ((i, s) in samples.withIndex()) {
                val hit = widths[i] ?: continue
                if (hit.ring == r) {
                    // The widest part nearby stands for the stroke, so a tip is judged by the stroke it ends.
                    val window = 2 * hit.distance + heightMm * 0.05
                    var stroke = hit.distance
                    for (direction in intArrayOf(1, -1)) {
                        var j = i
                        for (k in 1 until samples.size) {
                            j = Math.floorMod(j + direction, samples.size)
                            if (along(samples[j].arc, s.arc) > window) break
                            widths[j]?.let { stroke = max(stroke, it.distance) }
                        }
                    }
                    val toEnd = (along(hit.arc, s.arc) - hit.distance) / 2
                    if (toEnd < END_FACTOR * stroke) continue
                }
                ink.add(hit.distance, s.weight)
            }
            for ((i, s) in samples.withIndex()) {
                val hit = cast(edges, s, -s.nx, -s.ny, maxGap)
                if (hit == null) { gap.addOpen(s.weight); continue }
                // A notch shallower than the strokes around it (an ink trap, the cusp where a bowl
                // meets a stem) is a corner, not space between strokes.
                val stroke = widths[i]?.distance ?: hit.distance
                if (hit.ring == r && (along(hit.arc, s.arc) - hit.distance) / 2 < END_FACTOR * max(stroke, hit.distance)) continue
                gap.add(hit.distance, s.weight)
            }
        }
    }

    /** Nearest boundary crossing along the ray from [s] in direction ([dx], [dy]), ignoring its own edge. */
    private fun cast(edges: List<Edge>, s: RingSample, dx: Double, dy: Double, maxDistance: Double): Hit? {
        var best = maxDistance
        var hit: Hit? = null
        for ((index, e) in edges.withIndex()) {
            if (index == s.edge) continue
            val ex = e.bx - e.ax
            val ey = e.by - e.ay
            val denominator = dx * ey - dy * ex
            if (abs(denominator) < 1e-12) continue
            val wx = e.ax - s.x
            val wy = e.ay - s.y
            val t = (wx * ey - wy * ex) / denominator
            if (t <= 1e-9 || t >= best) continue
            val u = (wx * dy - wy * dx) / denominator
            if (u < 0.0 || u > 1.0) continue
            best = t
            hit = Hit(t, e.ring, e.arc + u * e.length)
        }
        return hit
    }

    /** Boundary rings of the nonzero union, each oriented with the ink on its left. */
    private fun mergedRings(paths: List<List<Pair<Double, Double>>>): List<List<Pair<Double, Double>>> {
        val region = runCatching {
            PolygonRegion.fromPaths(paths.map { p -> Polyline(p.map { Point(it.first, it.second) }, closed = true) }, FillRule.NON_ZERO)
        }.getOrNull() ?: return emptyList()
        return polygons(region.geometry).flatMap { polygon ->
            listOf(oriented(polygon.exteriorRing, positive = true)) +
                (0 until polygon.numInteriorRing).map { oriented(polygon.getInteriorRingN(it), positive = false) }
        }.filter { it.size >= 3 }
    }

    private fun polygons(g: Geometry): List<Polygon> = when {
        g.isEmpty -> emptyList()
        g is Polygon -> listOf(g)
        g is GeometryCollection -> (0 until g.numGeometries).flatMap { polygons(g.getGeometryN(it)) }
        else -> emptyList()
    }

    /** Shells get a positive signed area and holes a negative one, so the left normal points into the ink. */
    private fun oriented(ring: LineString, positive: Boolean): List<Pair<Double, Double>> {
        val points = ring.coordinates.map { it.x to it.y }.dropLast(1)
        var area = 0.0
        for (i in points.indices) {
            val (ax, ay) = points[i]
            val (bx, by) = points[(i + 1) % points.size]
            area += ax * by - bx * ay
        }
        return if ((area > 0) == positive) points else points.reversed()
    }

    // ---- Single-line fonts ----

    private fun measureStrokes(strokes: List<List<Pair<Double, Double>>>, heightMm: Double, gap: Measures, loop: Measures) {
        val step = heightMm / STEPS_PER_HEIGHT
        val contact = heightMm * CONTACT_FRACTION
        // Dense points of every stroke; a stroke ending where it started is a closed loop.
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        val strokeOf = ArrayList<Int>()
        val starts = IntArray(strokes.size + 1)
        val closed = BooleanArray(strokes.size)
        for ((s, stroke) in strokes.withIndex()) {
            starts[s] = xs.size
            xs += stroke.first().first
            ys += stroke.first().second
            strokeOf += s
            for ((p, q) in stroke.zipWithNext()) {
                val n = max(1, ceil(hypot(q.first - p.first, q.second - p.second) / step).toInt())
                for (k in 1..n) {
                    xs += p.first + (q.first - p.first) * k / n
                    ys += p.second + (q.second - p.second) * k / n
                    strokeOf += s
                }
            }
            closed[s] = hypot(stroke.first().first - stroke.last().first, stroke.first().second - stroke.last().second) < contact
        }
        starts[strokes.size] = xs.size
        val count = xs.size
        fun dist(i: Int, j: Int) = hypot(xs[i] - xs[j], ys[i] - ys[j])
        val arc = DoubleArray(count)
        for (s in strokes.indices) for (i in starts[s] + 1 until starts[s + 1]) arc[i] = arc[i - 1] + dist(i - 1, i)
        /** Distance along one stroke, around the loop when the stroke is closed. */
        fun alongStroke(i: Int, j: Int): Double {
            val s = strokeOf[i]
            val d = abs(arc[i] - arc[j])
            return if (closed[s]) min(d, arc[starts[s + 1] - 1] - d) else d
        }
        fun neighbors(i: Int): List<Int> {
            val s = strokeOf[i]
            val first = starts[s]
            val last = starts[s + 1] - 1
            return buildList {
                if (i > first) add(i - 1) else if (closed[s] && last > first) add(last)
                if (i < last) add(i + 1) else if (closed[s] && last > first) add(first)
            }
        }

        // Contacts: points of another stroke, or of a distant part of the same stroke, within reach.
        val grid = HashMap<Long, MutableList<Int>>()
        fun cell(i: Int) = floor(xs[i] / contact).toLong() to floor(ys[i] / contact).toLong()
        fun key(cx: Long, cy: Long) = cx * 73856093L xor cy * 19349663L
        for (i in 0 until count) cell(i).let { (cx, cy) -> grid.getOrPut(key(cx, cy)) { mutableListOf() } += i }
        val links = Array(count) { ArrayList<Int>(4) }
        val inContact = BooleanArray(count)
        for (i in 0 until count) {
            val (cx, cy) = cell(i)
            for (dx in -1L..1L) for (dy in -1L..1L) for (j in grid[key(cx + dx, cy + dy)].orEmpty()) {
                if (j <= i || dist(i, j) >= contact) continue
                if (strokeOf[i] == strokeOf[j] && alongStroke(i, j) <= 3 * contact) continue
                links[i] += j
                links[j] += i
                inContact[i] = true
                inContact[j] = true
            }
        }

        // A contact cluster is a junction when three or more stroke branches meet there (a crossing,
        // a T, a fork, a retraced stem). Two stroke ends meeting are one continuing line.
        val cluster = IntArray(count) { it }
        fun root(i: Int): Int { var r = i; while (cluster[r] != r) r = cluster[r]; return r }
        fun join(i: Int, j: Int) { val a = root(i); val b = root(j); if (a != b) cluster[a] = b }
        for (i in 0 until count) if (inContact[i]) {
            for (j in links[i]) join(i, j)
            for (j in neighbors(i)) if (inContact[j]) join(i, j)
        }
        val branches = HashMap<Int, Int>()
        for (s in strokes.indices) {
            val first = starts[s]
            val last = starts[s + 1] - 1
            // On a closed stroke, start counting after a gap so a run across the closing point is one run.
            val offset = if (closed[s]) (0..last - first).firstOrNull { !inContact[first + it] } ?: continue else 0
            val size = last - first + 1
            var k = 0
            while (k < size) {
                val i = first + (offset + k) % size
                if (!inContact[i]) { k++; continue }
                val c = root(i)
                var end = k
                while (end + 1 < size && first + (offset + end + 1) % size != i && inContact[first + (offset + end + 1) % size] &&
                    root(first + (offset + end + 1) % size) == c) end++
                val atStart = !closed[s] && i == first
                val atEnd = !closed[s] && first + (offset + end) % size == last
                branches[c] = (branches[c] ?: 0) + (if (atStart) 0 else 1) + (if (atEnd) 0 else 1)
                k = end + 1
            }
        }
        val junction = BooleanArray(count) { inContact[it] && (branches[root(it)] ?: 0) >= 3 }

        // Ink distance to the nearest junction, along strokes and across contacts.
        val toJunction = DoubleArray(count) { Double.POSITIVE_INFINITY }
        val queue = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        for (i in 0 until count) if (junction[i]) { toJunction[i] = 0.0; queue += 0.0 to i }
        while (queue.isNotEmpty()) {
            val (d, i) = queue.poll()
            if (d > toJunction[i]) continue
            for (j in neighbors(i) + links[i]) {
                val nd = d + dist(i, j)
                if (nd < toJunction[j]) { toJunction[j] = nd; queue += nd to j }
            }
        }

        /** Whether ink connects [from] to [to] within [limit]. */
        fun inkPathWithin(from: Int, to: Int, limit: Double): Boolean {
            val best = HashMap<Int, Double>()
            val open = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
            best[from] = 0.0
            open += 0.0 to from
            while (open.isNotEmpty()) {
                val (d, i) = open.poll()
                if (i == to) return true
                if (d > (best[i] ?: Double.POSITIVE_INFINITY)) continue
                for (j in neighbors(i) + links[i]) {
                    val nd = d + dist(i, j)
                    if (nd <= limit && nd < (best[j] ?: Double.POSITIVE_INFINITY)) { best[j] = nd; open += nd to j }
                }
            }
            return false
        }

        val maxGap = heightMm * MAX_GAP_FRACTION
        for (i in 0 until count) {
            if (inContact[i]) continue
            val around = neighbors(i)
            if (around.isEmpty()) continue
            val tx = xs[around.last()] - xs[around.first()]
            val ty = ys[around.last()] - ys[around.first()]
            val length = hypot(tx, ty)
            if (length <= 0.0) continue
            val weight = length / around.size / 2
            for (sign in intArrayOf(1, -1)) {
                val dx = -ty / length * sign
                val dy = tx / length * sign
                var best = maxGap
                var target = -1
                for (s in strokes.indices) for (j in starts[s] until starts[s + 1] - 1) {
                    if (strokeOf[i] == s && min(alongStroke(i, j), alongStroke(i, j + 1)) <= 3 * step) continue
                    val ex = xs[j + 1] - xs[j]
                    val ey = ys[j + 1] - ys[j]
                    val denominator = dx * ey - dy * ex
                    if (abs(denominator) < 1e-12) continue
                    val wx = xs[j] - xs[i]
                    val wy = ys[j] - ys[i]
                    val t = (wx * ey - wy * ex) / denominator
                    if (t <= contact || t >= best) continue
                    val u = (wx * dy - wy * dx) / denominator
                    if (u < 0.0 || u > 1.0) continue
                    best = t
                    target = if (u < 0.5) j else j + 1
                }
                if (target < 0) { gap.addOpen(weight); loop.addOpen(weight); continue }
                if (best < heightMm * BUNDLE_FRACTION) continue
                if (toJunction[i] < END_FACTOR * best) continue
                if (inkPathWithin(i, target, LOOP_PATH_RATIO * best)) { loop.add(best, weight); gap.addOpen(weight) }
                else { gap.add(best, weight); loop.addOpen(weight) }
            }
        }
    }
}
