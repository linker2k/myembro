// SPDX-License-Identifier: GPL-3.0-or-later AND LicenseRef-Graphics-Gems
// Ported from GraphicsGems gemsii/quantizer.c@ca4898517a93b6b45444a9cda5fd50132694b864.
// C Implementation of Wu's Color Quantizer (v. 2), Author: Xiaolin Wu.
// "Free to distribute, comments and suggestions are appreciated."
// The author thanks Tom Lane for documentation and a cure to a previous bug.
// Kotlin translation (c) 2026 myembro contributors. See licenses/graphics-gems/LICENSE.md.
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import kotlin.math.abs

/** RGB palette in original box order; no nearest-neighbor reassignment, alpha or thread mapping. */
class WuQuantizedImage internal constructor(val palette: List<Int>, indices: IntArray) {
    private val labels = indices.copyOf()
    fun copyIndices(): IntArray = labels.copyOf()
}

/** Wu 1991 variance-minimizing quantization, original v2 implementation. Engine-only, no app caller. */
object ImageQuantizer {
    fun quantize(rgb: IntArray, colorCount: Int): WuQuantizedImage {
        require(rgb.isNotEmpty() && rgb.size <= 1_048_576)
        require(colorCount in 1..256)
        require(rgb.all { it in 0..0xFFFFFF }) { "Input must be RGB24; alpha handling is outside this algorithm" }
        return State(rgb).quantize(colorCount)
    }

    private class Box(val low: IntArray = IntArray(3), val high: IntArray = IntArray(3) { 32 }) {
        val volume get() = (high[0] - low[0]) * (high[1] - low[1]) * (high[2] - low[2])
        fun copy() = Box(low.copyOf(), high.copyOf())
    }

    private class State(rgb: IntArray) {
        // Source LP64 long moments and IEEE-754 float second moments/variance are preserved.
        val moments = Array(4) { LongArray(33 * 33 * 33) } // wt, mr, mg, mb
        val squares = FloatArray(33 * 33 * 33)
        val addresses = IntArray(rgb.size)
        init {
            rgb.forEachIndexed { i, pixel ->
                val r = pixel ushr 16
                val g = pixel ushr 8 and 255
                val b = pixel and 255
                val index = index((r shr 3) + 1, (g shr 3) + 1, (b shr 3) + 1)
                addresses[i] = index
                moments[0][index]++
                moments[1][index] += r.toLong()
                moments[2][index] += g.toLong()
                moments[3][index] += b.toLong()
                squares[index] += (r * r + g * g + b * b).toFloat()
            }
            cumulativeMoments()
        }

        private fun cumulativeMoments() {
            for (r in 1..32) {
                val area = Array(4) { LongArray(33) }
                val area2 = FloatArray(33)
                for (g in 1..32) {
                    val line = LongArray(4)
                    var line2 = 0f
                    for (b in 1..32) {
                        val current = index(r, g, b)
                        val previous = current - 1089
                        for (m in 0..3) {
                            line[m] += moments[m][current]
                            area[m][b] += line[m]
                            moments[m][current] = moments[m][previous] + area[m][b]
                        }
                        line2 += squares[current]
                        area2[b] += line2
                        squares[current] = squares[previous] + area2[b]
                    }
                }
            }
        }

        fun quantize(requested: Int): WuQuantizedImage {
            val boxes = Array(requested) { Box() }
            val variance = FloatArray(requested)
            var count = requested
            var next = 0
            var i = 1
            while (i < requested) {
                if (cut(boxes[next], boxes[i])) {
                    variance[next] = if (boxes[next].volume > 1) variance(boxes[next]) else 0f
                    variance[i] = if (boxes[i].volume > 1) variance(boxes[i]) else 0f
                } else {
                    variance[next] = 0f
                    i--
                }
                next = 0
                var largest = variance[0]
                for (k in 1..i) if (variance[k] > largest) {
                    largest = variance[k]
                    next = k
                }
                if (largest <= 0f) {
                    count = i + 1
                    break
                }
                i++
            }
            val tags = IntArray(33 * 33 * 33)
            val palette = (0 until count).map { k ->
                val box = boxes[k]
                for (r in box.low[0] + 1..box.high[0])
                    for (g in box.low[1] + 1..box.high[1])
                        for (b in box.low[2] + 1..box.high[2]) tags[index(r, g, b)] = k
                val weight = volume(box, moments[0])
                if (weight == 0L) 0 else {
                    val red = (volume(box, moments[1]) / weight).toInt()
                    val green = (volume(box, moments[2]) / weight).toInt()
                    val blue = (volume(box, moments[3]) / weight).toInt()
                    (red shl 16) or (green shl 8) or blue
                }
            }
            return WuQuantizedImage(palette, IntArray(addresses.size) { tags[addresses[it]] })
        }

        private fun variance(box: Box): Float {
            val red = volume(box, moments[1]).toFloat()
            val green = volume(box, moments[2]).toFloat()
            val blue = volume(box, moments[3]).toFloat()
            val l = box.low
            val h = box.high
            val xx = squares[index(h[0], h[1], h[2])] - squares[index(h[0], h[1], l[2])] -
                squares[index(h[0], l[1], h[2])] + squares[index(h[0], l[1], l[2])] -
                squares[index(l[0], h[1], h[2])] + squares[index(l[0], h[1], l[2])] +
                squares[index(l[0], l[1], h[2])] - squares[index(l[0], l[1], l[2])]
            return abs(xx - (red * red + green * green + blue * blue) / volume(box, moments[0]).toFloat())
        }

        private fun maximize(box: Box, axis: Int, whole: LongArray): Pair<Float, Int> {
            var best = 0f
            var cut = -1
            val half = box.copy()
            for (position in box.low[axis] + 1 until box.high[axis]) {
                half.high[axis] = position
                // Vol is exactly Bottom+Top in the source: bounded Long sums are exact integers.
                val lower = LongArray(4) { volume(half, moments[it]) }
                if (lower[0] == 0L) continue
                var score = (lower[1].toFloat() * lower[1] + lower[2].toFloat() * lower[2] +
                    lower[3].toFloat() * lower[3]) / lower[0]
                val upper = LongArray(4) { whole[it] - lower[it] }
                if (upper[0] == 0L) continue
                score += (upper[1].toFloat() * upper[1] + upper[2].toFloat() * upper[2] +
                    upper[3].toFloat() * upper[3]) / upper[0]
                if (score > best) {
                    best = score
                    cut = position
                }
            }
            return best to cut
        }

        private fun cut(first: Box, second: Box): Boolean {
            val whole = LongArray(4) { volume(first, moments[it]) }
            val cuts = (0..2).map { maximize(first, it, whole) }
            // Source RED >= GREEN >= BLUE tie preference; first position wins strict score ties.
            val axis = when {
                cuts[0].first >= cuts[1].first && cuts[0].first >= cuts[2].first -> 0
                cuts[1].first >= cuts[0].first && cuts[1].first >= cuts[2].first -> 1
                else -> 2
            }
            if (axis == 0 && cuts[0].second < 0) return false
            for (a in 0..2) {
                second.high[a] = first.high[a]
                second.low[a] = first.low[a]
            }
            first.high[axis] = cuts[axis].second
            second.low[axis] = cuts[axis].second
            return true
        }

        private fun volume(box: Box, moment: LongArray): Long {
            val l = box.low
            val h = box.high
            return moment[index(h[0], h[1], h[2])] - moment[index(h[0], h[1], l[2])] -
                moment[index(h[0], l[1], h[2])] + moment[index(h[0], l[1], l[2])] -
                moment[index(l[0], h[1], h[2])] + moment[index(l[0], h[1], l[2])] +
                moment[index(l[0], l[1], h[2])] - moment[index(l[0], l[1], l[2])]
        }
        private fun index(r: Int, g: Int, b: Int) = r * 1089 + g * 33 + b
    }
}
