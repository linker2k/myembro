// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/stitches/tatami_fill.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.stitches

import io.github.linker2k.myembro.engine.inkstitch.svg.PIXELS_PER_MM
import org.locationtech.jts.geom.*
import org.locationtech.jts.linearref.LengthIndexedLine
import org.locationtech.jts.operation.overlayng.OverlayNG
import org.locationtech.jts.operation.overlayng.OverlayNGRobust
import org.locationtech.jts.operation.overlay.snap.GeometrySnapper
import org.locationtech.jts.index.strtree.STRtree
import io.github.linker2k.myembro.engine.geometry.LegacyCrosses
import org.locationtech.jts.precision.GeometryPrecisionReducer
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier
import java.util.PriorityQueue
import java.util.ArrayDeque
import org.jgrapht.graph.DefaultWeightedEdge
import org.jgrapht.graph.SimpleWeightedGraph
import org.jgrapht.alg.matching.blossom.v5.KolmogorovWeightedPerfectMatching
import kotlin.math.*

/** Original fill multigraph / Hierholzer / disappearing underpath edges, without networkx. */
object FillRouting {
    fun routeSegments(shape: Geometry, segments: List<List<FillPoint>>, startingPoint: FillPoint?,
        endingPoint: FillPoint?, underpath: Boolean, runningLengthPx: Double, tolerancePx: Double,
        angle: Double = 0.0, skipLast: Boolean = false): List<FillPoint> = route(shape, segments,
            startingPoint, endingPoint, underpath, runningLengthPx, tolerancePx, angle, skipLast) { points, reversed ->
        var directed = (if (reversed) points.reversed() else points).map {
            it.copy(tags = it.tags - "fill_row_start" - "fill_row_end")
        }
        if (skipLast && directed.size > 1) directed = directed.dropLast(1)
        directed.mapIndexed { index, point -> point.copy(tags = point.tags +
            (if (index == 0) setOf("fill_row_start") else emptySet()) +
            (if (index == directed.lastIndex) setOf("fill_row_end") else emptySet())) }
    }

    internal fun route(shape: Geometry, segments: List<List<FillPoint>>, startingPoint: FillPoint?,
        endingPoint: FillPoint?, underpath: Boolean, runningLengthPx: Double, tolerancePx: Double,
        angle: Double, skipLast: Boolean, gapFillRows: Int = 0,
        render: (List<FillPoint>, Boolean) -> List<FillPoint>): List<FillPoint> {
        if (shape.isEmpty || segments.isEmpty()) return emptyList()
        require(segments.size <= 200_000)
        val parts = FillGeometry.polygons(FillGeometry.makeValid(shape))
        if (parts.size != 1) {
            val result = mutableListOf<FillPoint>()
            var previous = startingPoint
            for (part in parts.sortedBy { if (previous == null) it.envelopeInternal.minX else it.distance(FillGeometry.point(previous!!)) }) {
                val own = segments.filter { part.buffer(1e-6).covers(FillGeometry.line(it)) }
                val path = route(part, own, previous, endingPoint, underpath, runningLengthPx, tolerancePx,
                    angle, skipLast, gapFillRows, render)
                if (path.isNotEmpty()) {
                    result += if (result.isEmpty()) path.first() else path.first().tagged("jump")
                    result += path.drop(1); previous = path.last()
                }
            }
            return result
        }
        val polygon = parts.single()
        val outlines = listOf(polygon.exteriorRing) + (0 until polygon.numInteriorRing).map(polygon::getInteriorRingN)
        val fill = Graph()
        for ((index, segment) in segments.withIndex()) {
            require(segment.size >= 2)
            val a = fill.node(segment.first()); val b = fill.node(segment.last())
            fill.add(a, b, "segment", index, segment)
        }
        tagNodes(fill, outlines)
        outlineEdges(fill)
        startingPoint?.let { insertNode(fill, outlines, it) }
        endingPoint?.let { insertNode(fill, outlines, it) }
        FillTrace.emit("graph_before_eulerize") { FillTrace.graph(fill) }
        eulerize(fill)
        FillTrace.emit("graph_after_eulerize") { FillTrace.graph(fill) }
        val travel = buildTravelGraph(fill, polygon, outlines, angle, underpath)
        FillTrace.emit("travel_graph") { FillTrace.graph(travel) }
        if (travel.nodes.isEmpty()) return emptyList()
        val startReference = startingPoint ?: fill.nodes.keys.first().point()
        val endReference = endingPoint ?: startReference
        val start = nearest(fill.nodes.keys, startReference)
        val end = nearest(fill.nodes.keys, endReference)
        val graph = fill.copy()
        val path = mutableListOf<PathEdge>()
        val stack = mutableListOf(end to null as Edge?)
        var lastVertex: Node? = null
        var lastEdge: Edge? = null
        while (stack.isNotEmpty()) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val (vertex, via) = stack.last()
            val edges = graph.at(vertex)
            if (edges.isEmpty()) {
                if (lastVertex != null) path += PathEdge(lastVertex, vertex, lastEdge!!)
                lastVertex = vertex; lastEdge = via; stack.removeAt(stack.lastIndex)
            } else {
                val edge = edges.firstOrNull { it.key == "segment" } ?: edges.first()
                stack += edge.other(vertex) to edge
                graph.remove(edge)
            }
        }
        check(graph.edges.isEmpty()) { "Disconnected fill graph cannot have an Eulerian route" }
        if (start != end) {
            if (underpath || fill.nodes.getValue(start).outline == fill.nodes.getValue(end).outline)
                path.add(0, PathEdge(start, end, Edge(-1, start, end, "initial")))
            else {
                val composed = fill.copy()
                travel.edges.values.forEach { composed.add(it.a, it.b, it.key, it.segment, it.points, it.weight) }
                val initial = shortest(composed, start, end)
                initial.zipWithNext().forEachIndexed { index, (a, b) ->
                    val edge = composed.at(a).firstOrNull { it.other(a) == b && it.key == "segment" }
                    path.add(index, PathEdge(a, b, edge ?: Edge(-1, a, b, "outline-start$index")))
                }
            }
        }
        val realStart = nearest(travel.nodes.keys, startReference)
        val realEnd = nearest(travel.nodes.filterValues { it.outline != null }.keys, endReference)
        path.add(0, PathEdge(realStart, start, Edge(-1, realStart, start, "outline")))
        path += PathEdge(end, realEnd, Edge(-1, end, realEnd, "outline"))
        val collapsed = collapse(fillGaps(path, gapFillRows), fill)
        FillTrace.emit("collapsed_euler_path") { collapsed.joinToString(";") { "${it.a}|${it.b}|${it.edge.key}" } }
        val result = mutableListOf<FillPoint>()
        if (collapsed.isNotEmpty() && !collapsed.first().segment()) result += collapsed.first().a.point()
        for (edge in collapsed) {
            if (edge.segment()) {
                val source = edge.edge.points ?: listOf(edge.a.point(), edge.b.point())
                val rendered = render(source, source.first().distance(edge.a.point()) > source.last().distance(edge.a.point()))
                check(rendered.isEmpty() || rendered.first().distance(edge.a.point()) <= 1e-4) {
                    "Fill segment geometry does not start at its graph vertex: ${edge.a} -> ${rendered.first()}"
                }
                check(skipLast || result.isEmpty() || result.last().distance(edge.a.point()) <= 0.1*PIXELS_PER_MM+1e-4) {
                    "Fill travel did not reach next row: ${result.last()} -> ${edge.a} (key=${edge.edge.key})"
                }
                result += rendered
                // Once a row is sewn, travel crossing it would be visible: remove those edges.
                val original = fill.at(edge.a).firstOrNull { it.other(edge.a) == edge.b && it.key == "segment" }
                original?.underpathEdges?.forEach { travel.edges[it]?.let(travel::remove) }
            } else {
                val nodes = shortest(travel, edge.a, edge.b)
                // Never turn an unreachable route across a hole into an unmarked sewing line.
                require(nodes.isNotEmpty()) { "No travel route inside fill polygon" }
                var points = nodes.map(Node::point)
                if (underpath && points.size > 2) points = FillGeometry.smoothPath(points, 2.0)
                points = clampPath(points, polygon)
                check(points.isEmpty() || points.last().distance(edge.b.point()) < 1e-4) { "Fill travel clamping lost its endpoint" }
                val stitches = FillRunning.run(points, runningLengthPx, tolerancePx).map { it.tagged("travel") }
                result += if (skipLast && !edge.outline()) stitches else stitches.drop(1)
            }
            require(result.size <= 1_000_000) { "Fill exceeds 1000000 stitches" }
        }
        FillTrace.emit("rendered_route") { FillTrace.points(listOf(result)) }
        return result
    }

    internal data class Node(val x: Double, val y: Double) { fun point() = FillPoint(x, y) }
    internal data class NodeData(var outline: Int? = null, var projection: Double = 0.0)
    internal class Edge(val id: Int, val a: Node, val b: Node, val key: String,
        val segment: Int? = null, val points: List<FillPoint>? = null, var weight: Double = 1.0) {
        val underpathEdges = mutableSetOf<Int>()
        fun other(node: Node) = if (node == a) b else a
    }
    private data class PathEdge(val a: Node, val b: Node, val edge: Edge) {
        fun segment() = edge.key.startsWith("segment")
        fun outline() = listOf("outline", "extra", "initial").any(edge.key::startsWith)
    }
    internal class Graph {
        val nodes = linkedMapOf<Node, NodeData>()
        val edges = linkedMapOf<Int, Edge>()
        private val adjacency = linkedMapOf<Node, LinkedHashMap<Node, LinkedHashMap<String, Edge>>>()
        private var nextId = 0
        fun node(point: FillPoint): Node {
            // GEOS snap(0.005px) is replaced by stable 1e-6px graph node canonicalization.
            fun canonical(value: Double): Double = (Math.rint(value*1e6)/1e6).let { if (it == 0.0) 0.0 else it }
            val n = Node(canonical(point.x), canonical(point.y))
            if (n !in nodes) { nodes[n] = NodeData(); adjacency[n] = linkedMapOf() }
            return n
        }
        fun add(a: Node, b: Node, key: String, segment: Int? = null, points: List<FillPoint>? = null,
            weight: Double = 1.0): Edge {
            node(a.point()); node(b.point())
            val existing = adjacency.getValue(a)[b]?.get(key)
            if (existing != null) { existing.weight = weight; return existing }
            val edge = Edge(nextId++, a, b, key, segment, points, weight)
            edges[edge.id] = edge
            adjacency.getValue(a).getOrPut(b) { linkedMapOf() }[key] = edge
            adjacency.getValue(b).getOrPut(a) { linkedMapOf() }[key] = edge
            require(edges.size <= 1_000_000) { "Fill graph exceeds 1000000 edges" }
            return edge
        }
        fun at(a: Node) = adjacency[a]?.values?.flatMap { it.values } ?: emptyList()
        fun has(a: Node, b: Node) = adjacency[a]?.containsKey(b) == true
        fun remove(edge: Edge) {
            edges.remove(edge.id)
            for ((a, b) in listOf(edge.a to edge.b, edge.b to edge.a)) {
                adjacency[a]?.get(b)?.remove(edge.key)
                if (adjacency[a]?.get(b)?.isEmpty() == true) adjacency[a]?.remove(b)
            }
        }
        fun copy(): Graph {
            val copy = Graph()
            for ((node, data) in nodes) { copy.node(node.point()); copy.nodes[node] = data.copy() }
            // MultiGraph.copy reinserts every adjacency in node -> neighbor -> key order.
            // Global edge insertion order is different: it can change pick_edge's first
            // outline neighbor and therefore reverse whole fill sections after a copy.
            for (node in nodes.keys) for (edge in at(node))
                copy.add(node, edge.other(node), edge.key, edge.segment, edge.points, edge.weight)
            return copy
        }
    }

    private fun nearest(nodes: Collection<Node>, point: FillPoint) = nodes.minBy { it.point().distance(point) }
    private fun tagNodes(graph: Graph, outlines: List<LineString>) {
        for ((node, data) in graph.nodes) {
            val p = FillGeometry.point(node.point())
            val index = outlines.indices.minBy { outlines[it].distance(p) }
            data.outline = index
            data.projection = LengthIndexedLine(outlines[index]).project(Coordinate(node.x, node.y))
        }
    }
    private fun outlineEdges(graph: Graph) {
        for (nodes in graph.nodes.keys.sortedWith(compareBy<Node> { graph.nodes[it]!!.outline }.thenBy { graph.nodes[it]!!.projection })
            .groupBy { graph.nodes[it]!!.outline }.values) {
            for (i in nodes.indices) {
                val a = nodes[i]; val b = nodes[(i+1)%nodes.size]
                graph.add(a, b, "outline")
                // Upstream adds extras even when duplicate_every_other=False.
                if (i%2 == 0) graph.add(a, b, "extra")
            }
        }
    }
    private fun insertNode(graph: Graph, outlines: List<LineString>, reference: FillPoint) {
        val i = outlines.indices.minBy { outlines[it].distance(FillGeometry.point(reference)) }
        val indexed = LengthIndexedLine(outlines[i])
        val projection = indexed.project(Coordinate(reference.x, reference.y))
        val p = indexed.extractPoint(projection)
        val node = graph.node(FillPoint(p.x, p.y))
        graph.nodes.getValue(node).apply { outline = i; this.projection = projection }
        val edges = graph.edges.values.filter { it.key == "outline" && graph.nodes[it.a]!!.outline == i }
        if (edges.any { it.a == node || it.b == node }) return
        if (edges.isEmpty()) {
            // A boundary untouched by rows: retain the projected travel target without adding an unsafe fill row.
            graph.nodes.remove(node)
            return
        }
        val edge = edges.minBy { FillGeometry.line(listOf(it.a.point(), it.b.point())).distance(FillGeometry.point(node.point())) }
        graph.remove(edge)
        graph.add(edge.a, node, "outline"); graph.add(node, edge.b, "outline")
    }

    private fun eulerize(graph: Graph) {
        val odd = graph.nodes.keys.filter { node -> graph.at(node).fold(0) { sum, edge -> sum + if (edge.a == edge.b) 2 else 1 } % 2 != 0 }
        if (odd.isEmpty()) return
        require(odd.size <= 256 && odd.size.toLong()*graph.edges.size <= 20_000_000) { "Fill Euler augmentation exceeds resource limit" }
        val matchingGraph = SimpleWeightedGraph<Int,DefaultWeightedEdge>(DefaultWeightedEdge::class.java)
        odd.indices.forEach(matchingGraph::addVertex)
        for (i in odd.indices) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val distances = unweightedDistances(graph,odd[i])
            for (j in i+1 until odd.size) {
                val distance = distances[odd[j]] ?: error("Disconnected fill graph")
                matchingGraph.setEdgeWeight(matchingGraph.addEdge(i,j),distance.toDouble())
            }
        }
        // NetworkX eulerize minimizes unweighted duplicated path length, not geometric length.
        FillTrace.emit("matching_input") { matchingGraph.edgeSet().joinToString(";") { "${matchingGraph.getEdgeSource(it)},${matchingGraph.getEdgeTarget(it)},${matchingGraph.getEdgeWeight(it).toBits()}" } }
        val matching = KolmogorovWeightedPerfectMatching(matchingGraph).matching
        require(matching.edges.size*2 == odd.size) { "Fill graph has no perfect odd-vertex matching" }
        var duplicate = 0
        val pairs = matching.edges.map { edge ->
            val a = matchingGraph.getEdgeSource(edge); val b = matchingGraph.getEdgeTarget(edge)
            min(a,b) to max(a,b)
        }.sortedWith(compareBy<Pair<Int,Int>> { it.first }.thenBy { it.second })
        FillTrace.emit("matching_pairs") { pairs.toString() }
        for ((i,j) in pairs) {
            for ((a, b) in shortest(graph,odd[i],odd[j],true).zipWithNext()) {
                val original = graph.at(a).filter { it.other(a) == b }
                    .minBy { when { it.key == "segment" -> 0; it.key == "outline" -> 1; it.key == "extra" -> 2; else -> 3 } }
                graph.add(a, b, "${original.key}-${duplicate++}", original.segment, original.points, original.weight)
            }
        }
    }

    private fun unweightedDistances(graph: Graph,start: Node): Map<Node,Int> {
        val distances = hashMapOf(start to 0)
        val queue = ArrayDeque<Node>(); queue += start
        while (queue.isNotEmpty()) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val node = queue.removeFirst()
            for (edge in graph.at(node)) {
                val neighbor = edge.other(node)
                if (neighbor !in distances) { distances[neighbor] = distances.getValue(node)+1; queue += neighbor }
            }
        }
        return distances
    }

    private fun buildTravelGraph(fill: Graph, polygon: Polygon, outlines: List<LineString>, angle: Double,
        underpath: Boolean): Graph {
        val graph = Graph()
        for ((node, data) in fill.nodes) { graph.node(node.point()); graph.nodes[node] = data.copy() }
        var travelEdges = emptyList<LineString>()
        if (underpath) {
            val scale = if (polygon.area < 10000) 0.5 else 1.0
            fun grating(a: Double, spacing: Double): Geometry = FillGeometry.factory.createMultiLineString(
                Fill.intersectRegionWithGrating(polygon, a, spacing).flatten().map(FillGeometry::line).toTypedArray())
            val g1 = grating(angle+PI/4, scale*2*PIXELS_PER_MM)
            val g2 = grating(angle-PI/4, scale*2*PIXELS_PER_MM)
            val g3 = grating(angle-PI/2, scale*sqrt(2.0)*PIXELS_PER_MM)
            if (!g1.isEmpty && !g2.isEmpty) {
                val diagonal = OverlayNGRobust.overlay(g1, g2, OverlayNG.SYMDIFFERENCE)
                val vertical = GeometrySnapper(OverlayNGRobust.overlay(g3, g1, OverlayNG.DIFFERENCE)).snapTo(diagonal, 0.005)
                travelEdges = FillGeometry.lines(diagonal)+FillGeometry.lines(vertical)
                for (g in listOf(g1, g2, g3)) for (line in FillGeometry.lines(g))
                    line.coordinates.forEach { graph.node(FillPoint(it.x, it.y)) }
                tagNodes(graph, outlines)
            }
        }
        if (travelEdges.isEmpty()) {
            for (outline in outlines) for ((a, b) in outline.coordinates.asList().zipWithNext()) {
                val line = FillGeometry.line(listOf(FillPoint(a.x, a.y), FillPoint(b.x, b.y)))
                val indexed = LengthIndexedLine(line)
                graph.node(FillPoint(a.x, a.y))
                if (line.length > 1) for (i in 1 until line.length.toInt()) {
                    val point = indexed.extractPoint(i.toDouble()); graph.node(FillPoint(point.x, point.y))
                }
                graph.node(FillPoint(b.x, b.y))
                require(graph.nodes.size <= 250_000) { "Fill travel graph exceeds 250000 nodes" }
            }
            tagNodes(graph, outlines)
        }
        outlineEdges(graph)
        if (travelEdges.isNotEmpty()) {
            graph.edges.values.forEach { it.weight = 3*it.a.point().distance(it.b.point()) }
            val weightedOutline = GeometryPrecisionReducer.reduce(
                DouglasPeuckerSimplifier.simplify(polygon.boundary, 0.5*PIXELS_PER_MM), PrecisionModel(1e6))
            val index = STRtree()
            // Index row segments once. Exact robust intersections implement the line/line DE-9IM rule;
            // no full row noding, newer relation classes or coordinate simplification.
            val rows = fill.edges.values.filter { it.key == "segment" }.associateWith { FillGeometry.line(it.points!!) }
            val predicates = rows.mapValues { LegacyCrosses.prepareLine(it.value) }
            for ((edge, geometry) in rows) index.insert(geometry.envelopeInternal, edge)
            index.build()
            for (line in travelEdges) {
                val points = FillGeometry.points(line)
                val a = graph.node(points.first()); val b = graph.node(points.last())
                val edge = graph.add(a, b, "travel", weight = a.point().distance(b.point())/(line.distance(weightedOutline)+0.1))
                for (candidate in index.query(line.envelopeInternal)) {
                    val row = candidate as Edge
                    if (predicates.getValue(row).test(line)) row.underpathEdges += edge.id
                }
            }
        }
        return graph
    }

    private fun shortest(graph: Graph, start: Node, end: Node, unweighted: Boolean = false): List<Node> {
        if (start == end) return listOf(start)
        if (start !in graph.nodes || end !in graph.nodes) return emptyList()
        val distances = hashMapOf(start to 0.0); val previous = hashMapOf<Node, Node>()
        data class Entry(val node: Node, val distance: Double, val order: Int)
        val queue = PriorityQueue(compareBy<Entry> { it.distance }.thenBy { it.order })
        var order = 0
        queue += Entry(start, 0.0, order++)
        while (queue.isNotEmpty()) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val entry = queue.remove()
            if (entry.distance != distances[entry.node]) continue
            if (entry.node == end) break
            for (edge in graph.at(entry.node)) {
                val other = edge.other(entry.node)
                val cost = entry.distance + if (unweighted) 1.0 else edge.weight
                if (cost < (distances[other] ?: Double.POSITIVE_INFINITY)) {
                    distances[other] = cost; previous[other] = entry.node
                    queue += Entry(other, cost, order++)
                }
            }
        }
        if (end !in previous) return emptyList()
        val result = mutableListOf(end)
        while (result.last() != start) result += previous.getValue(result.last())
        return result.reversed()
    }

    private fun collapse(path: List<PathEdge>, graph: Graph): List<PathEdge> {
        var start: Node? = null
        val result = mutableListOf<PathEdge>()
        for (edge in path) {
            if (edge.segment()) {
                if (start != null) result += PathEdge(start, edge.a, Edge(-1, start, edge.a, if (graph.has(start, edge.a)) "outline" else "collapsed"))
                start = null; result += edge
            } else if (start == null) start = edge.a
        }
        if (start != null && path.isNotEmpty() && start != path.last().b)
            result += PathEdge(start, path.last().b, Edge(-1, start, path.last().b, "collapsed"))
        return result
    }

    private fun fillGaps(path: List<PathEdge>, requested: Int): List<PathEdge> {
        require(requested in 0..100)
        if (requested == 0 || path.size < 3) return path
        val count = if (requested%2 == 1) requested+1 else requested
        val noLoops = mutableListOf<PathEdge>()
        val seen = hashMapOf<Node, Int>()
        for (edge in path) {
            if (edge.segment()) { noLoops += edge; seen.clear() }
            else if (edge.b in seen) { noLoops.subList(seen.getValue(edge.b), noLoops.size).clear(); seen.clear() }
            else { noLoops += edge; seen[edge.b] = noLoops.size }
        }
        val result = mutableListOf<PathEdge>()
        var previous: PathEdge? = null; var rows = 0
        for (edge in noLoops) {
            if (previous?.outline() == true && edge.segment()) rows++
            if (previous?.outline() == true && edge.outline()) {
                if (rows > 3 && result.size >= 4) {
                    val next = result.removeAt(result.lastIndex)
                    val last = result.last(); val penultimate = result[result.size-3]
                    val direction = (last.b.point()-last.a.point()).unit()
                    var normal = direction.rotate(PI/2)
                    if ((last.b.point()-penultimate.a.point()).dot(normal) < 0) normal *= -1.0
                    val offset = normal*(last.b.point()-penultimate.a.point()).dot(normal)
                    var a = last.a.point(); var b = last.b.point()
                    fun segment(from: FillPoint, to: FillPoint) {
                        val n1 = Node(from.x, from.y); val n2 = Node(to.x, to.y)
                        result += PathEdge(n1, n2, Edge(-1, n1, n2, "segment", points = listOf(from, to)))
                    }
                    repeat(count) { val start = b+offset; val end = a+offset; segment(b, start); segment(start, end); a = start; b = end }
                    segment(b, last.b.point()); result += next
                }
                rows = 0
            }
            previous = edge; result += edge
        }
        return result
    }

    private fun boundaryPath(outlines: List<LineString>, start: FillPoint, end: FillPoint): List<FillPoint> {
        val ring = outlines.minBy { it.distance(FillGeometry.point(start))+it.distance(FillGeometry.point(end)) }
        val indexed = LengthIndexedLine(ring)
        val a = indexed.project(Coordinate(start.x, start.y)); val b = indexed.project(Coordinate(end.x, end.y))
        val direct = indexed.extractLine(a, b) as LineString
        val wrapped = if (a <= b) FillGeometry.points(indexed.extractLine(a, 0.0) as LineString) +
            FillGeometry.points(indexed.extractLine(ring.length, b) as LineString).drop(1)
        else FillGeometry.points(indexed.extractLine(a, ring.length) as LineString) +
            FillGeometry.points(indexed.extractLine(0.0, b) as LineString).drop(1)
        return if (direct.length <= ring.length-direct.length) FillGeometry.points(direct) else wrapped
    }

    /** Clamp each full exterior excursion once, rather than projecting every outside vertex. */
    internal fun clampPath(path: List<FillPoint>, polygon: Polygon): List<FillPoint> {
        if (path.size < 2) return path
        val outlines = listOf(polygon.exteriorRing) + (0 until polygon.numInteriorRing).map(polygon::getInteriorRingN)
        // Boundary graph nodes are rounded to 1e-6px. A smaller tolerance can erase an
        // entire legitimate boundary leg and leave a long unsewn gap before the next row.
        val inside = polygon.buffer(1e-6)
        val first = if (inside.covers(FillGeometry.point(path.first()))) path.first() else {
            val ring = outlines.minBy { it.distance(FillGeometry.point(path.first())) }
            val indexed = LengthIndexedLine(ring)
            indexed.extractPoint(indexed.project(Coordinate(path.first().x,path.first().y))).let { FillPoint(it.x,it.y) }
        }
        val result = mutableListOf(first)
        for ((a, b) in path.zipWithNext()) {
            if (a.samePosition(b)) continue
            val segment = FillGeometry.line(listOf(a, b))
            val linear = LengthIndexedLine(segment)
            val pieces = if (inside.covers(segment)) listOf(listOf(a,b)) else
                FillGeometry.lines(OverlayNGRobust.overlay(segment, polygon, OverlayNG.INTERSECTION))
                .map { line -> FillGeometry.points(line).let { if ((it.last()-it.first()).dot(b-a) < 0) it.reversed() else it } }
                .sortedBy { linear.project(Coordinate(it.first().x, it.first().y)) }
            for (piece in pieces) {
                if (result.isEmpty()) result += piece.first()
                else if (result.last().distance(piece.first()) > 1e-7)
                    result += boundaryPath(outlines, result.last(), piece.first()).drop(1)
                result += piece.drop(1)
            }
        }
        // An outside terminal excursion may intersect the polygon only at the final
        // point. Overlay drops zero-dimensional intersections; reconnect that anchor
        // along the matching boundary instead of silently truncating the path.
        if (result.last().distance(path.last()) > 1e-7)
            result += boundaryPath(outlines, result.last(), path.last()).drop(1)
        return result
    }
}
