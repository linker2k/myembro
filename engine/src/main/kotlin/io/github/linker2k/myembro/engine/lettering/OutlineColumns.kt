// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import io.github.linker2k.myembro.engine.geometry.*
import io.github.linker2k.myembro.engine.geometry.Point
import io.github.linker2k.myembro.engine.geometry.Polyline as GeometryPath
import org.locationtech.jts.geom.*
import org.locationtech.jts.geom.prep.PreparedGeometryFactory
import kotlin.math.*

/** Paired rails and their centerline in mm, in sewing order. */
data class OutlineColumn(
    val centerline: List<Pair<Double, Double>>,
    val leftRail: List<Pair<Double, Double>>,
    val rightRail: List<Pair<Double, Double>>,
) {
    val maxWidthMm: Double get() = leftRail.zip(rightRail).maxOfOrNull { (a, b) -> hypot(a.first - b.first, a.second - b.second) } ?: 0.0
}

/**
 * Approximate vector-outline skeleton and normal-ray rail pairing. Bounded 384-pixel long
 * dimension; tiny/unrecoverable components are left for the caller's residual fill.
 * Thinning equations: Guo & Hall, CACM 32(3), 1989, doi:10.1145/62065.62074.
 * Independently implemented; no font/reference stitch data is involved.
 */
object OutlineColumns {
    private val thinningMasks = Array(2) { pass -> BooleanArray(256) { mask ->
        val n = IntArray(8) { (mask ushr it) and 1 }
        val p2 = n[0]; val p3 = n[1]; val p4 = n[2]; val p5 = n[3]
        val p6 = n[4]; val p7 = n[5]; val p8 = n[6]; val p9 = n[7]
        val transitions = ((p2 xor 1) and (p3 or p4)) + ((p4 xor 1) and (p5 or p6)) +
            ((p6 xor 1) and (p7 or p8)) + ((p8 xor 1) and (p9 or p2))
        val neighbors = min((p9 or p2) + (p3 or p4) + (p5 or p6) + (p7 or p8),
            (p2 or p3) + (p4 or p5) + (p6 or p7) + (p8 or p9))
        val preserve = if (pass == 0) (p6 or p7 or (p9 xor 1)) and p8 else (p2 or p3 or (p5 xor 1)) and p4
        transitions == 1 && neighbors in 2..3 && preserve == 0
    } }
    fun extract(contoursMm: List<List<Pair<Double, Double>>>): List<OutlineColumn> = extract(
        PolygonRegion.fromPaths(contoursMm.map { path -> GeometryPath(path.map { (x, y) -> Point(x * 10, y * 10) }, true) }, FillRule.EVEN_ODD),
    )

    internal fun extract(region: PolygonRegion): List<OutlineColumn> {
        if (region.isEmpty) return emptyList()
        val factory = GeometryFactory()
        val prepared = PreparedGeometryFactory.prepare(region.geometry)
        val bounds = region.geometry.envelopeInternal
        val extent = max(bounds.width, bounds.height)
        if (extent < 1.0) return emptyList()
        val step = max(0.6, extent / 384)
        val x0 = bounds.minX - 2 * step
        val y0 = bounds.minY - 2 * step
        val w = ceil(bounds.width / step).toInt() + 5
        val h = ceil(bounds.height / step).toInt() + 5
        val pixels = BooleanArray(w * h)
        fun point(id: Int) = Point(x0 + id % w * step, y0 + id / w * step)
        for (y in 2 until h - 2) for (x in 2 until w - 2) {
            val p = point(y * w + x)
            pixels[y * w + x] = prepared.covers(factory.createPoint(Coordinate(p.x, p.y)))
        }
        val offsets = intArrayOf(-w, -w + 1, 1, w + 1, w, w - 1, -1, -w - 1)
        var iteration = 0
        while (true) {
            var removed = 0
            for (pass in 0..1) {
                val delete = arrayListOf<Int>()
                for (y in 1 until h - 1) for (x in 1 until w - 1) {
                    val id = y * w + x
                    if (!pixels[id]) continue
                    var mask = 0
                    for (i in 0..7) if (pixels[id + offsets[i]]) mask = mask or (1 shl i)
                    if (thinningMasks[pass][mask]) delete += id
                }
                delete.forEach { pixels[it] = false }
                removed += delete.size
            }
            if (removed == 0) break
            require(++iteration < max(w, h)) { "Outline thinning did not converge" }
        }
        val graph = linkedMapOf<Int, MutableSet<Int>>()
        for (id in pixels.indices) if (pixels[id]) {
            graph[id] = offsets.indices.filter { k ->
                val next = id + offsets[k]
                next in pixels.indices && pixels[next] && (k % 2 == 0 ||
                    !pixels[id + offsets[(k + 7) % 8]] && !pixels[id + offsets[(k + 1) % 8]])
            }.map { id + offsets[it] }.toMutableSet()
        }
        // Remove short dead-end raster spurs, while preserving complete narrow strokes.
        repeat(3) {
            val erase = mutableSetOf<Int>()
            for (start in graph.keys.filter { graph[it]?.size == 1 }) {
                val path = arrayListOf(start)
                var previous = -1
                var current = start
                while (true) {
                    val next = graph.getValue(current).firstOrNull { it != previous } ?: break
                    path += next
                    previous = current; current = next
                    if (graph.getValue(current).size != 2) break
                }
                val length = path.zipWithNext().sumOf { (a, b) -> point(a).distance(point(b)) }
                if (graph.getValue(current).size > 2 && length < step * 4) erase += path.dropLast(1)
            }
            erase.forEach { id -> graph[id]?.forEach { graph[it]?.remove(id) }; graph.remove(id) }
        }
        val visited = hashSetOf<Long>()
        fun edge(a: Int, b: Int) = (min(a, b).toLong() shl 32) or max(a, b).toLong()
        val paths = arrayListOf<CenterPath>()
        fun trace(start: Int, next: Int) {
            val ids = arrayListOf(start)
            var a = start; var b = next
            while (visited.add(edge(a, b))) {
                ids += b
                if (b == start || graph.getValue(b).size != 2) break
                val c = graph.getValue(b).first { it != a }
                a = b; b = c
            }
            if (ids.size >= 3) paths += CenterPath(ids.map(::point), graph[start]?.size == 1, graph[ids.last()]?.size == 1)
        }
        graph.filter { it.value.size != 2 }.forEach { (start, neighbors) -> neighbors.forEach { if (edge(start, it) !in visited) trace(start, it) } }
        graph.forEach { (start, neighbors) -> neighbors.forEach { if (edge(start, it) !in visited) trace(start, it) } }
        // Continue near-straight strokes across junctions: X diagonals overlap, rather than
        // being filled at the same global angle or being forced to share a single rail pair.
        mergeAligned(paths, step, region)
        fun section(center: Point, dx: Double, dy: Double): Pair<Point, Point>? {
            val length = hypot(dx, dy)
            if (length < 1e-9) return null
            val nx = dx / length; val ny = dy / length
            val line = factory.createLineString(arrayOf(Coordinate(center.x - nx * extent * 2, center.y - ny * extent * 2),
                Coordinate(center.x + nx * extent * 2, center.y + ny * extent * 2)))
            val cut = region.geometry.intersection(line)
            fun parts(g: Geometry): List<LineString> = when (g) {
                is LineString -> listOf(g)
                is GeometryCollection -> (0 until g.numGeometries).flatMap { parts(g.getGeometryN(it)) }
                else -> emptyList()
            }
            return parts(cut).map { segment ->
                val coordinates = segment.coordinates
                val low = coordinates.minOf { (it.x - center.x) * nx + (it.y - center.y) * ny }
                val high = coordinates.maxOf { (it.x - center.x) * nx + (it.y - center.y) * ny }
                low to high
            }.firstOrNull { it.first <= 1e-6 && it.second >= -1e-6 }?.let { (low, high) ->
                Point(center.x + nx * low, center.y + ny * low) to Point(center.x + nx * high, center.y + ny * high)
            }
        }
        return paths.mapNotNull { path ->
            val source = path.points
            if (source.zipWithNext().sumOf { (a, b) -> a.distance(b) } < step * 3) return@mapNotNull null
            val closed = source.first() == source.last()
            val smoothed = source.mapIndexed { i, p ->
                if (!closed && (i < 2 || i > source.lastIndex - 2)) p else {
                    val around = (-2..2).map { delta -> source[if (closed) Math.floorMod(i + delta, source.lastIndex) else (i + delta).coerceIn(source.indices)] }
                    Point(around.map { it.x }.average(), around.map { it.y }.average())
                }
            }.toMutableList()
            if (closed) smoothed[smoothed.lastIndex] = smoothed.first()
            if (path.extendStart) section(source.first(), source.first().x - source[min(5, source.lastIndex)].x,
                source.first().y - source[min(5, source.lastIndex)].y)?.let { smoothed.add(0, it.second.interpolate(source.first(), 0.001)) }
            if (path.extendEnd) section(source.last(), source.last().x - source[max(0, source.lastIndex - 5)].x,
                source.last().y - source[max(0, source.lastIndex - 5)].y)?.let { smoothed += it.second.interpolate(source.last(), 0.001) }
            val centers = arrayListOf<Pair<Double, Double>>()
            val left = arrayListOf<Pair<Double, Double>>()
            val right = arrayListOf<Pair<Double, Double>>()
            val sections = smoothed.mapIndexedNotNull { i, center ->
                val a = smoothed[if (closed) Math.floorMod(i - 3, smoothed.lastIndex) else max(0, i - 3)]
                val b = smoothed[if (closed) (i + 3) % smoothed.lastIndex else min(smoothed.lastIndex, i + 3)]
                section(center, -(b.y - a.y), b.x - a.x)?.let { center to it }
            }
            if (sections.isEmpty()) return@mapNotNull null
            val widths = sections.map { it.second.first.distance(it.second.second) }.sorted()
            val halfLimit = widths[(widths.size * 0.35).toInt()] * 0.75
            sections.forEach { (center, pair) ->
                // At a junction a normal ray may see the entire crossbar of H/X. Keep
                // this column's local width; the intersecting column covers the junction.
                fun limit(p: Point) = center.interpolate(p, min(1.0, halfLimit / center.distance(p).coerceAtLeast(1e-9)))
                val p = limit(pair.first); val q = limit(pair.second)
                centers += (p.x + q.x) / 20 to (p.y + q.y) / 20
                left += p.x / 10 to p.y / 10
                right += q.x / 10 to q.y / 10
            }
            if (centers.size < 3) null else {
                val column = OutlineColumn(centers, left, right)
                val length = centers.zipWithNext().sumOf { (a, b) -> hypot(a.first - b.first, a.second - b.second) }
                // A short junction spur with a very long normal is not a stroke column.
                // Leave that area to the intersecting column or the residual fill.
                column.takeIf { it.maxWidthMm <= length * 1.5 }
            }
        }
    }

    private data class CenterPath(val points: List<Point>, val extendStart: Boolean, val extendEnd: Boolean) {
        fun reversed() = CenterPath(points.asReversed(), extendEnd, extendStart)
    }
    private fun mergeAligned(paths: MutableList<CenterPath>, step: Double, region: PolygonRegion) {
        while (true) {
            var best = 0.85
            var merge: Triple<Int, Int, CenterPath>? = null
            for (i in paths.indices) for (j in i + 1 until paths.size) for (ra in listOf(false, true)) for (rb in listOf(false, true)) {
                val a = if (ra) paths[i].reversed() else paths[i]
                val b = if (rb) paths[j].reversed() else paths[j]
                if (a.points.first() == a.points.last() || b.points.first() == b.points.last() || a.extendEnd || b.extendStart) continue
                val p = a.points.last(); val q = b.points.first()
                if (p.distance(q) > step * 3 || !region.coversSegment(p, q) && p != q) continue
                val before = a.points[max(0, a.points.lastIndex - 5)]; val after = b.points[min(5, b.points.lastIndex)]
                val ax = p.x - before.x; val ay = p.y - before.y
                val bx = after.x - q.x; val by = after.y - q.y
                val cosine = (ax * bx + ay * by) / (hypot(ax, ay) * hypot(bx, by))
                if (cosine > best) {
                    best = cosine
                    merge = Triple(i, j, CenterPath(a.points + b.points.drop(if (p == q) 1 else 0), a.extendStart, b.extendEnd))
                }
            }
            val (i, j, path) = merge ?: break
            paths[i] = path; paths.removeAt(j)
        }
    }
}
