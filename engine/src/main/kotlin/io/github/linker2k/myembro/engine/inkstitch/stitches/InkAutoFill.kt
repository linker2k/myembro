// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2010 Ink/Stitch Authors (see upstream git history); Kotlin changes: myembro contributors.
// Ported from Ink/Stitch lib/stitches/tatami_fill.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
// lib/stitches/tatami_fill.py: build_fill_stitch_graph, add_edges_between_outline_nodes,
// build_travel_edges, process_travel_edges, find_stitch_path, pick_edge,
// collapse_sequential_outline_edges, travel, path_to_stitches.
// lib/stitches/fill.py: intersect_region_with_grating, adjust_stagger, stitch_row.
// JTS replaces Shapely; bounded local graph/Dijkstra/Hierholzer replace NetworkX.
package io.github.linker2k.myembro.engine.inkstitch.stitches

import io.github.linker2k.myembro.engine.geometry.*
import io.github.linker2k.myembro.engine.geometry.Point
import org.locationtech.jts.geom.*
import org.locationtech.jts.linearref.LengthIndexedLine
import org.locationtech.jts.operation.union.UnaryUnionOp
import org.locationtech.jts.index.strtree.STRtree
import java.util.PriorityQueue
import kotlin.math.*

/** Deterministic constant-spacing, four-stagger auto-fill. Engine units (0.1mm), +y down. */
object InkAutoFill {
    data class Settings(val angleDegrees: Double = 0.0, val rowSpacing: Double = 4.0,
        val stitchLength: Double = 35.0, val travelLength: Double = 25.0,
        val staggers: Int = 4, val underpath: Boolean = true) {
        init { require(rowSpacing.isFinite() && rowSpacing > 0 && stitchLength.isFinite() && stitchLength > 0)
            require(angleDegrees.isFinite() && travelLength.isFinite() && travelLength > 0 && staggers > 0) }
    }
    private val factory = GeometryFactory()
    private const val PX = 10.0 / (96.0 / 25.4) // upstream SVG pixels converted to engine units
    private data class Node(val p: Point, var ring: Int = -1, var projection: Double = 0.0)
    private data class Edge(val a: Int, val b: Int, val segment: Boolean, val path: List<Point>, var weight: Double,
        var enabled: Boolean = true, val coveredTravel: MutableList<Int> = arrayListOf()) {
        fun other(i: Int) = if (a == i) b else a
    }
    private class Graph {
        val nodes = arrayListOf<Node>(); val edges = arrayListOf<Edge>(); val adjacency = arrayListOf<MutableList<Int>>()
        private val ids = hashMapOf<Pair<Long, Long>, Int>()
        fun node(p: Point): Int = ids.getOrPut((p.x * 1e6).roundToLong() to (p.y * 1e6).roundToLong()) {
            require(nodes.size < 100_000) { "Auto-fill exceeds graph node limit" }
            nodes += Node(p); adjacency += arrayListOf<Int>(); nodes.lastIndex
        }
        fun edge(a: Int, b: Int, segment: Boolean, points: List<Point>, weight: Double = length(points)): Int {
            if (a == b) return -1
            require(edges.size < 300_000) { "Auto-fill exceeds graph edge limit" }
            edges += Edge(a, b, segment, points, weight)
            val i = edges.lastIndex; adjacency[a] += i; adjacency[b] += i
            return i
        }
    }
    private fun point(c: Coordinate) = Point(c.x, c.y)
    private fun coord(p: Point) = Coordinate(p.x, p.y)
    private fun line(points: List<Point>) = factory.createLineString(points.map(::coord).toTypedArray())
    private fun length(points: List<Point>) = points.zipWithNext().sumOf { (a, b) -> a.distance(b) }
    private fun lines(g: Geometry): List<LineString> = when (g) {
        is LineString -> listOf(g)
        is GeometryCollection -> (0 until g.numGeometries).flatMap { lines(g.getGeometryN(it)) }
        else -> emptyList()
    }
    private fun polygons(g: Geometry): List<Polygon> = when (g) {
        is Polygon -> listOf(g)
        is GeometryCollection -> (0 until g.numGeometries).flatMap { polygons(g.getGeometryN(it)) }
        else -> emptyList()
    }
    private fun grating(shape: Geometry, angle: Double, spacing: Double): List<List<Point>> {
        val rotate = Affine2D.rotation(-angle); val back = Affine2D.rotation(angle)
        val envelope = shape.coordinates.map(::point).map(rotate::apply)
        val minX = envelope.minOf { it.x }; val maxX = envelope.maxOf { it.x }
        val minY = envelope.minOf { it.y }; val maxY = envelope.maxOf { it.y }
        val count = ceil((maxY - floor(minY / spacing) * spacing) / spacing).toInt()
        require(count in 0..100_000) { "Auto-fill exceeds row limit" }
        val result = arrayListOf<List<Point>>()
        for (row in 0 until count) {
            val y = floor(minY / spacing) * spacing + row * spacing
            // A boundary-coincident grating can create degenerate zero-width rows; JTS keeps only lines.
            val cut = shape.intersection(line(listOf(back.apply(Point(minX - 1, y)), back.apply(Point(maxX + 1, y))))).norm()
            result += lines(cut).map { it.coordinates.map(::point) }.filter { length(it) > 1e-6 }
        }
        return result
    }
    /** Ink/Stitch graph_make_valid -> NetworkX eulerize: duplicate shortest paths between matched odd vertices.
     * Matching is solved exactly by bounded subset DP (same minimum-edge objective, no greedy substitute).
     * More than 18 odd vertices is rejected rather than using an unverified approximate route.
     */
    private fun eulerize(graph: Graph) {
        val odd = graph.nodes.indices.filter { graph.adjacency[it].size % 2 != 0 }
        if (odd.isEmpty()) return
        require(odd.size <= 18) { "Auto-fill odd-vertex matching exceeds verified bound" }
        val paths = Array(odd.size) { arrayOfNulls<List<Int>>(odd.size) }
        for (i in odd.indices) {
            val previous = IntArray(graph.nodes.size) { -1 }; val edge = IntArray(graph.nodes.size) { -1 }
            val queue = ArrayDeque<Int>(); queue.addLast(odd[i]); previous[odd[i]] = odd[i]
            while (queue.isNotEmpty()) {
                val v = queue.removeFirst()
                for (id in graph.adjacency[v]) {
                    val next = graph.edges[id].other(v)
                    if (previous[next] >= 0) continue
                    previous[next] = v; edge[next] = id; queue.addLast(next)
                }
            }
            for (j in i + 1 until odd.size) {
                require(previous[odd[j]] >= 0) { "Disconnected fill graph" }
                val p = arrayListOf<Int>(); var v = odd[j]
                while (v != odd[i]) { p += edge[v]; v = previous[v] }
                paths[i][j] = p
            }
        }
        val size = 1 shl odd.size
        val costs = IntArray(size) { -1 }; val matches = IntArray(size) { -1 }; costs[0] = 0
        fun cost(mask: Int): Int {
            if (costs[mask] >= 0) return costs[mask]
            val i = Integer.numberOfTrailingZeros(mask)
            val remaining = mask xor (1 shl i)
            var best = Int.MAX_VALUE / 2
            for (j in i + 1 until odd.size) if (remaining and (1 shl j) != 0) {
                val c = paths[i][j]!!.size + cost(remaining xor (1 shl j))
                if (c < best) { best = c; matches[mask] = j }
            }
            costs[mask] = best; return best
        }
        var mask = size - 1; cost(mask)
        while (mask != 0) {
            val i = Integer.numberOfTrailingZeros(mask); val j = matches[mask]
            for (id in paths[i][j]!!) {
                val edge = graph.edges[id]
                graph.edge(edge.a, edge.b, edge.segment, edge.path, edge.weight)
            }
            mask = mask xor (1 shl i) xor (1 shl j)
        }
    }

    fun paths(region: PolygonRegion, settings: Settings = Settings()): List<List<Point>> =
        polygons(region.geometry.norm()).flatMap { polygon(it, settings) }

    private fun polygon(shape: Polygon, s: Settings): List<List<Point>> {
        val spans = grating(shape, s.angleDegrees, s.rowSpacing)
        val rings = listOf(shape.exteriorRing) + (0 until shape.numInteriorRing).map(shape::getInteriorRingN)
        if (spans.isEmpty()) return rings.map { InkRunning.even(it.coordinates.map(::point), s.travelLength, 0.0) }
        val indexed = rings.map(::LengthIndexedLine)
        val fill = Graph()
        for (span in spans) fill.edge(fill.node(span.first()), fill.node(span.last()), true, span)
        fun tag(g: Graph) {
            for (n in g.nodes) if (n.ring < 0) {
                val c = factory.createPoint(coord(n.p))
                val nearest = rings.indices.minBy { rings[it].distance(c) }
                if (rings[nearest].distance(c) < 0.01 * PX) {
                    n.ring = nearest; n.projection = indexed[nearest].project(coord(n.p))
                }
            }
        }
        fun arc(ring: Int, start: Double, end: Double): List<Point> {
            if (end > start) return indexed[ring].extractLine(start, end).coordinates.map(::point)
            return indexed[ring].extractLine(start, rings[ring].length).coordinates.map(::point) +
                indexed[ring].extractLine(0.0, end).coordinates.drop(1).map(::point)
        }
        fun outlineEdges(g: Graph, duplicate: Boolean) {
            for (ring in rings.indices) {
                val ids = g.nodes.indices.filter { g.nodes[it].ring == ring }.sortedBy { g.nodes[it].projection }
                for (i in ids.indices) {
                    val a = ids[i]; val b = ids[(i + 1) % ids.size]
                    val path = arc(ring, g.nodes[a].projection, g.nodes[b].projection)
                    g.edge(a, b, false, path, length(path) * 3)
                    if (duplicate && i % 2 == 0) g.edge(a, b, false, path, length(path) * 3)
                }
            }
        }
        tag(fill); outlineEdges(fill, true)
        // Even ring intersections give even degree. Refuse invalid topology rather than inventing a stitch route.
        eulerize(fill)
        val travel = Graph()
        fill.nodes.forEach { travel.node(it.p) }
        val travelEdges = arrayListOf<Int>()
        if (s.underpath) {
            val scale = if (shape.area < 10000 * PX * PX) 0.5 else 1.0
            val grid = grating(shape, s.angleDegrees + 45, scale * 20) +
                grating(shape, s.angleDegrees - 45, scale * 20) +
                grating(shape, s.angleDegrees - 90, scale * sqrt(2.0) * 10)
            if (grid.isNotEmpty()) {
                // Unary union nodes all intersections, the JTS equivalent of Shapely's noded gratings.
                val noded = UnaryUnionOp.union(grid.map(::line)).norm()
                for (ls in lines(noded)) {
                    val points = ls.coordinates.map(::point)
                    val a = travel.node(points.first()); val b = travel.node(points.last())
                    val weight = ls.length / (ls.distance(shape.boundary) + 0.1 * PX)
                    val id = travel.edge(a, b, false, points, weight)
                    if (id >= 0) travelEdges += id
                }
            }
        }
        // Extra polygon vertices keep routes on the actual boundary, including hole corners.
        rings.forEach { ring -> ring.coordinates.forEach { travel.node(point(it)) } }
        tag(travel); outlineEdges(travel, false)
        val index = STRtree()
        travelEdges.forEach { i -> index.insert(line(travel.edges[i].path).envelopeInternal, i) }
        index.build()
        for (edge in fill.edges.filter { it.segment }) {
            val ls = line(edge.path)
            for (id in index.query(ls.envelopeInternal)) {
                val i = id as Int
                if (ls.crosses(line(travel.edges[i].path))) edge.coveredTravel += i
            }
        }
        fun route(a: Int, b: Int): List<Point>? {
            if (a == b) return listOf(travel.nodes[a].p)
            val distances = DoubleArray(travel.nodes.size) { Double.POSITIVE_INFINITY }
            val prev = IntArray(travel.nodes.size) { -1 }; val prevEdge = IntArray(travel.nodes.size) { -1 }
            val queue = PriorityQueue<Pair<Int, Double>>(compareBy { it.second })
            distances[a] = 0.0; queue += a to 0.0
            while (queue.isNotEmpty()) {
                val (v, cost) = queue.remove()
                if (cost > distances[v]) continue
                if (v == b) break
                for (i in travel.adjacency[v]) {
                    val e = travel.edges[i]; if (!e.enabled) continue
                    val to = e.other(v); val d = cost + e.weight
                    if (d < distances[to]) { distances[to] = d; prev[to] = v; prevEdge[to] = i; queue += to to d }
                }
            }
            if (!distances[b].isFinite()) return null
            val reversed = arrayListOf<List<Point>>()
            var v = b
            while (v != a) {
                val e = travel.edges[prevEdge[v]]
                reversed += if (e.a == prev[v]) e.path else e.path.asReversed()
                v = prev[v]
            }
            return reversed.asReversed().flatMapIndexed { i, p -> if (i == 0) p else p.drop(1) }
        }
        // Reverse Hierholzer walk, preferring a fill segment at each vertex, exactly as upstream.
        data class Step(val from: Int, val to: Int, val edge: Int)
        val used = BooleanArray(fill.edges.size)
        val path = arrayListOf<Step>()
        val pending = ArrayDeque<Pair<Int, Int>>()
        for (start in fill.nodes.indices) {
            if (fill.adjacency[start].all { used[it] }) continue
            pending.addLast(start to -1)
            while (pending.isNotEmpty()) {
                val (v, incoming) = pending.last()
                val available = fill.adjacency[v].filterNot { used[it] }
                if (available.isEmpty()) {
                    pending.removeLast()
                    if (pending.isNotEmpty()) path += Step(v, pending.last().first, incoming)
                } else {
                    val id = available.firstOrNull { fill.edges[it].segment } ?: available.first()
                    used[id] = true; pending.addLast(fill.edges[id].other(v) to id)
                }
            }
        }
        val result = arrayListOf<List<Point>>()
        var current = arrayListOf<Point>()
        fun add(points: List<Point>) {
            if (points.isEmpty()) return
            if (current.isNotEmpty() && current.last().distance(points.first()) > 1e-4) {
                result += current; current = arrayListOf()
            }
            current += if (current.isEmpty()) points else points.drop(1)
        }
        val toScan = Affine2D.rotation(-s.angleDegrees); val back = Affine2D.rotation(s.angleDegrees)
        var position: Int? = null
        for (step in path) {
            val edge = fill.edges[step.edge]
            if (!edge.segment) continue // collapse all outline runs into one routed connection
            val a = fill.nodes[step.from].p; val b = fill.nodes[step.to].p
            if (position != null) {
                val travelPath = route(position!!, step.from)
                if (travelPath == null) { if (current.isNotEmpty()) result += current; current = arrayListOf() }
                else add(InkRunning.even(travelPath, s.travelLength, 0.0))
            }
            val p = toScan.apply(a); val q = toScan.apply(b)
            val low = min(p.x, q.x); val high = max(p.x, q.x)
            val row = Math.rint(p.y / s.rowSpacing).toLong()
            val phase = positiveMod(row.toDouble() / s.staggers, 1.0) * s.stitchLength
            val xs = arrayListOf<Double>()
            var x = phase + (floor((low - phase) / s.stitchLength) + 1) * s.stitchLength
            while (x < high - 1e-8) { xs += x; x += s.stitchLength }
            if (p.x > q.x) xs.reverse()
            add(listOf(a) + xs.map { back.apply(Point(it, p.y)) } + b)
            edge.coveredTravel.forEach { travel.edges[it].enabled = false }
            position = step.to
        }
        if (current.isNotEmpty()) result += current
        return result
    }
}
