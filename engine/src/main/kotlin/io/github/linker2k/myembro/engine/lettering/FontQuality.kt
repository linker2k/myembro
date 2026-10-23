// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import io.github.linker2k.myembro.engine.geometry.FillRule
import io.github.linker2k.myembro.engine.geometry.Point
import io.github.linker2k.myembro.engine.geometry.PolygonRegion
import io.github.linker2k.myembro.engine.geometry.Polyline
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryCollection
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.geom.Polygon

enum class Grade { GOOD, CAUTION, UNSUITABLE }

/** What sets the recommended height (docs/lettering.md, "글꼴 품질"). */
enum class QualityLimit {
    /** Satin needs ink at least [FontQuality.SATIN_WIDTH_FACTOR] thread diameters wide. */
    INK,
    /** Space between outline strokes, or between single-line strokes. */
    GAP,
    /** Closed loops of single-line fonts, followed with running stitches. */
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
    /** Length-weighted 10th percentile of the space between strokes (single-line: centre to centre). */
    val gapP10Mm: Double? = null,
    /** Length-weighted 10th percentile of loop size as an equivalent diameter, circumference / π (single-line fonts). */
    val loopP10Mm: Double? = null,
    val limitedBy: QualityLimit = QualityLimit.DETAIL,
)

/**
 * Embroidery suitability of glyph geometry. A heuristic from local measurements, not a guarantee.
 *
 * Outline fonts: overlapping contours are merged by the nonzero rule and specks (brush texture)
 * dropped. From boundary samples every 1/[STEPS_PER_HEIGHT] of the height, a ray along the inward
 * normal gives the local stroke width and one along the outward normal the local gap. Stroke ends
 * (tapered tips, serif ends) are left out, as are gaps whose far side does not face back (open
 * corners) and the tips of notches. Of the rest the length-weighted 10th percentile counts, so one
 * thin spot cannot decide the result.
 *
 * Single-line fonts are drawn as lines [LINE_WIDTH_FRACTION] of the height wide, so strokes
 * meant to merge (Hershey's multi-stroke weights) merge and joins become notches, and measured
 * like outlines: the gap is centre to centre, the counters are loops.
 *
 * Limits follow the thread (docs/lettering.md): satin ≥ [SATIN_WIDTH_FACTOR] diameters, a counter
 * still one diameter open after [SPREAD_MM] spread, a loop long enough for [LOOP_STITCHES]
 * running stitches of [CURVE_STITCH_MM], all on the [GRID_MM] coordinate grid. The recommended
 * height is the largest height bringing each percentile to its limit and the detail floor,
 * rounded up to 0.5 mm. It is never 0 or infinite.
 */
object FontQuality {
    /** Bump when the analysis changes; stored reports of older versions are recomputed. */
    const val VERSION = 7

    /** Satin column at least this many thread diameters wide (practice value, docs/lettering.md). */
    const val SATIN_WIDTH_FACTOR = 2.5
    /** Thread spreads into a counter by about this much (practice value). */
    const val SPREAD_MM = 0.2
    /** Running stitch length along curves (practice range 1.0–1.5 mm; the short end). */
    const val CURVE_STITCH_MM = 1.0
    /** A loop needs at least this many stitches around to stay round. */
    const val LOOP_STITCHES = 6
    /** PES/PEC coordinate unit. */
    const val GRID_MM = 0.1
    const val HANGUL_DETAIL_MM = 8.0
    const val LATIN_DETAIL_MM = 6.0
    /** No general-purpose font is recommended at or below 5 mm, whatever the thread. */
    const val MIN_DETAIL_MM = 5.5

    /** Fixed samples: dense syllables for Hangul; counters, bowls and joins for Latin. */
    const val HANGUL_SAMPLE = "가나다한글뷁쀍"
    const val LATIN_SAMPLE = "aegsRBQ&@"

    private const val PERCENTILE = 0.10
    private const val STEPS_PER_HEIGHT = 200.0
    /** Gaps wider than this fraction of the height are open space, not a constraint. */
    private const val MAX_GAP_FRACTION = 0.5
    /** Stroke ends: samples within this many stroke widths plus [TIP_FRACTION] of the height. */
    private const val TIP_FACTOR = 0.5
    private const val TIP_FRACTION = 0.02
    /** Notch tips: gaps closer than this many yardsticks to the corner that closes them. */
    private const val NOTCH_FACTOR = 1.5
    /** On outer boundaries the yardstick is at least this share of the local stroke width. */
    private const val NOTCH_STROKE_SHARE = 0.7
    /** The far side of a gap must face back within 60°. */
    private const val FACING_COS = -0.5
    /** Ink islands smaller than this share of height² are texture specks. */
    private const val SPECK_FRACTION = 0.0005
    /**
     * Single-line strokes are drawn this share of the height wide (40wt thread at 6.7 mm), so
     * Hershey's multi-stroke weights, drawn 1 font unit (4.7–5.4 % of the height) apart to merge
     * into a bolder line, merge.
     */
    private const val LINE_WIDTH_FRACTION = 0.06
    /** Single-line pockets smaller than this share of height² fill in (where bundled strokes part). */
    private const val POCKET_FRACTION = 0.002
    /** Holes at least this round (4πA/P², a circle is 1) are loops whatever their size. */
    private const val ROUND_HOLE = 0.5
    private const val TIGHT_SECTION_MM = 0.25

    /** The fixed analysis sample of a font: Hangul and Latin when it has Hangul, otherwise Latin. */
    fun sample(hangul: Boolean): String = if (hangul) HANGUL_SAMPLE + LATIN_SAMPLE else LATIN_SAMPLE

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

    /** The 40wt detail floor of [text]; [analyze] scales it for finer thread. */
    fun detailFloorMm(text: String): Double =
        if (text.any { it in '가'..'힣' || it in 'ᄀ'..'ᇿ' || it in '㄰'..'㆏' }) HANGUL_DETAIL_MM
        else LATIN_DETAIL_MM

    /** Narrowest satin column for [weight], on the coordinate grid. */
    fun satinMinMm(weight: ThreadWeight): Double = onGrid(SATIN_WIDTH_FACTOR * weight.diameterMm)

    /** Narrowest outline gap: one diameter left open after spread. */
    fun gapMinMm(weight: ThreadWeight): Double = onGrid(weight.diameterMm + SPREAD_MM)

    /** Narrowest single-line spacing, centre to centre: two half lines, spread and one open diameter. */
    fun lineGapMinMm(weight: ThreadWeight): Double = onGrid(2 * weight.diameterMm + SPREAD_MM)

    /** Smallest loop as an equivalent diameter: [LOOP_STITCHES] stitches of [CURVE_STITCH_MM] around. */
    fun loopMinMm(): Double = onGrid(LOOP_STITCHES * CURVE_STITCH_MM / PI)

    /**
     * Outer list = independent glyphs, middle list = contours/strokes, inner list = points, all in
     * mm at [heightMm]. Outline contours are closed implicitly. [detailFloorMm] is the 40wt floor.
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
        val floorMm = ceilHalf(max(MIN_DETAIL_MM, detailFloorMm * threadWeight.floorScale))
        if (!hasGeometry) return FontQualityReport(
            Double.POSITIVE_INFINITY, floorMm, 0, 0, 0, Grade.UNSUITABLE,
            listOf("분석할 글자 외곽선이나 획이 없습니다."),
        )
        val inkLimit = satinMinMm(threadWeight)
        val gapLimit = if (outline) gapMinMm(threadWeight) else lineGapMinMm(threadWeight)
        val loopLimit = loopMinMm()
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
            gapP10?.let {
                if (outline) add("좁은 획 간격(하위 10%): ${mm(it)}mm (최소 ${mm(gapLimit)}mm, 실 퍼짐 포함).")
                else add("좁은 획 간격(하위 10%, 중심 간): ${mm(it)}mm (최소 ${mm(gapLimit)}mm, ${threadWeight.wt}wt).")
            }
            loopP10?.let { add("작은 고리 지름(하위 10%): ${mm(it)}mm (최소 ${mm(loopLimit)}mm, 둘레에 ${LOOP_STITCHES}땀).") }
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

    /** Rounds a physical limit up to the stitch coordinate grid: the machine cannot place finer. */
    private fun onGrid(value: Double) = ceil(value / GRID_MM - 1e-9) * GRID_MM

    private fun mm(value: Double) = String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')

    /** Length-weighted samples of one measurement; only constrained length counts. */
    private class Measures {
        private val values = ArrayList<Double>()
        private val weights = ArrayList<Double>()
        fun add(value: Double, weight: Double) { values += value; weights += weight }
        /** Null when nothing was measured. */
        fun percentile(): Double? {
            if (values.isEmpty()) return null
            val order = values.indices.sortedBy { values[it] }
            val target = weights.sum() * PERCENTILE
            var sum = 0.0
            for (i in order) {
                sum += weights[i]
                if (sum >= target) return values[i]
            }
            return values[order.last()]
        }
        fun below(limit: Double): Double = values.indices.sumOf { if (values[it] < limit - 1e-9) weights[it] else 0.0 }
    }

    // ---- Boundary measurement, shared by outline and single-line fonts ----

    /** One edge of a boundary ring; the ink is on its left. */
    private class Edge(val ring: Int, val ax: Double, val ay: Double, val bx: Double, val by: Double, val arc: Double) {
        val length = hypot(bx - ax, by - ay)
    }

    /** A ray's first crossing, with the outward normal of the edge it crossed. */
    private class Hit(val distance: Double, val ring: Int, val arc: Double, val ox: Double, val oy: Double)

    /** A boundary point with its inward normal. */
    private class RingSample(val x: Double, val y: Double, val nx: Double, val ny: Double, val arc: Double, val weight: Double, val edge: Int)

    /** Rings of a region, oriented with the ink on their left, and which of them are holes. */
    private class Rings(val rings: List<List<Pair<Double, Double>>>, val holes: Set<Int>)

    private fun measureOutline(paths: List<List<Pair<Double, Double>>>, heightMm: Double, ink: Measures, gap: Measures) {
        val region = runCatching {
            PolygonRegion.fromPaths(paths.map { p -> Polyline(p.map { Point(it.first, it.second) }, closed = true) }, FillRule.NON_ZERO)
        }.getOrNull() ?: return
        val polygons = polygons(region.geometry).filter { it.area >= SPECK_FRACTION * heightMm * heightMm }
        measureRings(rings(polygons, minHoleArea = 0.0), heightMm, ink, gap, gapOffset = 0.0)
    }

    /**
     * Measures [region]: stroke widths into [ink] when given, gaps (plus [gapOffset]) into [gap].
     * Ink: a sample whose ray comes back to its own ring near where it started sits in a stroke
     * end; the widest part nearby stands for the stroke. Gap: the far side must face back, and a
     * notch tip (the hit close along the ring) is a corner, not space between strokes.
     */
    private fun measureRings(region: Rings, heightMm: Double, ink: Measures?, gap: Measures, gapOffset: Double) {
        val rings = region.rings
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
            if (ink != null) for ((i, s) in samples.withIndex()) {
                val hit = widths[i] ?: continue
                if (hit.ring == r) {
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
                    if (toEnd < TIP_FACTOR * stroke + TIP_FRACTION * heightMm) continue
                }
                ink.add(hit.distance, s.weight)
            }
            for ((i, s) in samples.withIndex()) {
                val hit = cast(edges, s, -s.nx, -s.ny, maxGap) ?: continue
                if (-s.nx * hit.ox - s.ny * hit.oy > FACING_COS) continue
                // Counters are judged by the gap alone; on outer boundaries a notch is also measured
                // against the strokes around it (an ink trap, the cusp where a bowl meets a stem).
                val yardstick = if (r in region.holes) hit.distance
                else max(NOTCH_STROKE_SHARE * (widths[i]?.distance ?: 0.0), hit.distance)
                if (hit.ring == r && (along(hit.arc, s.arc) - hit.distance) / 2 < NOTCH_FACTOR * yardstick) continue
                gap.add(hit.distance + gapOffset, s.weight)
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
            hit = Hit(t, e.ring, e.arc + u * e.length, ey / e.length, -ex / e.length)
        }
        return hit
    }

    private fun rings(polygons: List<Polygon>, minHoleArea: Double): Rings {
        val rings = ArrayList<List<Pair<Double, Double>>>()
        val holes = HashSet<Int>()
        for (polygon in polygons) {
            oriented(polygon.exteriorRing, positive = true).takeIf { it.size >= 3 }?.let { rings += it }
            for (k in 0 until polygon.numInteriorRing) {
                val hole = polygon.getInteriorRingN(k)
                val area = polygon.factory.createPolygon(hole.coordinates).area
                // A small sliver is a pocket between merging strokes; a small round hole is still a loop.
                if (area < minHoleArea && 4 * PI * area / (hole.length * hole.length) < ROUND_HOLE) continue
                oriented(hole, positive = false).takeIf { it.size >= 3 }?.let { holes += rings.size; rings += it }
            }
        }
        return Rings(rings, holes)
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

    private val geometryFactory = GeometryFactory()

    /**
     * Draws the strokes [LINE_WIDTH_FRACTION] of the height wide and measures the drawing: gaps
     * plus the line width are centre-to-centre spacing; each counter is a loop whose centre line
     * runs half a line width outside it.
     */
    private fun measureStrokes(strokes: List<List<Pair<Double, Double>>>, heightMm: Double, gap: Measures, loop: Measures) {
        val width = LINE_WIDTH_FRACTION * heightMm
        val lines = strokes.map { s -> geometryFactory.createLineString(s.map { (x, y) -> Coordinate(x, y) }.toTypedArray()) }
        val drawn = runCatching { geometryFactory.createMultiLineString(lines.toTypedArray()).buffer(width / 2, 8) }.getOrNull() ?: return
        val region = rings(polygons(drawn), minHoleArea = POCKET_FRACTION * heightMm * heightMm)
        measureRings(region, heightMm, ink = null, gap = gap, gapOffset = width)
        for (h in region.holes) {
            val ring = region.rings[h]
            val perimeter = ring.indices.sumOf { i -> ring[i].let { (ax, ay) -> ring[(i + 1) % ring.size].let { (bx, by) -> hypot(bx - ax, by - ay) } } }
            val circumference = perimeter + PI * width
            loop.add(circumference / PI, circumference)
        }
    }
}
