// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class Grade { GOOD, CAUTION, UNSUITABLE }

data class FontQualityReport(
    val minStrokeMm: Double,
    val recommendedMinHeightMm: Double,
    val stitchCount: Int,
    val jumpCount: Int,
    val tightSpots: Int,
    val grade: Grade,
    val reasons: List<String>,
)

/** Conservative geometry heuristic, not a guarantee of sewability. All inputs are already in mm. */
object FontQuality {
    private const val SAMPLE_MM = 0.25
    private const val FIRST_CELL_MM = 0.05
    private const val CELL_CAPACITY = 16

    /**
     * Outer list = independent glyphs, middle list = contours/strokes, inner list = points.
     * [heightMm] is the CURRENT reference height: no additional normalization is applied.
     * Outline mode compares near-parallel, facing segments, excluding adjacent source edges.
     * Single-stroke mode compares different strokes (including crossings), never glyph pairs.
     *
     * Sampling plus bounded multiresolution spatial buckets avoids all-pairs comparisons:
     * O(n log(D / 0.05)) work and O(n) memory for n samples over glyph extent D. Reservoir
     * representatives keep dense cells bounded, so the reported minimum is approximate.
     * tightSpots counts distinct 0.25 mm arclength sections with a gap below 1 mm, on both sides.
     */
    fun analyze(
        glyphContours: List<List<List<Pair<Double, Double>>>>,
        heightMm: Double,
        outline: Boolean,
    ): FontQualityReport {
        require(heightMm.isFinite() && heightMm > 0) { "heightMm must be finite and positive" }
        require(glyphContours.all { glyph -> glyph.all { path -> path.all { (x, y) -> x.isFinite() && y.isFinite() } } }) {
            "Glyph coordinates must be finite"
        }
        var minimum = Double.POSITIVE_INFINITY
        var tightSpots = 0
        var stitchCount = 0
        var jumpCount = 0
        var hasGeometry = false
        for (glyph in glyphContours) {
            val samples = sample(glyph)
            if (samples.isEmpty()) continue
            hasGeometry = true
            // sourceHeight=heightMm preserves the caller's mm coordinates (scale = 10).
            val pattern = LetteringEngine.fromContours(glyph, heightMm, sourceHeight = heightMm)
            stitchCount = Math.addExact(stitchCount, pattern.stitchCount)
            jumpCount = Math.addExact(jumpCount, pattern.jumpCount)
            val result = measure(samples, outline)
            minimum = min(minimum, result.first)
            tightSpots = Math.addExact(tightSpots, result.second)
        }
        if (!hasGeometry) return FontQualityReport(
            0.0, Double.POSITIVE_INFINITY, 0, 0, 0, Grade.UNSUITABLE,
            listOf("분석할 글자 외곽선이나 획이 없습니다."),
        )
        val recommended = when {
            minimum == Double.POSITIVE_INFINITY -> 0.0
            minimum <= 1e-9 -> Double.POSITIVE_INFINITY
            else -> heightMm * (1.2 / minimum)
        }
        val grade = when {
            minimum < 0.6 -> Grade.UNSUITABLE
            minimum < 1.2 || outline && !minimum.isFinite() -> Grade.CAUTION
            else -> Grade.GOOD
        }
        val reasons = buildList {
            val feature = if (outline) "외곽선 두께 또는 간격" else "획 사이 간격"
            if (!minimum.isFinite()) {
                add(if (outline) "마주보는 외곽선을 찾지 못해 최소 두께를 판단할 수 없습니다." else "서로 가까운 다른 획이 없어 최소 간격 제한이 없습니다.")
            } else {
                add("최소 $feature: ${mm(minimum)}mm (권장 1.2mm 이상).")
                if (minimum < 0.6) add("너무 가늘거나 획이 겹쳐 현재 크기로는 자수에 부적합합니다.")
                else if (minimum < 1.2) add("가까운 획이 뭉칠 수 있어 글자 높이를 키우는 것을 권장합니다.")
                else add("현재 크기에서 권장 최소 두께·간격을 충족합니다.")
                if (minimum < 1.2) {
                    add(if (recommended.isFinite()) "글자 높이를 ${mm(recommended)}mm 이상으로 키워 보세요."
                    else "서로 닿거나 겹치는 획은 확대만으로 해결되지 않으므로 다른 폰트를 확인하세요.")
                }
            }
            if (tightSpots > 0) add("1mm 미만으로 붙은 0.25mm 획 구간이 ${tightSpots}개 있습니다.")
        }
        return FontQualityReport(minimum, recommended, stitchCount, jumpCount, tightSpots, grade, reasons)
    }

    private fun mm(value: Double) = String.format(Locale.ROOT, "%.2f", value)

    private data class Segment(
        val id: Int, val path: Int, val edge: Int, val edgeCount: Int, val closed: Boolean,
        val ax: Double, val ay: Double, val bx: Double, val by: Double, val arc: Double,
    ) {
        val length = hypot(bx - ax, by - ay)
        val ux = (bx - ax) / length
        val uy = (by - ay) / length
        val x = ax / 2 + bx / 2
        val y = ay / 2 + by / 2
    }

    private fun sample(contours: List<List<Pair<Double, Double>>>): List<Segment> {
        val samples = ArrayList<Segment>()
        contours.forEachIndexed { pathIndex, raw ->
            val points = raw.filterIndexed { i, p -> i == 0 || p != raw[i - 1] }
            if (points.size < 2) return@forEachIndexed
            val edges = points.size - 1
            val closed = points.first() == points.last()
            var arc = 0.0
            for (edge in 0 until edges) {
                val (ax, ay) = points[edge]
                val (bx, by) = points[edge + 1]
                val length = hypot(bx - ax, by - ay)
                require(length.isFinite()) { "Glyph extent is too large" }
                if (length == 0.0) continue
                val count = ceil(length / SAMPLE_MM).toInt().coerceAtLeast(1)
                for (part in 0 until count) {
                    val t0 = part.toDouble() / count
                    val t1 = (part + 1).toDouble() / count
                    samples += Segment(
                        samples.size, pathIndex, edge, edges, closed,
                        ax + (bx - ax) * t0, ay + (by - ay) * t0,
                        ax + (bx - ax) * t1, ay + (by - ay) * t1,
                        arc + (part + 0.5) * length / count,
                    )
                }
                arc += length
            }
        }
        return samples
    }

    private data class Cell(val x: Long, val y: Long)
    private data class Section(val path: Int, val index: Long)

    private class Bucket {
        val items = ArrayList<Segment>(CELL_CAPACITY)
        private var seen = 0
        fun add(segment: Segment) {
            seen++
            if (items.size < CELL_CAPACITY) items += segment
            else {
                // Deterministic reservoir: retain representatives throughout a dense cell.
                var hash = segment.id * -1640531527
                hash = (hash xor (hash ushr 16)) * -2048144789
                val slot = (hash.toLong() and 0xffffffffL) % seen
                if (slot < CELL_CAPACITY) items[slot.toInt()] = segment
            }
        }
    }

    private fun measure(samples: List<Segment>, outline: Boolean): Pair<Double, Int> {
        val minX = samples.minOf { min(it.ax, it.bx) }
        val minY = samples.minOf { min(it.ay, it.by) }
        val extent = max(samples.maxOf { max(it.ax, it.bx) } - minX, samples.maxOf { max(it.ay, it.by) } - minY)
        var size = FIRST_CELL_MM
        var minimum = Double.POSITIVE_INFINITY
        val tight = HashSet<Section>()
        while (true) {
            val grid = HashMap<Cell, Bucket>()
            for (segment in samples) {
                val cell = Cell(floor((segment.x - minX) / size).toLong(), floor((segment.y - minY) / size).toLong())
                for (dx in -1L..1L) for (dy in -1L..1L) {
                    val bucket = grid[Cell(cell.x + dx, cell.y + dy)] ?: continue
                    for (other in bucket.items) {
                        if (!eligible(segment, other, outline)) continue
                        val gap = gap(segment, other)
                        if (outline && gap.distance > 1e-9 &&
                            (abs(gap.dx * segment.ux + gap.dy * segment.uy) > gap.distance * 0.3 ||
                                abs(gap.dx * other.ux + gap.dy * other.uy) > gap.distance * 0.3)
                        ) continue
                        minimum = min(minimum, gap.distance)
                        if (gap.distance < 1.0 - 1e-9) {
                            tight += Section(segment.path, floor(segment.arc / SAMPLE_MM).toLong())
                            tight += Section(other.path, floor(other.arc / SAMPLE_MM).toLong())
                        }
                    }
                }
                grid.getOrPut(cell) { Bucket() }.add(segment)
            }
            if (size >= extent) break
            size *= 2
        }
        return minimum to tight.size
    }

    private fun eligible(a: Segment, b: Segment, outline: Boolean): Boolean {
        if (!outline) return a.path != b.path
        if (a.path == b.path) {
            val difference = abs(a.edge - b.edge)
            if (difference <= 1 || a.closed && difference == a.edgeCount - 1) return false
        }
        // Facing sides have near-parallel tangents regardless of contour winding.
        return abs(a.ux * b.ux + a.uy * b.uy) >= 0.95
    }

    private data class Gap(val dx: Double, val dy: Double) { val distance = hypot(dx, dy) }

    /** Exact distance between the candidate sampled line segments, including intersections. */
    private fun gap(a: Segment, b: Segment): Gap {
        val adx = a.bx - a.ax
        val ady = a.by - a.ay
        val bdx = b.bx - b.ax
        val bdy = b.by - b.ay
        val cross = adx * bdy - ady * bdx
        if (abs(cross) > 1e-15) {
            val dx = b.ax - a.ax
            val dy = b.ay - a.ay
            val t = (dx * bdy - dy * bdx) / cross
            val u = (dx * ady - dy * adx) / cross
            if (t in 0.0..1.0 && u in 0.0..1.0) return Gap(0.0, 0.0)
        }
        fun project(x: Double, y: Double, target: Segment): Gap {
            val along = ((x - target.ax) * target.ux + (y - target.ay) * target.uy).coerceIn(0.0, target.length)
            return Gap(target.ax + along * target.ux - x, target.ay + along * target.uy - y)
        }
        return listOf(project(a.ax, a.ay, b), project(a.bx, a.by, b), project(b.ax, b.ay, a), project(b.bx, b.by, a))
            .minBy { it.distance }
    }
}
