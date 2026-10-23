// SPDX-License-Identifier: GPL-3.0-or-later
// Original myembro cell-boundary tracer; no Potrace or Ink/Stitch source copied.
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import io.github.linker2k.myembro.engine.geometry.FillRule
import io.github.linker2k.myembro.engine.geometry.Point
import io.github.linker2k.myembro.engine.geometry.PolygonRegion
import io.github.linker2k.myembro.engine.geometry.Polyline
import org.locationtech.jts.coverage.CoverageSimplifier
import org.locationtech.jts.coverage.CoverageValidator
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.TopologyException
import kotlin.math.abs

/** Geometry uses existing engine units (0.1 mm), with origin at the image's top left, +y down. */
data class ImageVectorRegion(
    val id: Int, val color: ImageColor, val sourcePixelCount: Int, val geometry: PolygonRegion,
) {
    val areaMm2: Double get() = geometry.area / 100.0
}

enum class ImageTraceNotice { SIMPLIFICATION_FALLBACK, COLUMN_REQUIRES_REVIEW }

data class VectorizedImage(
    val widthMm: Double,
    val heightMm: Double,
    val regions: List<ImageVectorRegion>,
    val removedComponentCount: Int,
    val removedAreaMm2: Double,
    val notices: Set<ImageTraceNotice>,
)

/** Four-connected components and oriented cell edges preserve holes and diagonal separation. */
object BitmapVectorizer {
    private const val MAX_REGIONS = 4096
    private const val MAX_EDGES = 200_000

    fun vectorize(image: QuantizedImage, options: ImageTraceOptions): VectorizedImage {
        val pixelMm = options.widthMm / image.width
        val heightMm = pixelMm * image.height
        require(heightMm <= 1000.0) { "Physical image height exceeds 1000 mm" }
        val labels = image.copyIndices()
        val visited = BooleanArray(labels.size)
        val queue = IntArray(labels.size)
        val regions = mutableListOf<ImageVectorRegion>()
        var removedCount = 0; var removedPixels = 0; var totalEdges = 0
        for (seed in labels.indices) {
            if (visited[seed] || labels[seed] < 0) continue
            val color = labels[seed]
            var head = 0; var tail = 1
            queue[0] = seed; visited[seed] = true
            fun visit(next: Int) {
                if (!visited[next] && labels[next] == color) { visited[next] = true; queue[tail++] = next }
            }
            while (head < tail) {
                val p = queue[head++]; val x = p % image.width; val y = p / image.width
                if (x > 0) visit(p - 1)
                if (x + 1 < image.width) visit(p + 1)
                if (y > 0) visit(p - image.width)
                if (y + 1 < image.height) visit(p + image.width)
            }
            if (tail * pixelMm * pixelMm + 1e-12 < options.minimumRegionAreaMm2) {
                removedCount++; removedPixels += tail
                for (i in 0 until tail) labels[queue[i]] = -1
                continue
            }
            require(regions.size < MAX_REGIONS) { "More than $MAX_REGIONS regions; reduce colors or remove smaller regions" }
            val edges = HashSet<Int>()
            val stride = image.width + 1
            // Clockwise edges: the retained pixel is always on the right of the edge.
            fun edge(x: Int, y: Int, direction: Int) {
                totalEdges++
                require(totalEdges <= MAX_EDGES) { "More than $MAX_EDGES boundary edges; downsample or simplify the source image" }
                edges += (y * stride + x) * 4 + direction
            }
            for (i in 0 until tail) {
                val p = queue[i]; val x = p % image.width; val y = p / image.width
                if (y == 0 || labels[p - image.width] != color) edge(x, y, 0)
                if (x + 1 == image.width || labels[p + 1] != color) edge(x + 1, y, 1)
                if (y + 1 == image.height || labels[p + image.width] != color) edge(x + 1, y + 1, 2)
                if (x == 0 || labels[p - 1] != color) edge(x, y + 1, 3)
            }
            val rings = mutableListOf<Polyline>()
            for (start in edges.sorted()) {
                if (start !in edges) continue
                val ring = mutableListOf<Pair<Int, Int>>()
                var current = start
                do {
                    check(edges.remove(current)) { "Invalid cell boundary cycle" }
                    val vertex = current / 4; val dir = current % 4
                    val x = vertex % stride; val y = vertex / stride
                    ring += x to y
                    val end = when (dir) { 0 -> vertex + 1; 1 -> vertex + stride; 2 -> vertex - 1; else -> vertex - stride }
                    if (end == start / 4) break
                    // Right turn first separates touching lobes at saddle vertices deterministically.
                    current = intArrayOf((dir + 1) % 4, dir, (dir + 3) % 4, (dir + 2) % 4)
                        .map { end * 4 + it }.firstOrNull { it in edges }
                        ?: error("Unclosed cell boundary")
                } while (true)
                val corners = ring.filterIndexed { i, b ->
                    val a = ring[(i + ring.size - 1) % ring.size]; val c = ring[(i + 1) % ring.size]
                    (b.first - a.first) * (c.second - b.second) != (b.second - a.second) * (c.first - b.first)
                }
                rings += Polyline(corners.map { Point(it.first * pixelMm * 10, it.second * pixelMm * 10) }, closed = true)
            }
            val geometry = PolygonRegion.fromPaths(rings, FillRule.EVEN_ODD)
            check(abs(geometry.area / 100 - tail * pixelMm * pixelMm) <= 1e-6 * maxOf(1.0, geometry.area)) { "Cell trace lost area" }
            regions += ImageVectorRegion(regions.size, image.colors[color], tail, geometry)
        }
        val notices = mutableSetOf<ImageTraceNotice>()
        val result = if (options.simplifyToleranceMm == 0.0 || regions.isEmpty()) regions else {
            val originals = regions.map { it.geometry.geometry }.toTypedArray()
            val simplified = try {
                if (!CoverageValidator.isValid(originals)) null else {
                    val simplifier = CoverageSimplifier(originals)
                    simplifier.setRemovableRingSizeFactor(0.0) // Small retained holes/islands must not disappear.
                    simplifier.simplify(options.simplifyToleranceMm * 10).takeIf { candidates ->
                        candidates.indices.all { i ->
                            val candidate = candidates[i]
                            !candidate.isEmpty && candidate.isValid && topology(candidate) == topology(originals[i])
                        } && CoverageValidator.isValid(candidates)
                    }
                }
            } catch (_: TopologyException) { null }
            if (simplified == null) {
                notices += ImageTraceNotice.SIMPLIFICATION_FALLBACK
                regions
            } else regions.mapIndexed { i, region -> region.copy(geometry = PolygonRegion(simplified[i])) }
        }
        return VectorizedImage(options.widthMm, heightMm, result.toList(), removedCount,
            removedPixels * pixelMm * pixelMm, notices.toSet())
    }

    private fun topology(g: Geometry): Pair<Int, Int> {
        var holes = 0
        for (i in 0 until g.numGeometries) holes += (g.getGeometryN(i) as Polygon).numInteriorRing
        return g.numGeometries to holes
    }
}
