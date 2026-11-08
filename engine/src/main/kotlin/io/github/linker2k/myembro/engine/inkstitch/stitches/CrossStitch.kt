// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/stitches/cross_stitch.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
// Ported from Ink/Stitch lib/stitches/utils/cross_stitch.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
// Ported from networkx 3.5 algorithms/shortest_paths/unweighted.py (bidirectional_shortest_path, BSD-3-Clause)
package io.github.linker2k.myembro.engine.inkstitch.stitches

import io.github.linker2k.myembro.engine.inkstitch.svg.PIXELS_PER_MM
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.prep.PreparedGeometryFactory
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * Even cross stitch fill (cross, upright, dense upright, double, smyrna and their flipped variants).
 *
 * Upstream iterates crosses through a Python `set` of objects (id-hash order) and builds subgraph
 * adjacency through set-ordered views; both are replaced by insertion order here. The oracle
 * (tools/cross-stitch/oracle.py) applies the same ordering to the unchanged upstream code.
 * Not ported: half crosses (tatami based) and grid rotation (needs inkscape:transform-center).
 */
object CrossStitch {
    data class Settings(
        val method: String = "simple cross",
        val patternSizePx: Pair<Double, Double> = Pair(3.0 * PIXELS_PER_MM, 3.0 * PIXELS_PER_MM),
        val coverage: Int = 50,
        val offsetPx: Pair<Double, Double> = Pair(0.0, 0.0),
        val canvasGridOrigin: Boolean = true,
        val threadCount: Int = 4,
        val maxStitchLengthPx: Double = 11.0 * PIXELS_PER_MM,
        val rotation: Double = 0.0,
    ) {
        val supported: Boolean get() = !method.startsWith("half") && rotation == 0.0

        companion object {
            /** fill_stitch.py parameter properties: get_param treats blank as missing. */
            fun fromAttributes(attributes: Map<String, String>): Settings {
                fun raw(name: String) = attributes[name]?.trim()?.takeIf { it.isNotEmpty() }
                fun split(name: String, default: Pair<Double, Double>): Pair<Double, Double> {
                    val parts = raw(name)?.split(Regex("\\s+")) ?: return default
                    val values = parts.take(2).map { it.toDoubleOrNull() ?: return default }
                    return if (values.size == 1) Pair(values[0], values[0]) else Pair(values[0], values[1])
                }
                fun int(name: String, default: Int) = raw(name)?.toIntOrNull() ?: default
                fun float(name: String, default: Double) = raw(name)?.toDoubleOrNull() ?: default
                val size = split("pattern_size_mm", Pair(3.0, 3.0))
                val offset = split("cross_offset_mm", Pair(0.0, 0.0))
                val origin = raw("canvas_grid_origin")?.lowercase()?.let { it in setOf("yes", "y", "true", "t", "1") } ?: true
                return Settings(
                    method = raw("cross_stitch_method") ?: "simple cross",
                    patternSizePx = Pair(max(size.first * PIXELS_PER_MM, 0.1 * PIXELS_PER_MM), max(size.second * PIXELS_PER_MM, 0.1 * PIXELS_PER_MM)),
                    coverage = max(1, int("fill_coverage", 50)),
                    offsetPx = Pair(max(offset.first * PIXELS_PER_MM, 0.0), max(offset.second * PIXELS_PER_MM, 0.0)),
                    canvasGridOrigin = origin,
                    threadCount = int("cross_thread_count", 4),
                    maxStitchLengthPx = max(float("max_cross_stitch_length_mm", 11.0) * PIXELS_PER_MM, 0.1 * PIXELS_PER_MM),
                    rotation = float("cross_rotation", 0.0),
                )
            }
        }
    }

    /** Grid node; `+ 0.0` folds -0.0 so equality matches Python float tuples. */
    class P(x: Double, y: Double) {
        val x = x + 0.0
        val y = y + 0.0
        override fun equals(other: Any?) = other is P && other.x == x && other.y == y
        override fun hashCode() = 31 * x.hashCode() + y.hashCode()
        override fun toString() = "($x, $y)"
    }

    /** cross_stitch(): returns cycles (one StitchGroup each) in SVG pixels. */
    fun generate(shape: Geometry, settings: Settings, startingPoint: FillPoint?, endingPoint: FillPoint?): List<List<FillPoint>> {
        require(settings.supported) { "Unsupported cross stitch: ${settings.method} rotation=${settings.rotation}" }
        var threadCount = abs(settings.threadCount)
        var start = startingPoint
        var end = endingPoint
        if (start == null) { start = end; end = null }
        if (threadCount % 2 != 0) threadCount -= 1
        return evenCrossStitch(shape, settings, start, end, threadCount)
    }

    private fun evenCrossStitch(input: Geometry, s: Settings, startingPoint: FillPoint?, endingPoint: FillPoint?, threadCount: Int): List<List<FillPoint>> {
        val flipped = "flip" in s.method
        var shape = input
        var start = startingPoint?.let { P(it.x, it.y) }
        var end = endingPoint?.let { P(it.x, it.y) }
        if (flipped) {
            start = start?.let { rotateCoords(it) }
            end = end?.let { rotateCoords(it) }
            shape = input.copy().also { g ->
                g.apply(org.locationtech.jts.geom.CoordinateFilter { c -> val x = c.x; c.x = -c.y + 0.0; c.y = x + 0.0 })
                g.geometryChanged()
            }
        }
        val geoms = CrossGeometries(shape, s.patternSizePx, s.coverage, s.method, s.offsetPx, s.canvasGridOrigin, threadCount)
        val subgraphs = buildConnectSubgraphs(geoms)
        val cycles = buildEulerianCycles(subgraphs, start, end, geoms)
        val stitches = cyclesToStitches(cycles, s.maxStitchLengthPx, flipped)
        return stitches
    }

    private fun rotateCoords(p: P) = P(-p.y, p.x)
    private fun unrotateCoords(p: P) = P(p.y, -p.x)

    /** shapely nearest_points(MultiPoint, Point)[0]: the first point at minimum distance. */
    private fun nearest(points: Iterable<P>, target: P): P {
        var best: P? = null
        var bestDistance = Double.POSITIVE_INFINITY
        for (p in points) {
            val d = Math.hypot(p.x - target.x, p.y - target.y)
            if (d < bestDistance) { bestDistance = d; best = p }
        }
        return checkNotNull(best)
    }

    private fun getCorner(point: P, subcrosses: Iterable<Cross>) = nearest(subcrosses.flatMap { it.allConnectionPoints }, point)

    /** Insertion-ordered undirected graph; only membership and adjacency order are used upstream. */
    internal class Graph(val adj: LinkedHashMap<P, LinkedHashSet<P>> = LinkedHashMap()) {
        val nodes: Set<P> get() = adj.keys
        fun addNode(p: P) { adj.getOrPut(p) { LinkedHashSet() } }
        fun addEdge(a: P, b: P) { addNode(a); addNode(b); adj.getValue(a).add(b); adj.getValue(b).add(a) }
    }

    private fun buildConnectSubgraphs(geoms: CrossGeometries): MutableList<Graph> {
        val g = Graph()
        for (cross in geoms.crosses) {
            g.addNode(cross.centerPoint)
            for (point in cross.allConnectionPoints) g.addNode(point)
            for (point in cross.allConnectionPoints) g.addEdge(cross.centerPoint, point)
        }
        // nx.connected_components: components in order of their first node.
        val seen = HashSet<P>()
        val out = mutableListOf<Graph>()
        for (v in g.nodes) {
            if (v in seen) continue
            val component = HashSet<P>().apply { add(v) }
            var level = listOf(v)
            while (level.isNotEmpty()) {
                val next = mutableListOf<P>()
                for (u in level) for (w in g.adj.getValue(u)) if (component.add(w)) next += w
                level = next
            }
            seen += component
            val sub = LinkedHashMap<P, LinkedHashSet<P>>()
            for ((node, neighbours) in g.adj) if (node in component) sub[node] = neighbours
            out += Graph(sub)
        }
        return out
    }

    private fun buildEulerianCycles(subgraphs: MutableList<Graph>, startingPoint: P?, endingPoint: P?, geoms: CrossGeometries): List<List<P>> {
        val cycles = mutableListOf<List<P>>()
        val organized = organize(subgraphs, geoms, startingPoint, endingPoint)
        var travel = organized.first
        val start = organized.second
        val end = organized.third
        for ((i, subgraph) in subgraphs.withIndex()) {
            val subcrosses = LinkedHashSet(geoms.crosses.filter { isCrossInSubgraph(it, subgraph) })
            if (subcrosses.isEmpty()) continue
            var startingCorner: P? = null
            if (i == 0 && start != null) startingCorner = getCorner(start, subcrosses)
            else if (i == subgraphs.size - 1 && end != null) startingCorner = getCorner(end, subcrosses)
            else {
                // a corner belonging to only one cross avoids starting inside the shape
                for (cross in subcrosses) {
                    startingCorner = cross.goodPoints[0]
                    if (geoms.countAt(startingCorner) == 1) break
                    startingCorner = cross.goodPoints[1]
                    if (geoms.countAt(startingCorner) == 1) break
                }
            }
            cycles += travel + buildSimpleCycles(subcrosses, geoms, checkNotNull(startingCorner))
            travel = emptyList()
        }
        return cycles
    }

    private fun buildSimpleCycles(subcrosses: LinkedHashSet<Cross>, geoms: CrossGeometries, startingPoint: P): List<P> {
        val possible = geoms.good(startingPoint) + geoms.bad(startingPoint)
        val first = possible[0]
        var path = ArrayDeque(checkNotNull(first.cycleFromPoint(startingPoint)))
        geoms.removeCross(first)
        subcrosses.remove(first)
        while (subcrosses.isNotEmpty()) {
            val before = subcrosses.size
            var newPath = ArrayDeque<P>()
            while (path.isNotEmpty()) {
                val current = path.removeFirst()
                newPath.addLast(current)
                for (cross in geoms.good(current).toList()) {
                    // deque.extendleft(reversed(cycle)) prepends the cycle in order
                    checkNotNull(cross.cycleFromPoint(current)).asReversed().forEach { path.addFirst(it) }
                    geoms.removeCross(cross)
                    subcrosses.remove(cross)
                }
            }
            path = newPath
            if (subcrosses.isNotEmpty()) {
                newPath = ArrayDeque()
                while (path.isNotEmpty()) {
                    val current = path.removeFirst()
                    newPath.addLast(current)
                    val bad = geoms.bad(current)
                    if (bad.isNotEmpty()) {
                        val cross = bad[0]
                        newPath.addAll(checkNotNull(cross.cycleFromPoint(current)))
                        newPath.addAll(path)
                        path.clear()
                        geoms.removeCross(cross)
                        subcrosses.remove(cross)
                        break
                    }
                }
                path = newPath
            }
            // Upstream would loop forever here; never observed on connected subgraphs.
            check(subcrosses.size < before) { "Cross stitch cycle made no progress" }
        }
        path.addFirst(startingPoint)
        return path.toList()
    }

    private fun organize(subgraphs: MutableList<Graph>, geoms: CrossGeometries, startingPoint: P?, endingPoint: P?): Triple<List<P>, P?, P?> {
        var start = startingPoint
        val hasCrosses = geoms.crosses.isNotEmpty()
        var startingCorner: P? = null
        var endingCorner: P? = null
        var lastSubgraph = -1
        if (hasCrosses && start != null) {
            val (corner, index) = findIndexSubgraph(subgraphs, geoms.crosses, start)
            startingCorner = corner
            subgraphs.swap(0, index)
        }
        if (hasCrosses && start != null && endingPoint != null) {
            val (corner, index) = findIndexSubgraph(subgraphs, geoms.crosses, endingPoint)
            endingCorner = corner
            lastSubgraph = index
            subgraphs.swap(subgraphs.size - 1, index)
        }
        var travel = emptyList<P>()
        if (hasCrosses && start != null && endingPoint != null && lastSubgraph == 0) {
            val g = subgraphs[0]
            // nx.NodeNotFound is swallowed upstream
            if (startingCorner in g.nodes && endingCorner in g.nodes) travel = shortestPath(g, startingCorner!!, endingCorner!!)
            start = endingPoint
        }
        return Triple(travel, start, endingPoint)
    }

    private fun <T> MutableList<T>.swap(a: Int, b: Int) {
        // Python `l[a], l[b] = l[b], l[a]`
        val va = this[a]; val vb = this[b]
        this[a] = vb; this[b] = va
    }

    private fun findIndexSubgraph(subgraphs: List<Graph>, crosses: Iterable<Cross>, point: P): Pair<P, Int> {
        val corner = getCorner(point, crosses)
        var index = 0
        while (corner !in subgraphs[index].nodes) index++
        return Pair(corner, index)
    }

    private fun isCrossInSubgraph(cross: Cross, subgraph: Graph): Boolean =
        cross.centerPoint in subgraph.nodes && cross.allConnectionPoints.all { it in subgraph.nodes }

    /** networkx bidirectional_shortest_path / _bidirectional_pred_succ on an undirected graph. */
    internal fun shortestPath(g: Graph, source: P, target: P): List<P> {
        val pred = HashMap<P, P?>().apply { put(source, null) }
        val succ = HashMap<P, P?>().apply { put(target, null) }
        var meet: P? = if (source == target) source else null
        var forward = mutableListOf(source)
        var reverse = mutableListOf(target)
        search@ while (meet == null && forward.isNotEmpty() && reverse.isNotEmpty()) {
            if (forward.size <= reverse.size) {
                val level = forward; forward = mutableListOf()
                for (v in level) for (w in g.adj.getValue(v)) {
                    if (w !in pred) { forward += w; pred[w] = v }
                    if (w in succ) { meet = w; break@search }
                }
            } else {
                val level = reverse; reverse = mutableListOf()
                for (v in level) for (w in g.adj.getValue(v)) {
                    if (w !in succ) { succ[w] = v; reverse += w }
                    if (w in pred) { meet = w; break@search }
                }
            }
        }
        val w0 = checkNotNull(meet) { "No path between $source and $target" }
        val path = mutableListOf<P>()
        var w: P? = w0
        while (w != null) { path += w; w = pred[w] }
        path.reverse()
        w = succ[path.last()]
        while (w != null) { path += w; w = succ[w] }
        return path
    }

    private fun cyclesToStitches(cycles: List<List<P>>, maxStitchLength: Double, flip: Boolean): List<List<FillPoint>> =
        cycles.map { cycle ->
            val out = mutableListOf<FillPoint>()
            if (cycle.isNotEmpty()) {
                var last = if (flip) unrotateCoords(cycle[0]) else cycle[0]
                out += FillPoint(last.x, last.y, TAGS)
                for (raw in cycle.drop(1)) {
                    val point = if (flip) unrotateCoords(raw) else raw
                    if (point == last) continue
                    // LineString.segmentize keeps both ends, so each vertex repeats as upstream does.
                    segmentize(last, point, maxStitchLength).forEach { out += FillPoint(it.x, it.y, TAGS) }
                    last = point
                }
            }
            out
        }

    /** GEOS Densifier on one segment. */
    private fun segmentize(a: P, b: P, maxLength: Double): List<P> {
        val length = Math.hypot(b.x - a.x, b.y - a.y)
        val count = ceil(length / maxLength).toInt()
        val out = mutableListOf(a)
        if (count > 1) {
            val segmentLength = length / count
            for (i in 1 until count) {
                val fraction = i * segmentLength / length
                out += P(a.x + fraction * (b.x - a.x), a.y + fraction * (b.y - a.y))
            }
        }
        out += b
        return out
    }

    private val TAGS = setOf("cross_stitch")

    /** CrossGeometries: crosses kept in insertion order (see class comment). */
    internal class CrossGeometries(
        private val shape: Geometry, patternSize: Pair<Double, Double>, private val coverage: Int, private val method: String,
        crossOffset: Pair<Double, Double>, canvasGridOrigin: Boolean, threadCount: Int,
    ) {
        val crosses = LinkedHashSet<Cross>()
        private val byGood = HashMap<P, MutableList<Cross>>()
        private val byBad = HashMap<P, MutableList<Cross>>()
        private val nbRepeats = threadCount / 2
        private val boxX = patternSize.first
        private val boxY = patternSize.second
        private val prepared = PreparedGeometryFactory.prepare(shape)
        private val square = polygon(listOf(P(0.0, 0.0), P(boxX, 0.0), P(boxX, boxY), P(0.0, boxY)))
        private val fullSquareArea = square.area
        private val squareCenter = square.centroid.coordinate.let { P(it.x, it.y) }
        private val uprightSquare = listOf(P(0.0, squareCenter.y), P(squareCenter.x, 0.0), P(boxX, squareCenter.y), P(squareCenter.x, boxY))
        private val adaptedMinX: Double
        private val adaptedMinY: Double
        private val adaptedMaxX: Double
        private val adaptedMaxY: Double

        init {
            var offsetX = crossOffset.first
            var offsetY = crossOffset.second
            val env = shape.envelopeInternal
            if (!canvasGridOrigin) {
                offsetX -= pyMod(env.minX, boxX)
                offsetY += pyMod(env.minY, boxY)
            }
            adaptedMinX = env.minX - pyMod(env.minX, boxX) - offsetX
            adaptedMinY = env.minY - pyMod(env.minY, boxY) + offsetY
            adaptedMaxX = env.maxX + boxX - pyMod(env.maxX, boxX)
            adaptedMaxY = env.maxY + boxY - pyMod(env.maxY, boxY)
            setupCrosses(false)
            if ("dense" in method) setupCrosses(true)
            for (cross in crosses) {
                for (p in cross.goodPoints) byGood.getOrPut(p) { mutableListOf() } += cross
                for (p in cross.badPoints) byBad.getOrPut(p) { mutableListOf() } += cross
            }
        }

        fun good(p: P): List<Cross> = byGood[p] ?: emptyList()
        fun bad(p: P): List<Cross> = byBad[p] ?: emptyList()
        fun countAt(p: P) = good(p).size + bad(p).size

        private fun potentialMiddlePoints(): List<P> {
            val out = mutableListOf<P>()
            var y = adaptedMinY - boxY
            while (y <= adaptedMaxY + boxY) {
                var x = adaptedMinX - boxX
                while (x <= adaptedMaxX + boxX) {
                    out += P(x + boxX / 2, y)
                    out += P(x, y + boxY / 2)
                    x += boxX
                }
                y += boxY
            }
            return out
        }

        private fun setupCrosses(offset: Boolean) {
            val dense = "dense" in method
            val snapPoints = if (dense) potentialMiddlePoints() else emptyList()
            val dx = if (offset) squareCenter.x else 0.0
            val dy = if (offset) squareCenter.y else 0.0
            var y = adaptedMinY - dy
            while (y <= adaptedMaxY) {
                var x = adaptedMinX - dx
                while (x <= adaptedMaxX) {
                    val corners = listOf(P(0.0 + x, 0.0 + y), P(boxX + x, 0.0 + y), P(boxX + x, boxY + y), P(0.0 + x, boxY + y))
                    var upright = uprightSquare.map { P(it.x + x, it.y + y) }
                    if (dense) upright = upright.map { nearest(snapPoints, it) }
                    val box = polygon(corners)
                    if (prepared.contains(box)) add(box, corners, upright)
                    else if (prepared.intersects(box)) {
                        val intersection = box.intersection(shape)
                        if (intersection.area / fullSquareArea * 100 + 0.0001 >= coverage) add(box, corners, upright)
                    }
                    x += boxX
                }
                y += boxY
            }
        }

        private fun add(box: Polygon, corners: List<P>, middle: List<P>) {
            val center = box.centroid.coordinate.let { P(it.x, it.y) }
            crosses += when {
                "upright" in method -> UprightCross(center, corners, middle, nbRepeats)
                "double" in method -> DoubleCross(center, corners, middle, nbRepeats)
                "smyrna" in method -> SmyrnaCross(center, corners, middle, nbRepeats)
                else -> Cross(center, corners, middle, nbRepeats)
            }
        }

        fun removeCross(cross: Cross) {
            for (p in cross.goodPoints) byGood.getValue(p).remove(cross)
            for (p in cross.badPoints) byBad.getValue(p).remove(cross)
            crosses.remove(cross)
        }

        private fun polygon(points: List<P>): Polygon =
            FACTORY.createPolygon((points + points[0]).map { Coordinate(it.x, it.y) }.toTypedArray())
    }

    private val FACTORY = GeometryFactory()

    /** Python float `%`: exact fmod, result takes the divisor's sign. */
    private fun pyMod(a: Double, b: Double): Double {
        val r = a % b
        return if (r != 0.0 && (r < 0) != (b < 0)) r + b else r
    }

    /** Identity equality, as upstream Cross objects. */
    internal open class Cross(val centerPoint: P, val corners: List<P>, middlePoints: List<P>, val nbRepeats: Int) {
        val topLeft = corners[0]
        val topRight = corners[1]
        val bottomRight = corners[2]
        val bottomLeft = corners[3]
        val middleLeft = middlePoints[0]
        val middleTop = middlePoints[1]
        val middleRight = middlePoints[2]
        val middleBottom = middlePoints[3]
        open val goodPoints: List<P> get() = listOf(topRight, bottomLeft)
        open val badPoints: List<P> get() = listOf(topLeft, bottomRight)
        open val allConnectionPoints: List<P> get() = corners

        protected fun rep(vararg points: P, times: Int): List<P> = (0 until max(times, 0)).flatMap { points.asList() }
        protected val c get() = centerPoint
        protected val n get() = nbRepeats

        open fun cycleFromPoint(p: P): List<P>? = when (p) {
            topLeft -> cycleFromTopLeft()
            topRight -> cycleFromTopRight()
            bottomLeft -> cycleFromBottomLeft()
            bottomRight -> cycleFromBottomRight()
            else -> null
        }

        open fun cycleFromTopLeft() = rep(bottomRight, topLeft, times = n - 1) + listOf(bottomRight, c) + rep(topRight, bottomLeft, times = n) + listOf(c, topLeft)
        open fun cycleFromBottomRight() = rep(topLeft, bottomRight, times = n - 1) + listOf(topLeft, c) + rep(topRight, bottomLeft, times = n) + listOf(c, bottomRight)
        open fun cycleFromTopRight() = listOf(c) + rep(topLeft, bottomRight, times = n) + listOf(c) + rep(bottomLeft, topRight, times = n)
        open fun cycleFromBottomLeft() = listOf(c) + rep(bottomRight, topLeft, times = n) + listOf(c) + rep(topRight, bottomLeft, times = n)
    }

    internal class UprightCross(center: P, corners: List<P>, middle: List<P>, nb: Int) : Cross(center, corners, middle, nb) {
        override val goodPoints get() = listOf(middleTop, middleBottom)
        override val badPoints get() = listOf(middleLeft, middleRight)
        override val allConnectionPoints get() = goodPoints + badPoints

        override fun cycleFromPoint(p: P): List<P>? = when (p) {
            middleTop -> listOf(c) + rep(middleLeft, middleRight, times = n) + listOf(c) + rep(middleBottom, middleTop, times = n)
            middleBottom -> listOf(c) + rep(middleLeft, middleRight, times = n) + listOf(c) + rep(middleTop, middleBottom, times = n)
            middleLeft -> rep(middleRight, middleLeft, times = n - 1) + listOf(middleRight, c) + rep(middleTop, middleBottom, times = n) + listOf(c, middleLeft)
            middleRight -> rep(middleLeft, middleRight, times = n - 1) + listOf(middleLeft, c) + rep(middleTop, middleBottom, times = n) + listOf(c, middleRight)
            else -> null
        }
    }

    internal class DoubleCross(center: P, corners: List<P>, middle: List<P>, nb: Int) : Cross(center, corners, middle, nb) {
        private fun upright() = rep(middleLeft, middleRight, times = n) + listOf(c) + rep(middleTop, middleBottom, times = n) + listOf(c)
        override fun cycleFromTopLeft() = rep(bottomRight, topLeft, times = n - 1) + listOf(bottomRight, c) + upright() + rep(topRight, bottomLeft, times = n) + listOf(c, topLeft)
        override fun cycleFromBottomRight() = rep(topLeft, bottomRight, times = n - 1) + listOf(topLeft, c) + upright() + rep(topRight, bottomLeft, times = n) + listOf(c, bottomRight)
        override fun cycleFromTopRight() = listOf(c) + rep(topLeft, bottomRight, times = n) + listOf(c) + upright() + rep(bottomLeft, topRight, times = n)
        override fun cycleFromBottomLeft() = listOf(c) + rep(bottomRight, topLeft, times = n) + listOf(c) + upright() + rep(topRight, bottomLeft, times = n)
    }

    internal class SmyrnaCross(center: P, corners: List<P>, middle: List<P>, nb: Int) : Cross(center, corners, middle, nb) {
        override val goodPoints get() = listOf(middleTop, middleBottom)
        override val badPoints get() = listOf(topLeft, topRight, bottomLeft, bottomRight)
        override val allConnectionPoints get() = goodPoints + badPoints
        private fun upright(last: List<P>) = rep(middleLeft, middleRight, times = n) + listOf(c) + rep(*last.toTypedArray(), times = n)

        override fun cycleFromPoint(p: P): List<P>? = when (p) {
            middleTop -> listOf(c) + rep(topLeft, bottomRight, times = n) + listOf(c) + rep(bottomLeft, topRight, times = n) + listOf(c) + upright(listOf(middleBottom, middleTop))
            middleBottom -> listOf(c) + rep(topLeft, bottomRight, times = n) + listOf(c) + rep(bottomLeft, topRight, times = n) + listOf(c) + upright(listOf(middleTop, middleBottom))
            topLeft -> rep(bottomRight, topLeft, times = n - 1) + listOf(bottomRight, c) + rep(bottomLeft, topRight, times = n) + listOf(c) + upright(listOf(middleBottom, middleTop)) + listOf(c, topLeft)
            bottomRight -> rep(topLeft, bottomRight, times = n - 1) + listOf(topLeft, c) + rep(topRight, bottomLeft, times = n) + listOf(c) + upright(listOf(middleTop, middleBottom)) + listOf(c, bottomRight)
            topRight -> listOf(c) + rep(topLeft, bottomRight, times = n) + listOf(c, bottomLeft) + rep(topRight, bottomLeft, times = n - 1) + listOf(c) + upright(listOf(middleTop, middleBottom)) + listOf(c, topRight)
            bottomLeft -> listOf(c) + rep(bottomRight, topLeft, times = n) + listOf(c, topRight) + rep(bottomLeft, topRight, times = n - 1) + listOf(c) + upright(listOf(middleTop, middleBottom)) + listOf(c, bottomLeft)
            else -> null
        }
    }
}
