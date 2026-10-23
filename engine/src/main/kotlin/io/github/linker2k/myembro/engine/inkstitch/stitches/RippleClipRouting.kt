// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/stitches/ripple_stitch.py and lib/stitches/tatami_fill.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.stitches

import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.Stitch
import io.github.linker2k.myembro.engine.inkstitch.utils.Point
import org.locationtech.jts.geom.*
import org.locationtech.jts.linearref.LengthIndexedLine
import org.locationtech.jts.operation.overlayng.OverlayNG
import org.locationtech.jts.operation.overlayng.OverlayNGRobust
import java.util.ArrayDeque
import java.util.PriorityQueue
import java.util.concurrent.CancellationException
import kotlin.math.ceil

/** Ripple-specific outline-only travel graph. No straight connector replaces a reachable outline route. */
object RippleClipRouting : RippleClipRouter {
    private val factory = GeometryFactory()
    private const val LIMIT = 1_000_000

    override fun route(stitches: List<Stitch>, clip: Geometry, parameters: RippleParameters,
                       removeStartTravel: Boolean, removeEndTravel: Boolean, startingPoint: Point?): List<List<Stitch>> {
        if (stitches.isEmpty()) return emptyList()
        if (stitches.size < 2) return listOf(stitches)
        val lines = lines(OverlayNGRobust.overlay(line(stitches.map { it.point }), clip, OverlayNG.INTERSECTION))
        // Upstream explicitly retains the original stitches for an empty line intersection.
        if (lines.isEmpty()) return listOf(stitches)
        val segments = prepareSegments(clip, lines)
        if (segments.isEmpty()) return listOf(stitches)
        val start = startingPoint ?: segments.first().first()
        val end = segments.last().last()
        val outlines = lines(clip.boundary)
        require(outlines.isNotEmpty()) { "Ripple clip has no polygon boundary" }
        val fill = Graph()
        segments.forEach { segment ->
            fill.add(fill.node(segment.first()), fill.node(segment.last()), "segment", segment)
        }
        tagNodes(fill, outlines)
        outlineEdges(fill)
        insertNode(fill, outlines, start)
        insertNode(fill, outlines, end)
        if (fill.edges().isEmpty()) return listOf(stitches)
        val routed = fill.components().map { component ->
            eulerize(component)
            val travel = travelGraph(component, outlines)
            val path = collapse(findPath(component, travel, start, end), component)
            render(path, component, travel, clip, parameters)
        }.filter { it.isNotEmpty() }.toMutableList()
        if (routed.isEmpty()) return listOf(stitches)
        if (routed.size > 1) {
            routed.indexOfFirst { it.first().point == start }.takeIf { it >= 0 }?.let { routed.add(0, routed.removeAt(it)) }
            routed.indexOfFirst { it.last().point == end }.takeIf { it >= 0 }?.let { routed.add(routed.removeAt(it)) }
        }
        return routed.mapNotNull { group ->
            var current = group
            if (removeStartTravel) current.indexOfFirst { "travel" !in it.tags }.takeIf { it >= 0 }?.let { current = current.drop(it) }
            if (removeEndTravel) current.indexOfLast { "travel" !in it.tags }.takeIf { it >= 0 }?.let { current = current.take(it + 1) }
            current.takeIf { it.isNotEmpty() }
        }
    }

    private fun prepareSegments(clip: Geometry, lines: List<LineString>): List<List<Point>> {
        val result = mutableListOf<List<Point>>()
        var current = mutableListOf<Point>()
        for (line in lines) {
            interrupted()
            var points = line.coordinates.map { it.point() }
            if (clip.boundary.distance(point(points.first())) < 0.0001 && current.isNotEmpty()) {
                result += current; current = mutableListOf()
            }
            // Intersection nodes are not new needle penetrations: rejoin the original continuous path.
            if (current.size > 1) { current.removeAt(current.lastIndex); points = points.drop(1) }
            current.addAll(points)
        }
        if (current.isNotEmpty()) result += current
        require(result.all { it.size >= 2 }) { "Clipped ripple has a degenerate line segment" }
        return result
    }

    private data class Node(val x: Double, val y: Double) { val point get() = Point(x, y) }
    private data class NodeData(var outline: Int = 0, var projection: Double = 0.0)
    private data class Edge(val a: Node, val b: Node, val key: String, val geometry: List<Point>? = null,
                            val outline: Int? = null, val index: Int? = null, val weight: Double = 1.0) {
        fun other(node: Node) = if (node == a) b else a
    }
    private data class PathEdge(val a: Node, val b: Node, val key: String) {
        val segment get() = key.startsWith("segment")
        val outline get() = listOf("outline", "extra", "initial").any { key.startsWith(it) }
    }
    /** Mirrors MultiGraph's ordered node/neighbor/key dictionaries, including self-loop degree=2. */
    private class Graph {
        val nodes = linkedMapOf<Node, NodeData>()
        private val adjacency = linkedMapOf<Node, LinkedHashMap<Node, LinkedHashMap<String, Edge>>>()
        private var count = 0
        fun node(p: Point): Node {
            val node = Node(if (p.x == 0.0) 0.0 else p.x, if (p.y == 0.0) 0.0 else p.y)
            if (node !in nodes) { nodes[node] = NodeData(); adjacency[node] = linkedMapOf() }
            require(nodes.size <= LIMIT) { "Ripple graph node limit exceeded" }
            return node
        }
        fun add(a: Node, b: Node, key: String, geometry: List<Point>? = null,
                outline: Int? = null, index: Int? = null, weight: Double = 1.0): Edge {
            node(a.point); node(b.point)
            val edge = Edge(a, b, key, geometry, outline, index, weight)
            val ab = adjacency.getValue(a).getOrPut(b) { linkedMapOf() }
            if (key !in ab) count++
            ab[key] = edge
            adjacency.getValue(b).getOrPut(a) { linkedMapOf() }[key] = edge
            require(count <= LIMIT) { "Ripple graph edge limit exceeded" }
            return edge
        }
        fun at(node: Node) = adjacency[node]?.values?.flatMap { it.values } ?: emptyList()
        fun between(a: Node, b: Node) = adjacency[a]?.get(b).orEmpty()
        fun has(a: Node, b: Node) = adjacency[a]?.containsKey(b) == true
        fun degree(node: Node): Int = at(node).fold(0) { sum, edge -> sum + if (edge.a == edge.b) 2 else 1 }
        fun edges(): List<Edge> {
            val seen = hashSetOf<Node>()
            return buildList {
                for (node in nodes.keys) {
                    adjacency.getValue(node).forEach { (neighbor, edges) ->
                        if (neighbor !in seen) addAll(edges.values.map { it.copy(a = node, b = neighbor) })
                    }
                    seen += node
                }
            }
        }
        fun remove(edge: Edge) {
            if (adjacency[edge.a]?.get(edge.b)?.remove(edge.key) != null) count--
            if (edge.a != edge.b) adjacency[edge.b]?.get(edge.a)?.remove(edge.key)
            for ((a, b) in listOf(edge.a to edge.b, edge.b to edge.a)) {
                if (adjacency[a]?.get(b)?.isEmpty() == true) adjacency[a]?.remove(b)
            }
        }
        fun copy(only: Set<Node> = nodes.keys): Graph {
            val graph = Graph()
            nodes.filterKeys { it in only }.forEach { (node, data) -> graph.node(node.point); graph.nodes[node] = data.copy() }
            edges().filter { it.a in only && it.b in only }.forEach { graph.add(it.a, it.b, it.key, it.geometry, it.outline, it.index, it.weight) }
            return graph
        }
        fun components(): List<Graph> {
            val seen = hashSetOf<Node>()
            return nodes.keys.mapNotNull { start ->
                if (start in seen) return@mapNotNull null
                val component = linkedSetOf(start); val queue = ArrayDeque<Node>(); queue += start; seen += start
                while (queue.isNotEmpty()) {
                    interrupted()
                    val current = queue.removeFirst()
                    for (edge in at(current)) { val next = edge.other(current); if (seen.add(next)) { component += next; queue += next } }
                }
                copy(component)
            }
        }
    }

    private fun tagNodes(graph: Graph, outlines: List<LineString>, nodes: Collection<Node> = graph.nodes.keys) {
        for (node in nodes) {
            interrupted()
            val index = outlines.indices.minBy { outlines[it].distance(point(node.point)) }
            graph.nodes.getValue(node).apply {
                outline = index; projection = LengthIndexedLine(outlines[index]).project(Coordinate(node.x, node.y))
            }
        }
    }
    private fun outlineEdges(graph: Graph) {
        val sorted = graph.nodes.keys.sortedWith(compareBy<Node> { graph.nodes.getValue(it).outline }
            .thenBy { graph.nodes.getValue(it).projection })
        for ((outline, nodes) in sorted.groupBy { graph.nodes.getValue(it).outline }) {
            for (i in nodes.indices) {
                val a = nodes[i]; val b = nodes[(i + 1) % nodes.size]
                graph.add(a, b, "outline", outline = outline, index = i)
                // Upstream's duplicate_every_other argument is unused: extras are also in the travel graph.
                if (i % 2 == 0) graph.add(a, b, "extra", outline = outline, index = i)
            }
        }
    }
    private fun insertNode(graph: Graph, outlines: List<LineString>, reference: Point) {
        val index = outlines.indices.minBy { outlines[it].distance(point(reference)) }
        val indexed = LengthIndexedLine(outlines[index])
        val coordinate = indexed.extractPoint(indexed.project(Coordinate(reference.x, reference.y)))
        val node = graph.node(coordinate.point())
        var edges = graph.edges().filter { it.key == "outline" && it.outline == index }
        if (edges.isNotEmpty()) {
            val edge = edges.minBy { line(listOf(it.a.point, it.b.point)).distance(point(node.point)) }
            graph.remove(edge)
            graph.add(edge.a, node, "outline", outline = edge.outline, index = edge.index)
            graph.add(node, edge.b, "outline", outline = edge.outline, index = edge.index)
        } else {
            edges = graph.edges().filter { it.key == "outline" }
            require(edges.isNotEmpty()) { "Ripple clip has no outline edge for its endpoint" }
            val edge = edges.minBy { line(listOf(it.a.point, it.b.point)).distance(point(node.point)) }
            val distance = edge.a.point.distance(node.point)
            val count = if (distance > 10) ceil(distance / 10).toInt() else 1
            val segment = (0..count).map { edge.a.point + (node.point - edge.a.point) * (it.toDouble() / count) }
            // Preserve upstream insert_node's two segment geometries, including their endpoint ordering.
            graph.add(edge.a, node, "segment", segment)
            graph.add(node, edge.b, "segment", segment.reversed())
        }
        tagNodes(graph, outlines, listOf(node))
    }

    private fun eulerize(graph: Graph) {
        val odd = graph.nodes.keys.filter { graph.degree(it) % 2 != 0 }
        if (odd.isEmpty()) return
        require(odd.size <= 60) { "Ripple Euler matching exceeds 60 odd vertices" }
        val paths = Array(odd.size) { i -> Array(odd.size) { j -> if (i == j) listOf(odd[i]) else shortest(graph, odd[i], odd[j], true) } }
        require(paths.all { row -> row.all { it.isNotEmpty() } }) { "Disconnected ripple matching graph" }
        val memo = hashMapOf(0L to 0); val choices = hashMapOf<Long, Int>()
        fun match(mask: Long): Int {
            memo[mask]?.let { return it }
            interrupted()
            require(memo.size < LIMIT) { "Ripple exact Euler matching search limit exceeded" }
            val i = java.lang.Long.numberOfTrailingZeros(mask)
            var best = Int.MAX_VALUE
            for (j in i + 1 until odd.size) if (mask and (1L shl j) != 0L) {
                val score = paths[i][j].size + match(mask xor (1L shl i) xor (1L shl j))
                if (score < best) { best = score; choices[mask] = j }
            }
            memo[mask] = best; return best
        }
        var mask = (1L shl odd.size) - 1
        match(mask)
        while (mask != 0L) {
            val i = java.lang.Long.numberOfTrailingZeros(mask); val j = choices.getValue(mask)
            for ((a, b) in paths[i][j].zipWithNext()) {
                val originals = graph.between(a, b)
                val original = listOf("segment", "outline", "extra", "initial").firstNotNullOfOrNull { originals[it] }
                    ?: error("No source edge for ripple Euler matching")
                val key = originals.size
                graph.add(a, b, "${original.key}-$key", original.geometry, original.outline, original.index, original.weight)
            }
            mask = mask xor (1L shl i) xor (1L shl j)
        }
    }
    private fun travelGraph(fill: Graph, outlines: List<LineString>): Graph {
        val graph = Graph()
        fill.nodes.forEach { (node, data) -> graph.node(node.point); graph.nodes[node] = data.copy() }
        for ((index, outline) in outlines.withIndex()) {
            var previous: Coordinate? = null
            val indexedOutline = LengthIndexedLine(outline)
            for (coordinate in outline.coordinates) {
                interrupted()
                previous?.let { a ->
                    val segment = line(listOf(a.point(), coordinate.point()))
                    if (segment.length > 1) for (i in 1 until segment.length.toInt()) {
                        val p = LengthIndexedLine(segment).extractPoint(i.toDouble()).point()
                        val node = graph.node(p)
                        graph.nodes[node] = NodeData(index, indexedOutline.project(Coordinate(p.x, p.y)))
                    }
                }
                val node = graph.node(coordinate.point())
                graph.nodes[node] = NodeData(index, indexedOutline.project(coordinate))
                previous = coordinate
            }
        }
        outlineEdges(graph)
        return graph
    }
    private fun findPath(fill: Graph, travel: Graph, start: Point, end: Point): List<PathEdge> {
        val graph = fill.copy()
        val starting = graph.nodes.keys.minBy { it.point.distance(start) }
        val ending = graph.nodes.keys.minBy { it.point.distance(end) }
        val stack = mutableListOf(ending to null as String?)
        var previous: Node? = null; var key: String? = null
        val path = mutableListOf<PathEdge>()
        while (stack.isNotEmpty()) {
            interrupted()
            val (node, incoming) = stack.last()
            val edges = graph.at(node)
            if (edges.isEmpty()) {
                if (previous != null) path += PathEdge(previous, node, key!!)
                previous = node; key = incoming; stack.removeAt(stack.lastIndex)
            } else {
                val edge = edges.firstOrNull { it.key == "segment" } ?: edges.first()
                stack += edge.other(node) to edge.key; graph.remove(edge)
            }
        }
        check(graph.edges().isEmpty()) { "Disconnected ripple Euler circuit" }
        // Ripple calls find_stitch_path(..., underpath=True), although its travel graph is outline-only.
        if (starting != ending) path.add(0, PathEdge(starting, ending, "initial"))
        val realStart = travel.nodes.keys.minBy { it.point.distance(start) }
        val realEnd = travel.nodes.keys.minBy { it.point.distance(end) }
        path.add(0, PathEdge(realStart, starting, "outline"))
        path += PathEdge(ending, realEnd, "outline")
        return path
    }
    private fun collapse(path: List<PathEdge>, graph: Graph): List<PathEdge> {
        var start: Node? = null
        val result = mutableListOf<PathEdge>()
        for (edge in path) {
            if (edge.segment) {
                if (start != null) result += PathEdge(start, edge.a, if (graph.has(start, edge.a)) "outline" else "collapsed")
                start = null; result += edge
            } else if (start == null) start = edge.a
        }
        if (start != null && path.isNotEmpty() && start != path.last().b) result += PathEdge(start, path.last().b, "collapsed")
        return result
    }
    private fun render(path: List<PathEdge>, fill: Graph, travel: Graph, clip: Geometry, p: RippleParameters): List<Stitch> {
        if (path.isEmpty()) return emptyList()
        val result = mutableListOf<Stitch>()
        if (!path.first().segment) result += Stitch(path.first().a.point, setOf("travel"))
        for (edge in path) {
            interrupted()
            if (edge.segment) {
                // Source resolves all segment-* duplicates against the original 'segment' geometry.
                val geometry = fill.between(edge.a, edge.b)["segment"]?.geometry
                    ?: error("Missing original ripple segment geometry")
                val points = if (geometry.first() == edge.a.point) geometry else geometry.reversed()
                result += points.map(::Stitch)
            } else {
                val route = shortest(travel, edge.a, edge.b)
                // Original NetworkXNoPath fallback, never used as an approximation for a reachable route.
                val nodes = if (route.isEmpty()) listOf(edge.a, edge.b) else route
                val boundaryOnly = nodes.filter { clip.boundary.distance(point(it.point)) < 1 }.map { it.point }
                if (boundaryOnly.isNotEmpty()) result += RunningStitch.evenRunningStitch(boundaryOnly, p.lengthsPx,
                    p.tolerancePx).drop(1).map { Stitch(it, setOf("travel")) }
            }
            require(result.size <= LIMIT) { "Clipped ripple exceeds stitch limit" }
        }
        return result
    }
    private fun shortest(graph: Graph, start: Node, end: Node, unweighted: Boolean = false): List<Node> {
        if (start == end) return listOf(start)
        if (start !in graph.nodes || end !in graph.nodes) return emptyList()
        val distances = hashMapOf(start to 0.0); val previous = hashMapOf<Node, Node>()
        data class Step(val node: Node, val distance: Double, val order: Int)
        val queue = PriorityQueue(compareBy<Step> { it.distance }.thenBy { it.order })
        var order = 0; queue += Step(start, 0.0, order++)
        while (queue.isNotEmpty()) {
            interrupted()
            val step = queue.remove()
            if (step.distance != distances[step.node]) continue
            if (step.node == end) break
            for (edge in graph.at(step.node)) {
                val next = edge.other(step.node)
                val distance = step.distance + if (unweighted) 1.0 else edge.weight
                if (distance < (distances[next] ?: Double.POSITIVE_INFINITY)) {
                    distances[next] = distance; previous[next] = step.node; queue += Step(next, distance, order++)
                }
            }
        }
        if (end !in previous) return emptyList()
        val result = mutableListOf(end)
        while (result.last() != start) result += previous.getValue(result.last())
        return result.reversed()
    }
    private fun point(p: Point) = factory.createPoint(Coordinate(p.x, p.y))
    private fun line(points: List<Point>) = factory.createLineString(points.map { Coordinate(it.x, it.y) }.toTypedArray())
    private fun Coordinate.point() = Point(x, y)
    private fun lines(geometry: Geometry): List<LineString> = when (geometry) {
        is LineString -> if (geometry.isEmpty) emptyList() else listOf(geometry)
        is GeometryCollection -> (0 until geometry.numGeometries).flatMap { lines(geometry.getGeometryN(it)) }
        else -> emptyList()
    }
    private fun interrupted() { if (Thread.currentThread().isInterrupted) throw CancellationException("Ripple clip routing interrupted") }
}
