// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import kotlin.math.PI
import kotlin.math.cbrt
import kotlin.math.pow

/**
 * Color regions of the sample-pack converter, ported with its values unchanged:
 * `tools/color-trace/png2svg_claude_v2.py` `convert()` up to the 2mm cleanup (the SVG smoothing
 * after it is replaced by the existing Potrace route). Lab is scikit-image 0.25 `rgb2lab`/`lab2rgb`
 * (sRGB, D65 2°). Only the 40000-pixel sample and the first k-means seed come from a different
 * random generator than NumPy; [labels] accepts both so parity tests can feed NumPy's choices.
 */
object SampleColorRegions {
    /** Sample long-side limit: the pack's source PNGs are 1024px (cute-art final-list.json). */
    const val MAX_EDGE = 1024
    const val SAMPLE_PIXELS = 40000
    const val CLUSTERS = 10
    const val ITERATIONS = 30
    const val SPARSE_FRACTION = 0.005
    const val MERGE_DELTA_E = 18.0
    const val MIN_DIAMETER_MM = 2.0
    const val CLEANUP_PASSES = 3

    /** [labels]: palette index per pixel, −1 for background. [palette] is RGB, largest area first. */
    class Regions(val width: Int, val height: Int, val labels: IntArray, val palette: List<Int>)

    private const val XN = 0.95047
    private const val YN = 1.0
    private const val ZN = 1.08883
    private val XYZ_FROM_RGB = arrayOf(
        doubleArrayOf(0.412453, 0.357580, 0.180423),
        doubleArrayOf(0.212671, 0.715160, 0.072169),
        doubleArrayOf(0.019334, 0.119193, 0.950227))
    private val RGB_FROM_XYZ = invert(XYZ_FROM_RGB)

    /** [argb] is composited on white paper; fully transparent pixels are background. */
    fun labels(width: Int, height: Int, argb: IntArray, widthMm: Double, maxColors: Int,
        sample: IntArray? = null, firstCenter: Int? = null, checkpoint: (String) -> Unit = {}): Regions {
        require(width in 1..MAX_EDGE && height in 1..MAX_EDGE && width.toLong() * height == argb.size.toLong())
        require(widthMm.isFinite() && widthMm > 0 && maxColors in 2..6)
        checkpoint("quantization")
        val n = argb.size
        val lab = DoubleArray(n * 3)
        val cache = HashMap<Int, Int>()
        for (p in 0 until n) {
            val rgb = paper(argb[p])
            val known = cache[rgb]
            if (known != null) { System.arraycopy(lab, known * 3, lab, p * 3, 3); continue }
            cache[rgb] = p
            rgbToLab(rgb, lab, p * 3)
        }
        val chosen = sample ?: defaultSample(n)
        require(chosen.isNotEmpty() && chosen.all { it in 0 until n })
        val x = DoubleArray(chosen.size * 3) { lab[chosen[it / 3] * 3 + it % 3] }
        val centers = kmeans(x, chosen.size, firstCenter ?: kotlin.random.Random(0).nextInt(chosen.size)).toMutableList()
        val counts = MutableList(centers.size) { 0.0 }
        for (i in chosen.indices) counts[nearest(x, i, centers)] += 1.0
        foldSparse(centers, counts, chosen.size)
        // +1 slot for the background white, which is not drawn
        while (centers.size > 1) {
            var best = Double.POSITIVE_INFINITY; var bi = 0; var bj = 0
            for (i in centers.indices) for (j in i + 1 until centers.size) {
                val d = distance(centers[i], centers[j])
                if (d < best) { best = d; bi = i; bj = j }
            }
            if (centers.size <= maxColors + 1 && best >= MERGE_DELTA_E) break
            val (keep, drop) = if (counts[bi] >= counts[bj]) bi to bj else bj to bi
            counts[keep] += counts[drop]; centers.removeAt(drop); counts.removeAt(drop)
        }
        checkpoint("quantization")
        val labels = IntArray(n) { nearest(lab, it, centers) }
        val white = centers.indices.maxBy { centers[it][0] }
        removeBorderWhite(width, height, labels, white)
        for (p in 0 until n) if (argb[p] ushr 24 == 0) labels[p] = -1
        val mmPerPixel = widthMm / width
        cleanup(width, height, labels, centers.size, PI * (MIN_DIAMETER_MM / 2 / mmPerPixel).pow(2), checkpoint)
        val areas = IntArray(centers.size)
        for (label in labels) if (label >= 0) areas[label]++
        val order = centers.indices.filter { areas[it] > 0 }.sortedByDescending { areas[it] }
        val remap = IntArray(centers.size) { -1 }
        order.forEachIndexed { k, c -> remap[c] = k }
        for (p in labels.indices) if (labels[p] >= 0) labels[p] = remap[labels[p]]
        return Regions(width, height, labels, order.map { labToRgb(centers[it]) })
    }

    private fun paper(argb: Int): Int {
        val a = argb ushr 24
        if (a == 255) return argb and 0xffffff
        var rgb = 0
        for (shift in intArrayOf(16, 8, 0)) {
            val c = (argb ushr shift) and 255
            rgb = rgb or (((c * a + 255 * (255 - a) + 127) / 255) shl shift)
        }
        return rgb
    }

    private fun defaultSample(n: Int): IntArray {
        if (n <= SAMPLE_PIXELS) return IntArray(n) { it }
        val index = IntArray(n) { it }
        val random = kotlin.random.Random(1)
        for (i in 0 until SAMPLE_PIXELS) {
            val j = i + random.nextInt(n - i)
            val t = index[i]; index[i] = index[j]; index[j] = t
        }
        return index.copyOf(SAMPLE_PIXELS)
    }

    private fun kmeans(x: DoubleArray, m: Int, first: Int): List<DoubleArray> {
        val centers = mutableListOf(doubleArrayOf(x[first * 3], x[first * 3 + 1], x[first * 3 + 2]))
        val minimum = DoubleArray(m) { Double.POSITIVE_INFINITY }
        repeat(CLUSTERS - 1) { // farthest-point init keeps small distinct colors
            val last = centers.last()
            var far = 0
            for (i in 0 until m) {
                minimum[i] = minOf(minimum[i], squared(x, i, last))
                if (minimum[i] > minimum[far]) far = i
            }
            centers += doubleArrayOf(x[far * 3], x[far * 3 + 1], x[far * 3 + 2])
        }
        val assignment = IntArray(m)
        repeat(ITERATIONS) {
            for (i in 0 until m) assignment[i] = nearest(x, i, centers)
            val sums = Array(centers.size) { DoubleArray(3) }
            val counts = IntArray(centers.size)
            for (i in 0 until m) {
                val c = assignment[i]; counts[c]++
                for (k in 0..2) sums[c][k] += x[i * 3 + k]
            }
            for (c in centers.indices) if (counts[c] > 0)
                centers[c] = DoubleArray(3) { sums[c][it] / counts[c] }
        }
        return centers
    }

    /** User-approved v2 fix: fold clusters below 0.5% of sampled pixels before closest-pair merging. */
    private fun foldSparse(centers: MutableList<DoubleArray>, counts: MutableList<Double>, sampleCount: Int) {
        while (centers.size > 1) {
            val small = counts.indices.filter { counts[it] < sampleCount * SPARSE_FRACTION }
            if (small.isEmpty()) break
            val drop = small.minWith(compareBy<Int>({ counts[it] }, { it }))
            val keep = centers.indices.filter { it != drop }
                .minWith(compareBy<Int>({ distance(centers[drop], centers[it]) }, { it }))
            counts[keep] += counts[drop]; centers.removeAt(drop); counts.removeAt(drop)
        }
    }

    /** Background = white-cluster pixels 4-connected to the border (SciPy `ndi.label` default). */
    private fun removeBorderWhite(width: Int, height: Int, labels: IntArray, white: Int) {
        val queue = IntArray(labels.size); var tail = 0
        fun seed(p: Int) { if (labels[p] == white) { labels[p] = -1; queue[tail++] = p } }
        for (x in 0 until width) { seed(x); seed((height - 1) * width + x) }
        for (y in 0 until height) { seed(y * width); seed(y * width + width - 1) }
        var head = 0
        while (head < tail) {
            val p = queue[head++]; val x = p % width
            if (x > 0) seed(p - 1); if (x + 1 < width) seed(p + 1)
            if (p >= width) seed(p - width); if (p + width < labels.size) seed(p + width)
        }
    }

    /** Reassign pieces under 2mm diameter to their surrounding color, as the pack converter does. */
    private fun cleanup(width: Int, height: Int, labels: IntArray, colors: Int, minArea: Double, checkpoint: (String) -> Unit) {
        val component = IntArray(labels.size)
        val queue = IntArray(labels.size)
        val ring = IntArray(colors + 1)
        repeat(CLEANUP_PASSES) {
            var changed = false
            for (c in 0 until colors) {
                checkpoint("quantization")
                // Components in raster order of their first pixel, as SciPy numbers them.
                component.fill(0)
                val pieces = mutableListOf<IntArray>()
                for (start in labels.indices) if (labels[start] == c && component[start] == 0) {
                    component[start] = pieces.size + 1
                    var head = 0; var tail = 1; queue[0] = start
                    while (head < tail) {
                        val p = queue[head++]; val x = p % width
                        fun visit(q: Int) { if (labels[q] == c && component[q] == 0) { component[q] = pieces.size + 1; queue[tail++] = q } }
                        if (x > 0) visit(p - 1); if (x + 1 < width) visit(p + 1)
                        if (p >= width) visit(p - width); if (p + width < labels.size) visit(p + width)
                    }
                    pieces += queue.copyOf(tail)
                }
                for ((index, piece) in pieces.withIndex()) {
                    if (piece.size >= minArea) continue
                    val id = index + 1
                    ring.fill(0)
                    var any = false
                    var minX = width; var maxX = 0; var minY = height; var maxY = 0
                    for (p in piece) { val x = p % width; val y = p / width
                        minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y) }
                    // Two cross-shaped binary dilations = city-block distance ≤ 2.
                    for (y in maxOf(0, minY - 2)..minOf(height - 1, maxY + 2))
                        for (x in maxOf(0, minX - 2)..minOf(width - 1, maxX + 2)) {
                            val p = y * width + x
                            if (component[p] == id) continue
                            if (!near(width, height, component, id, x, y)) continue
                            val label = labels[p]
                            if (label != c) { ring[label + 1]++; any = true }
                        }
                    // np.bincount(nb+1).argmax()-1: ties go to the lowest label, background first.
                    var target = -1
                    if (any) { var best = 0; for (k in ring.indices) if (ring[k] > ring[best]) best = k; target = best - 1 }
                    for (p in piece) labels[p] = target
                    changed = true
                }
            }
            if (!changed) return
        }
    }

    private fun near(width: Int, height: Int, component: IntArray, id: Int, x: Int, y: Int): Boolean {
        for (dy in -2..2) for (dx in -2..2) {
            if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > 2) continue
            val nx = x + dx; val ny = y + dy
            if (nx in 0 until width && ny in 0 until height && component[ny * width + nx] == id) return true
        }
        return false
    }

    private fun nearest(x: DoubleArray, i: Int, centers: List<DoubleArray>): Int {
        var best = 0; var bestDistance = Double.POSITIVE_INFINITY
        for (c in centers.indices) {
            val d = squared(x, i, centers[c])
            if (d < bestDistance) { bestDistance = d; best = c }
        }
        return best
    }

    private fun squared(x: DoubleArray, i: Int, c: DoubleArray): Double {
        val a = x[i * 3] - c[0]; val b = x[i * 3 + 1] - c[1]; val d = x[i * 3 + 2] - c[2]
        return a * a + b * b + d * d
    }

    private fun distance(a: DoubleArray, b: DoubleArray): Double =
        kotlin.math.sqrt((a[0] - b[0]).pow(2) + (a[1] - b[1]).pow(2) + (a[2] - b[2]).pow(2))

    internal fun rgbToLab(rgb: Int, out: DoubleArray, offset: Int) {
        val linear = DoubleArray(3) { k ->
            val v = ((rgb ushr (16 - k * 8)) and 255) / 255.0
            if (v > 0.04045) ((v + 0.055) / 1.055).pow(2.4) else v / 12.92
        }
        fun f(t: Double) = if (t > 0.008856) cbrt(t) else 7.787 * t + 16.0 / 116.0
        val fx = f(dot(XYZ_FROM_RGB[0], linear) / XN)
        val fy = f(dot(XYZ_FROM_RGB[1], linear) / YN)
        val fz = f(dot(XYZ_FROM_RGB[2], linear) / ZN)
        out[offset] = 116.0 * fy - 16.0
        out[offset + 1] = 500.0 * (fx - fy)
        out[offset + 2] = 200.0 * (fy - fz)
    }

    internal fun labToRgb(lab: DoubleArray): Int {
        val y = (lab[0] + 16.0) / 116.0
        val x = lab[1] / 500.0 + y
        val z = maxOf(0.0, y - lab[2] / 200.0)
        fun inverse(t: Double) = if (t > 0.2068966) t * t * t else (t - 16.0 / 116.0) / 7.787
        val xyz = doubleArrayOf(inverse(x) * XN, inverse(y) * YN, inverse(z) * ZN)
        var rgb = 0
        for (k in 0..2) {
            val v = dot(RGB_FROM_XYZ[k], xyz)
            val g = (if (v > 0.0031308) 1.055 * v.pow(1 / 2.4) - 0.055 else v * 12.92).coerceIn(0.0, 1.0)
            // np.round: half to even
            rgb = rgb or (Math.rint(g * 255).toInt() shl (16 - k * 8))
        }
        return rgb
    }

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun invert(m: Array<DoubleArray>): Array<DoubleArray> {
        val (a, b, c) = m[0].toList(); val (d, e, f) = m[1].toList(); val (g, h, i) = m[2].toList()
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        return arrayOf(
            doubleArrayOf((e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det),
            doubleArrayOf((f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det),
            doubleArrayOf((d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det))
    }
}
