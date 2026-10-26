// Ported from Ink/Stitch lib/stitches/meander_fill.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
// Ported from Ink/Stitch lib/tiles.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.stitches

import io.github.linker2k.myembro.engine.geometry.Affine2D
import io.github.linker2k.myembro.engine.geometry.Point
import io.github.linker2k.myembro.engine.geometry.SvgPath
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.linearref.LengthIndexedLine
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class MeanderFillParameters(
    val meander_pattern: String = "N3-11a",
    val meander_angle: Double = 0.0,
    val meander_scale_percent: List<Double> = listOf(100.0, 100.0),
    val clip: Boolean = false,
    val smoothness_mm: Double = 0.0,
    val running_stitch_length_mm: Double = 2.5,
    val running_stitch_tolerance_mm: Double = 0.2,
    val zigzag_spacing_mm: Double = 0.0,
    val zigzag_width_mm: Double = 3.0,
    val bean_stitch_repeats: List<Int> = listOf(0),
    val repeats: Int = 1,
    val random_seed: String = "",
) {
    init {
        require(meander_angle.isFinite() && meander_scale_percent.size in 1..2 && meander_scale_percent.all { it.isFinite() })
        require(listOf(running_stitch_length_mm, running_stitch_tolerance_mm).all { it.isFinite() && it > 0 })
        require(listOf(smoothness_mm, zigzag_spacing_mm, zigzag_width_mm).all { it.isFinite() && it >= 0 })
        require(repeats in 1..16 && bean_stitch_repeats.isNotEmpty() && bean_stitch_repeats.all { it in 0..20 })
    }
}

/** Centered, endpoint-only tile as in Tile._load_paths; shifts are SVG px vectors. */
data class MeanderTile(val id: String, val segments: List<Pair<FillPoint, FillPoint>>, val shift0: FillPoint, val shift1: FillPoint) {
    init { require(segments.isNotEmpty() && shift0.length() > 0 && shift1.length() > 0) }
}

/** Seeded tile graph expansion, using source edge/edge-pair detours rather than a random walk. */
object MeanderFill {
    private const val PX_PER_MM = 96.0 / 25.4
    private const val MAX_NODES = 20_000
    private val tiles = ConcurrentHashMap<String, MeanderTile>()
    private data class Edge(val start: FillPoint, val end: FillPoint)

    fun availablePatterns(): List<String> = MeanderFill::class.java.getResourceAsStream("/inkstitch/tiles/index.txt")
        ?.bufferedReader()?.use { it.readLines().filter(String::isNotBlank) } ?: emptyList()

    /** Unknown pattern IDs fall back to the last bundled tile, as upstream get_tile does. */
    fun loadTile(id: String): MeanderTile {
        val ids = availablePatterns()
        require(ids.isNotEmpty()) { "No meander tiles installed" }
        val selected = if (id in ids) id else ids.last()
        return tiles.computeIfAbsent(selected) {
            val data = MeanderFill::class.java.getResourceAsStream("/inkstitch/tiles/$selected.svg")
                ?.use { stream -> stream.readBytes() } ?: error("Missing meander tile: $selected")
            tileFromSvg(selected, data)
        }
    }

    fun fill(shape: Polygon, parameters: MeanderFillParameters = MeanderFillParameters(),
        originalShape: Polygon = shape, startingPoint: FillPoint? = null, endingPoint: FillPoint? = null,
        shapeIndex: Int = 0, tile: MeanderTile? = null): List<FillPoint> {
        require(shape.isValid && originalShape.isValid) { "Meander expects a make_valid polygon component" }
        if (shape.isEmpty) return emptyList()
        val graph = tileGraph(shape, tile ?: loadTile(parameters.meander_pattern), parameters)
        require(graph.size >= 2) { "Could not build graph for meander stitching; enlarge the shape or reduce pattern scale" }
        ensureConnected(graph)
        val (start, end) = startingAndEndingNodes(graph, shape, startingPoint, endingPoint)
        val rng = MeanderPrng(parameters.random_seed, shapeIndex)
        val path = generatePath(graph, start, end, rng::next)
        return postProcess(path, parameters, originalShape)
    }

    /** Source tile SVGs are data only. DTDs and external entities are disabled. */
    fun tileFromSvg(id: String, svg: ByteArray): MeanderTile {
        require(svg.size <= 1_000_000) { "Meander tile is too large" }
        // Same layers as SvgPictureInput: a lexical declaration ban that does not depend on JAXP feature
        // support (Android's factory rejects the Xerces features below), then features where available,
        // then a resolver that refuses any external entity.
        val text = svg.decodeToString()
        require('\u0000' !in text && !Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(text)) { "Tile declarations forbidden" }
        val builder = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            for ((feature, enabled) in listOf(
                "http://apache.org/xml/features/disallow-doctype-decl" to true,
                "http://xml.org/sax/features/external-general-entities" to false,
                "http://xml.org/sax/features/external-parameter-entities" to false,
                "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false)) {
                try { setFeature(feature, enabled) } catch (_: javax.xml.parsers.ParserConfigurationException) { }
            }
            isExpandEntityReferences = false
        }.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> throw IllegalArgumentException("External tile entity forbidden") }
        }
        val root = builder.parse(ByteArrayInputStream(svg)).documentElement
        val segments = mutableListOf<Pair<FillPoint, FillPoint>>()
        var parallelogram: List<FillPoint>? = null
        fun walk(element: Element, inherited: Affine2D) {
            val matrix = inherited * if (element.hasAttribute("transform")) Affine2D.parse(element.getAttribute("transform")) else Affine2D.IDENTITY
            val type = element.localName ?: element.tagName.substringAfter(':')
            fun convert(p: Point): FillPoint = matrix.apply(p).let { FillPoint(it.x, it.y) }
            if (type == "path") {
                val paths = SvgPath.flatten(element.getAttribute("d"))
                paths.filter { it.points.isNotEmpty() }.forEach { path -> segments += convert(path.points.first()) to convert(path.points.last()) }
                if (element.getAttribute("class").split(' ').contains("para"))
                    parallelogram = paths.firstOrNull()?.points?.map(::convert)
            } else if (type in setOf("polygon", "polyline") && element.getAttribute("class").split(' ').contains("para")) {
                val numbers = NUMBER.findAll(element.getAttribute("points")).map { it.value.toDouble() }.toList()
                require(numbers.size % 2 == 0)
                parallelogram = numbers.chunked(2).map { convert(Point(it[0], it[1])) }
            }
            val children = element.childNodes
            for (i in 0 until children.length) (children.item(i) as? Element)?.let { walk(it, matrix) }
        }
        walk(root, Affine2D.IDENTITY)
        require(segments.isNotEmpty()) { "Tile contains no line segments" }
        val all = segments.flatMap { listOf(it.first, it.second) }
        val center = FillPoint((all.minOf { it.x } + all.maxOf { it.x }) / 2,
            (all.minOf { it.y } + all.maxOf { it.y }) / 2)
        val para = parallelogram
        val shift0: FillPoint
        val shift1: FillPoint
        if (para != null && para.size >= 3) { shift0 = para[1] - para[0]; shift1 = para[2] - para[1] }
        else { shift0 = FillPoint(dimension(root.getAttribute("width")), 0.0); shift1 = FillPoint(0.0, dimension(root.getAttribute("height"))) }
        return MeanderTile(id, segments.map { (a, b) -> (a - center) to (b - center) }, shift0, shift1)
    }

    private val NUMBER = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
    private fun dimension(value: String): Double {
        val number = NUMBER.find(value)?.value?.toDouble() ?: error("Tile is missing dimensions")
        val unit = value.trim().removePrefix(NUMBER.find(value)!!.value).trim()
        return number * when (unit) { "", "px" -> 1.0; "mm" -> PX_PER_MM; "cm" -> 10 * PX_PER_MM; "in" -> 96.0; "pt" -> 96.0 / 72; else -> error("Unsupported SVG tile unit: $unit") }
    }

    internal fun tileGraph(shape: Polygon, tile: MeanderTile, parameters: MeanderFillParameters): LinkedHashMap<FillPoint, LinkedHashSet<FillPoint>> {
        val xScale = max(1.0, parameters.meander_scale_percent.first()) / 100
        val yScale = max(1.0, parameters.meander_scale_percent.last()) / 100
        val angle = Math.toRadians(parameters.meander_angle)
        fun transform(p: FillPoint) = FillPoint(p.x * xScale, p.y * yScale).rotate(angle)
        val shift0 = transform(tile.shift0); val shift1 = transform(tile.shift1)
        val segments = tile.segments.map { (a, b) -> transform(a) to transform(b) }
        val envelope = shape.envelopeInternal
        val center = FillPoint(envelope.centre().x, envelope.centre().y)
        val count = ceil(hypot(envelope.width, envelope.height) / min(shift0.length(), shift1.length())).toInt()
        require(count in 0..225) { "Meander tile repetition resource limit exceeded" }
        val diagonal = (shift0 + shift1).length()
        val graph = linkedMapOf<FillPoint, LinkedHashSet<FillPoint>>()
        for (i in -count until count) for (j in -count until count) {
            val offset = shift0 * i.toDouble() + shift1 * j.toDouble()
            if (abs(offset.x) > envelope.width / 2 + diagonal || abs(offset.y) > envelope.height / 2 + diagonal) continue
            for ((a, b) in segments) {
                fun rounded(p: FillPoint) = FillPoint(Math.rint(p.x + offset.x + center.x), Math.rint(p.y + offset.y + center.y))
                val start = rounded(a); val end = rounded(b)
                if (shape.contains(FillGeometry.line(listOf(start, end)))) addEdge(graph, start, end)
            }
            require(graph.size <= MAX_NODES) { "Meander node resource limit exceeded" }
        }
        for ((node, neighbors) in graph) neighbors.remove(node)
        while (true) {
            val dead = graph.filterValues { it.size <= 1 }.keys.toList()
            if (dead.isEmpty()) break
            for (node in dead) { graph.remove(node)?.forEach { graph[it]?.remove(node) } }
        }
        return graph
    }

    private fun addEdge(graph: LinkedHashMap<FillPoint, LinkedHashSet<FillPoint>>, a: FillPoint, b: FillPoint) {
        graph.getOrPut(a) { linkedSetOf() }.add(b)
        graph.getOrPut(b) { linkedSetOf() }.add(a)
    }
    private fun removeEdge(graph: LinkedHashMap<FillPoint, LinkedHashSet<FillPoint>>, edge: Edge) {
        graph[edge.start]?.remove(edge.end); graph[edge.end]?.remove(edge.start)
    }

    /** k=1 edge augmentation is the MST of pairwise nearest component connections. */
    internal fun ensureConnected(graph: LinkedHashMap<FillPoint, LinkedHashSet<FillPoint>>) {
        val components = mutableListOf<List<FillPoint>>()
        val remaining = graph.keys.toMutableSet()
        for (root in graph.keys) {
            if (!remaining.remove(root)) continue
            val component = mutableListOf<FillPoint>()
            val queue = ArrayDeque<FillPoint>().apply { add(root) }
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst(); component += node
                for (next in graph.getValue(node)) if (remaining.remove(next)) queue += next
            }
            components += component
        }
        data class Candidate(val i: Int, val j: Int, val a: FillPoint, val b: FillPoint, val distance: Double)
        val candidates = mutableListOf<Candidate>()
        for (i in components.indices) for (j in i + 1 until components.size) {
            var best: Candidate? = null
            for (a in components[i]) for (b in components[j]) {
                val distance = a.distance(b)
                if (best == null || distance < best.distance) best = Candidate(i, j, a, b, distance)
            }
            best?.let(candidates::add)
        }
        val parent = IntArray(components.size) { it }
        fun root(i: Int): Int { var n = i; while (parent[n] != n) n = parent[n]; return n }
        for (candidate in candidates.sortedBy { it.distance }) {
            val a = root(candidate.i); val b = root(candidate.j)
            if (a == b) continue
            parent[a] = b
            addEdge(graph, candidate.a, candidate.b)
        }
    }

    internal fun startingAndEndingNodes(graph: LinkedHashMap<FillPoint, LinkedHashSet<FillPoint>>, shape: Polygon,
        startingPoint: FillPoint?, endingPoint: FillPoint?): Pair<FillPoint, FillPoint> {
        val start = startingPoint ?: shape.exteriorRing.coordinate.let { FillPoint(it.x, it.y) }
        val end = endingPoint ?: LengthIndexedLine(shape.exteriorRing).let { indexed ->
            val distance = (indexed.project(Coordinate(start.x, start.y)) + indexed.endIndex / 2) % indexed.endIndex
            indexed.extractPoint(distance).let { FillPoint(it.x, it.y) }
        }
        val startNode = graph.keys.minBy { it.distance(start) }
        val endNode = graph.keys.filter { it != startNode }.minBy { it.distance(end) }
        return startNode to endNode
    }

    /** Bidirectional BFS mirrors networkx.shortest_path's unweighted search and adjacency order. */
    private fun shortestPath(graph: LinkedHashMap<FillPoint, LinkedHashSet<FillPoint>>, start: FillPoint, end: FillPoint): List<FillPoint> {
        val pred = linkedMapOf<FillPoint, FillPoint?>(start to null)
        val succ = linkedMapOf<FillPoint, FillPoint?>(end to null)
        var forward = listOf(start); var backward = listOf(end)
        var meeting: FillPoint? = null
        while (forward.isNotEmpty() && backward.isNotEmpty() && meeting == null) {
            val isForward = forward.size <= backward.size
            val current = if (isForward) forward else backward
            val next = mutableListOf<FillPoint>()
            val own = if (isForward) pred else succ
            val other = if (isForward) succ else pred
            loop@ for (v in current) for (w in graph.getValue(v)) {
                if (w !in own) { own[w] = v; next += w }
                if (w in other) { meeting = w; break@loop }
            }
            if (isForward) forward = next else backward = next
        }
        val meet = meeting ?: error("Meander graph is disconnected")
        val left = mutableListOf<FillPoint>()
        var node: FillPoint? = meet
        while (node != null) { left += node; node = pred[node] }
        left.reverse()
        node = succ[meet]
        while (node != null) { left += node; node = succ[node] }
        return left
    }

    // Ink/Stitch meander_fill.py:116,123 + utils/threading.py:19-23.
    // Upstream has path-length cutoffs (7/10), not a cumulative search-step budget.
    private fun checkStopFlag() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Meander generation cancelled")
    }

    internal fun generatePath(graph: LinkedHashMap<FillPoint, LinkedHashSet<FillPoint>>, start: FillPoint,
        end: FillPoint, rng: () -> Double): List<FillPoint> {
        val initial = shortestPath(graph, start, end)
        val path = initial.zipWithNext { a, b -> Edge(a, b) }.toMutableList()
        path.forEach { removeEdge(graph, it) }
        // Stable IDs preserve graph/adjacency order while avoiding coordinate hashing in deep DFS.
        val nodes = graph.keys.toList()
        val ids = nodes.withIndex().associate { it.value to it.index }
        val initialNodes = initial.toSet()
        val unused = BooleanArray(nodes.size) { nodes[it] !in initialNodes }
        val adjacency = Array(nodes.size) { i -> graph.getValue(nodes[i]).map { ids.getValue(it) }.toMutableList() }
        val visited = BooleanArray(nodes.size)
        val edges = path.toMutableList()
        fun alternative(a: FillPoint, b: FillPoint, cutoff: Int, minimum: Int): List<Edge>? {
            val startId = ids.getValue(a); val endId = ids.getValue(b)
            val candidate = IntArray(cutoff + 1)
            candidate[0] = startId
            fun dfs(node: Int, depth: Int): List<Edge>? {
                if (node == endId) return if (depth > minimum)
                    List(depth) { Edge(nodes[candidate[it]], nodes[candidate[it + 1]]) } else null
                if (depth >= cutoff) return null
                for (neighbor in adjacency[node]) {
                    if ((!unused[neighbor] && neighbor != startId && neighbor != endId) || visited[neighbor]) continue
                    visited[neighbor] = true
                    candidate[depth + 1] = neighbor
                    val result = dfs(neighbor, depth + 1)
                    visited[neighbor] = false
                    if (result != null) return result
                }
                return null
            }
            visited[startId] = true
            return try { dfs(startId, 0) } finally { visited[startId] = false }
        }
        fun <T> popRandom(list: MutableList<T>): T {
            val index = Math.rint(rng() * (list.size - 1)).toInt()
            val item = list[index]
            val last = list.removeAt(list.lastIndex)
            if (index < list.size) list[index] = last
            return item
        }
        fun apply(index: Int, removeCount: Int, replacement: List<Edge>) {
            repeat(removeCount) { path.removeAt(index) }
            path.addAll(index, replacement)
            replacement.forEach {
                removeEdge(graph, it)
                val a = ids.getValue(it.start); val b = ids.getValue(it.end)
                adjacency[a].remove(b); adjacency[b].remove(a)
                unused[a] = false
            }
            edges += replacement
        }
        while (edges.isNotEmpty()) {
            while (edges.isNotEmpty()) {
                checkStopFlag()
                val edge = popRandom(edges)
                val replacement = alternative(edge.start, edge.end, 7, 1) ?: continue
                apply(path.indexOf(edge), 1, replacement)
            }
            val pairs = path.zipWithNext().toMutableList()
            while (pairs.isNotEmpty()) {
                checkStopFlag()
                val (first, second) = popRandom(pairs)
                val replacement = alternative(first.start, second.end, 10, 2) ?: continue
                apply(path.indexOf(first), 2, replacement)
                break
            }
        }
        return if (path.isEmpty()) emptyList() else path.map { it.start } + path.last().end
    }

    internal fun postProcess(points: List<FillPoint>, parameters: MeanderFillParameters,
        originalShape: Polygon? = null): List<FillPoint> {
        val tolerance = max(0.01, parameters.running_stitch_tolerance_mm) * PX_PER_MM
        val runningLength = max(0.01, parameters.running_stitch_length_mm) * PX_PER_MM
        val smoothed = FillGeometry.smoothPath(points, parameters.smoothness_mm * PX_PER_MM)
        var stitches = if (parameters.zigzag_spacing_mm > 0) {
            val sampled = FillRunning.run(smoothed, parameters.zigzag_spacing_mm * PX_PER_MM / 2, tolerance)
            zigzagStitch(sampled, parameters.zigzag_width_mm * PX_PER_MM)
        } else smoothed
        if (parameters.clip && stitches.size >= 2) {
            requireNotNull(originalShape) { "Meander clipping requires originalShape" }
            // Source clamp_path_to_polygon(check_distance=False) follows full outside excursions
            // along the border; the shared clamp performs the corresponding ordered stitching.
            stitches = segmentize(FillRouting.clampPath(stitches, originalShape), runningLength)
        }
        if (parameters.clip || parameters.zigzag_spacing_mm == 0.0)
            stitches = FillRunning.run(stitches, runningLength, tolerance)
        if (stitches.size >= 2 && parameters.bean_stitch_repeats.any { it > 0 }) {
            val bean = mutableListOf(stitches.first())
            for ((i, pair) in stitches.zipWithNext().withIndex()) {
                bean += pair.second
                repeat(parameters.bean_stitch_repeats[i % parameters.bean_stitch_repeats.size]) { bean += pair.first; bean += pair.second }
            }
            stitches = bean
        }
        for (i in 1 until parameters.repeats) stitches = stitches + if (i % 2 == 1) stitches.reversed() else stitches
        return stitches
    }

    /** Shapely segments_to_path(..., max_stitch_length): split each clamped segment evenly. */
    private fun segmentize(points: List<FillPoint>, maximumPx: Double): List<FillPoint> {
        if (points.size < 2) return points
        val result = mutableListOf(points.first())
        for ((a, b) in points.zipWithNext()) {
            val count = max(1, ceil(a.distance(b) / maximumPx).toInt())
            require(count <= 1_000_000 && result.size.toLong() + count <= 1_000_000) { "Meander clipping resource limit exceeded" }
            for (i in 1..count) result += a.interpolate(b, i.toDouble() / count)
        }
        return result
    }

    internal fun zigzagStitch(stitches: List<FillPoint>, strokeWidthPx: Double): List<FillPoint> {
        val result = stitches.toMutableList()
        for (i in 0 until result.lastIndex) {
            val direction = result[i + 1] - result[i]
            if (direction.length() == 0.0) continue
            val unit = direction.unit()
            result[i] = result[i] + FillPoint(-unit.y, unit.x) * (strokeWidthPx / 2) * if (i % 2 == 1) -1.0 else 1.0
        }
        return result
    }
}
