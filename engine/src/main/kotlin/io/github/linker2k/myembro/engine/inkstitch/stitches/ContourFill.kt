// Ported from Ink/Stitch lib/stitches/contour_fill.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.stitches

import org.locationtech.jts.algorithm.Orientation
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryCollection
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.util.GeometryFixer
import org.locationtech.jts.linearref.LengthIndexedLine
import org.locationtech.jts.operation.buffer.BufferOp
import org.locationtech.jts.operation.buffer.BufferParameters
import org.locationtech.jts.operation.distance.DistanceOp
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier
import kotlin.math.max

/** Ink/Stitch SVG attributes; geometry and starting points use SVG pixels (96 dpi). */
data class ContourFillParameters(
    val contour_strategy: Int = 0,
    val join_style: Int = 0,
    val clockwise: Boolean = true,
    val avoid_self_crossing: Boolean = false,
    val row_spacing_mm: Double = 0.25,
    val max_stitch_length_mm: Double = 4.0,
    val running_stitch_tolerance_mm: Double = 0.2,
    val smoothness_mm: Double = 0.0,
    val enable_random_stitch_length: Boolean = false,
    val random_stitch_length_jitter_percent: Double = 10.0,
    val random_seed: String = "",
    val repeats: Int = 1,
    val bean_stitch_repeats: List<Int> = listOf(0),
) {
    init {
        require(contour_strategy in 0..2 && join_style in 0..2)
        require(listOf(row_spacing_mm, max_stitch_length_mm, running_stitch_tolerance_mm).all { it.isFinite() && it > 0 })
        require(smoothness_mm.isFinite() && smoothness_mm >= 0)
        require(random_stitch_length_jitter_percent.isFinite() && random_stitch_length_jitter_percent >= 0)
        require(repeats in 1..16 && bean_stitch_repeats.isNotEmpty() && bean_stitch_repeats.all { it in 0..20 })
    }
}

/**
 * Isocontour tree and recursive/spiral traversal, corresponding to contour_fill.py.
 * JTS negative polygon buffers replace GEOS offset curves; contour ordering can differ.
 * Random stitch lengths are rejected until the shared running-stitch PRNG port exists.
 */
object ContourFill {
    private const val PX_PER_MM = 96.0 / 25.4
    private const val MAX_CONTOURS = 20_000
    private val factory = GeometryFactory()
    private data class Node(var ring: List<FillPoint>, val children: MutableList<Node> = mutableListOf(), var parent: Node? = null)
    private data class Connection(val parentPoint: FillPoint, val childPoint: FillPoint, val distance: Double, val child: Node)

    /** _make_fermat_spiral, shared with circular_fill.py. Rings are outermost first. */
    fun makeFermatSpiral(rings: List<LineString>, stitchLengthPx: Double, startingPoint: FillPoint): List<FillPoint> {
        require(stitchLengthPx.isFinite() && stitchLengthPx > 0)
        val coordinates = rings.map { points(it) }
        return makeSpiral(coordinates.filterIndexed { i, _ -> i % 2 == 0 }, stitchLengthPx, startingPoint) +
            makeSpiral(coordinates.filterIndexed { i, _ -> i % 2 == 1 }, stitchLengthPx, startingPoint).reversed()
    }

    fun fill(polygon: Polygon, parameters: ContourFillParameters = ContourFillParameters(),
        startingPoint: FillPoint = FillPoint(0.0, 0.0)): List<FillPoint> {
        require(!parameters.enable_random_stitch_length) { "Contour random stitch length is not yet ported" }
        if (polygon.isEmpty) return emptyList()
        val spacingPx = max(0.1, parameters.row_spacing_mm) * PX_PER_MM
        val lengthPx = max(0.1, parameters.max_stitch_length_mm) * PX_PER_MM
        val tolerancePx = max(0.01, parameters.running_stitch_tolerance_mm) * PX_PER_MM
        val repaired = if (polygon.isValid) polygon else GeometryFixer.fix(polygon)
        val result = mutableListOf<FillPoint>()
        for (part in polygons(repaired)) {
            val tree = offsetPolygon(part, spacingPx, parameters.join_style + 1, parameters.clockwise)
            val path = when (parameters.contour_strategy) {
                0 -> findPathInnerToOuter(tree, spacingPx, startingPoint, parameters.avoid_self_crossing)
                1 -> makeSpiral(spiralRings(tree), lengthPx, startingPoint)
                else -> {
                    val rings = spiralRings(tree)
                    makeSpiral(rings.filterIndexed { i, _ -> i % 2 == 0 }, lengthPx, startingPoint) +
                        makeSpiral(rings.filterIndexed { i, _ -> i % 2 == 1 }, lengthPx, startingPoint).reversed()
                }
            }
            // inner_to_outer alone smooths and clamps; spiral strategies ignore smoothness upstream.
            val prepared = if (parameters.contour_strategy == 0 && parameters.smoothness_mm > 0 && path.size >= 2)
                FillRouting.clampPath(FillGeometry.smoothPath(path, parameters.smoothness_mm * PX_PER_MM), part)
                else path
            val points = if (prepared.size < 2) emptyList() else running(prepared, lengthPx, tolerancePx)
            var stitched = beanStitch(points, parameters.bean_stitch_repeats)
            // Preserve v3.3.0's list-extension behavior, including its accumulated repeats.
            for (i in 1 until parameters.repeats) stitched = stitched + if (i % 2 == 1) stitched.reversed() else stitched
            if (result.isNotEmpty() && stitched.isNotEmpty()) result += stitched.first().copy(tags = stitched.first().tags + "jump")
            result += stitched
        }
        return result
    }

    /** Raw interpolation formula from _interpolate_linear_rings; useful for parity fixtures. */
    internal fun interpolateLinearRings(ring1: List<FillPoint>, ring2: List<FillPoint>,
        maxStitchLengthPx: Double, start: FillPoint? = null): List<FillPoint> {
        require(maxStitchLengthPx.isFinite() && maxStitchLengthPx > 0)
        val count = (20 * length(ring1) / maxStitchLengthPx).toInt()
        require(count <= 1_000_000) { "Too many contour interpolation points" }
        if (count <= 1) return emptyList()
        var first = resample(ring1, count)
        var second = resample(ring2, count)
        if (start != null) {
            first = reorder(first, start)
            second = reorder(second, start)
        }
        val coordinates = first.indices.map { i -> mix(first[i], second[i], i.toDouble() / (count - 1)) }
        return points(DouglasPeuckerSimplifier.simplify(line(coordinates), 0.1))
    }

    /** Preorder contours, used by tests and by clients inspecting the offset construction. */
    internal fun offsetContours(polygon: Polygon, offsetPx: Double, joinStyle: Int = 1,
        clockwise: Boolean = true): List<List<FillPoint>> {
        require(offsetPx.isFinite() && offsetPx > 0)
        fun visit(node: Node): List<List<FillPoint>> = listOf(node.ring) + node.children.flatMap(::visit)
        return if (polygon.isEmpty) emptyList() else visit(offsetPolygon(polygon, offsetPx, joinStyle, clockwise))
    }

    private fun offsetPolygon(polygon: Polygon, offset: Double, joinStyle: Int, clockwise: Boolean): Node {
        val root = Node(points(polygon.exteriorRing))
        val holes = (0 until polygon.numInteriorRing).map { Node(points(polygon.getInteriorRingN(it))) }
        val active = ArrayDeque<Pair<Node, List<Node>>>()
        active.add(root to holes)
        var created = 1 + holes.size
        while (active.isNotEmpty()) {
            val (parent, previousHoles) = active.removeLast()
            val outer = buffer(ringPolygon(parent.ring), -offset, joinStyle)
            var inset = outer
            for (hole in previousHoles) inset = inset.difference(buffer(ringPolygon(hole.ring), offset, joinStyle))
            for (part in polygons(inset)) {
                if (part.area < 0.1) continue
                val simplified = DouglasPeuckerSimplifier.simplify(part.exteriorRing, 0.01)
                val ring = points(simplified)
                if (ring.size < 4) continue
                val child = Node(ring, parent = parent)
                parent.children += child
                val newHoles = (0 until part.numInteriorRing).map { i ->
                    val hole = Node(points(part.getInteriorRingN(i)))
                    val holeShape = ringPolygon(hole.ring)
                    for (previous in previousHoles) if (holeShape.contains(ringPolygon(previous.ring))) {
                        previous.parent = hole
                        hole.children += previous
                    }
                    hole
                }
                created += 1 + newHoles.size
                require(created <= MAX_CONTOURS) { "Contour resource limit exceeded" }
                active.add(child to newHoles)
            }
            for (previous in previousHoles) if (previous.parent == null) {
                previous.parent = parent
                parent.children += previous
            }
        }
        val pending = ArrayDeque<Node>().apply { add(root) }
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            // SVG's y axis is down: mathematical CCW is visually clockwise.
            if (Orientation.isCCW(line(node.ring).coordinates) != clockwise) node.ring = node.ring.reversed()
            node.children.forEach(pending::add)
        }
        return root
    }

    private fun findPathInnerToOuter(node: Node, offset: Double, startingPoint: FillPoint,
        avoidSelfCrossing: Boolean, forward: Boolean = true, depth: Int = 0): List<FillPoint> {
        require(depth <= 512) { "Contour nesting resource limit exceeded" }
        var ring = if (!forward && avoidSelfCrossing) node.ring.reversed() else node.ring
        ring = roll(ring, LengthIndexedLine(line(ring)).project(coordinate(startingPoint)))
        node.ring = ring
        val connections = node.children.mapNotNull { child ->
            nearestConnection(ring, child, 1.5 * offset) ?: nearestConnection(ring, child, 2.05 * offset)
        }.sortedBy { it.distance }
        val result = mutableListOf<FillPoint>()
        if (connections.isEmpty()) {
            if (!avoidSelfCrossing) ring = ring.reversed()
        } else {
            result += ring.first()
            var distanceSoFar = 0.0
            for (connection in connections) {
                if (ring.isEmpty()) break
                val (before, after) = cut(ring, connection.distance - distanceSoFar)
                distanceSoFar = connection.distance
                result += before
                result += findPathInnerToOuter(connection.child, offset, connection.childPoint, avoidSelfCrossing, !forward, depth + 1)
                ring = if (after.isNotEmpty()) {
                    distanceSoFar += offset
                    cut(after, offset).second
                } else emptyList()
            }
        }
        if (ring.isNotEmpty()) {
            val remaining = length(ring)
            if (remaining > offset) ring = cut(ring, remaining - offset).first
            result += ring
        }
        return result
    }

    private fun nearestConnection(parent: List<FillPoint>, child: Node, threshold: Double): Connection? {
        val parentLine = line(parent)
        val childLine = line(child.ring)
        val intersection = parentLine.intersection(childLine.buffer(threshold))
        if (intersection.isEmpty) return null
        // GEOS returns pieces in input order; JTS ordering is normalized explicitly by projection.
        val indexed = LengthIndexedLine(parentLine)
        val parentPoint = intersection.coordinates.minByOrNull { indexed.project(it) } ?: return null
        val nearest = DistanceOp.nearestPoints(factory.createPoint(parentPoint), childLine)
        return Connection(point(nearest[0]), point(nearest[1]), indexed.project(nearest[0]), child)
    }

    private fun spiralRings(root: Node): List<List<FillPoint>> {
        val rings = mutableListOf<List<FillPoint>>()
        var node = root
        while (true) {
            rings += node.ring
            if (node.children.isEmpty()) return rings
            val center = line(node.ring).centroid
            // Same fallback as _get_spiral_rings: branch with center nearest parent's center.
            node = node.children.minBy { center.distance(line(it.ring).centroid) }
        }
    }

    private fun makeSpiral(rings: List<List<FillPoint>>, stitchLengthPx: Double, start: FillPoint): List<FillPoint> {
        val path = mutableListOf<FillPoint>()
        var part = emptyList<FillPoint>()
        for ((first, second) in rings.zipWithNext()) {
            part = interpolateLinearRings(first, second, stitchLengthPx, start)
            path += part.dropLast(1)
        }
        if (part.isNotEmpty()) path += part.last()
        return path
    }

    private fun beanStitch(points: List<FillPoint>, repeats: List<Int>): List<FillPoint> {
        if (points.size < 2 || repeats.all { it == 0 }) return points
        val result = mutableListOf(points.first())
        for ((i, pair) in points.zipWithNext().withIndex()) {
            result += pair.second
            repeat(repeats[i % repeats.size]) { result += pair.first; result += pair.second }
        }
        return result
    }

    private fun resample(path: List<FillPoint>, count: Int): List<FillPoint> {
        if (path.size < 2) return List(count) { path.first() }
        val indexed = LengthIndexedLine(line(path))
        val total = indexed.endIndex
        return List(count) { point(indexed.extractPoint(it.toDouble() / (count - 1) * total)) }
    }

    private fun reorder(points: List<FillPoint>, start: FillPoint): List<FillPoint> {
        val index = points.indices.minBy { distance(points[it], start) }
        return points.drop(index) + points.take(index)
    }
    private fun roll(ring: List<FillPoint>, distance: Double): List<FillPoint> {
        val (before, after) = cut(ring, distance)
        return if (before.isEmpty() || after.isEmpty()) ring else after + before.drop(1)
    }
    private fun cut(path: List<FillPoint>, distance: Double): Pair<List<FillPoint>, List<FillPoint>> {
        if (distance <= 0) return emptyList<FillPoint>() to path
        if (distance >= length(path)) return path to emptyList()
        var travelled = 0.0
        for (i in 1 until path.size) {
            val segment = distance(path[i - 1], path[i])
            if (travelled + segment >= distance) {
                val p = mix(path[i - 1], path[i], (distance - travelled) / segment)
                return (path.take(i) + p) to (listOf(p) + path.drop(i))
            }
            travelled += segment
        }
        return path to emptyList()
    }
    private fun buffer(polygon: Polygon, distance: Double, joinStyle: Int): Geometry =
        BufferOp.bufferOp(polygon, distance, BufferParameters(5, BufferParameters.CAP_ROUND, joinStyle, 10.0))
    private fun polygons(g: Geometry): List<Polygon> = when {
        g.isEmpty -> emptyList()
        g is Polygon -> listOf(g)
        g is GeometryCollection -> (0 until g.numGeometries).flatMap { polygons(g.getGeometryN(it)) }
        else -> emptyList()
    }
    private fun ringPolygon(ring: List<FillPoint>) = factory.createPolygon(line(ring).coordinates)
    private fun line(points: List<FillPoint>) = factory.createLineString(points.map(::coordinate).toTypedArray())
    private fun coordinate(p: FillPoint) = Coordinate(p.x, p.y)
    private fun point(c: Coordinate) = FillPoint(c.x, c.y)
    private fun points(g: Geometry) = g.coordinates.map(::point)
    private fun distance(a: FillPoint, b: FillPoint) = kotlin.math.hypot(a.x - b.x, a.y - b.y)
    private fun length(path: List<FillPoint>) = path.zipWithNext().sumOf { (a, b) -> distance(a, b) }
    private fun mix(a: FillPoint, b: FillPoint, t: Double) = FillPoint(a.x * (1 - t) + b.x * t, a.y * (1 - t) + b.y * t)

    private fun running(path: List<FillPoint>, stitchLengthPx: Double, tolerancePx: Double): List<FillPoint> =
        FillRunning.run(path, stitchLengthPx, tolerancePx)
}
