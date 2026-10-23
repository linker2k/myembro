// SPDX-License-Identifier: GPL-3.0-or-later
// Original myembro image preprocessing; not an Ink/Stitch or Potrace source port.
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import io.github.linker2k.myembro.engine.model.EmbThread
import kotlin.math.*

/** Straight (not premultiplied) ARGB pixels. No Android, ImageIO or AWT dependency. */
class RasterImage(val width: Int, val height: Int, argb: IntArray) {
    internal val pixels: IntArray
    init {
        require(width in 1..4096 && height in 1..4096 && width.toLong() * height <= MAX_PIXELS) {
            "Image must contain at most $MAX_PIXELS pixels; downsample before tracing"
        }
        require(argb.size == width * height) { "Pixel count does not match dimensions" }
        pixels = argb.copyOf()
    }
    companion object { const val MAX_PIXELS = 1_048_576 }
}

data class ImageTraceOptions(
    val widthMm: Double,
    val colorCount: Int = 6,
    val minimumRegionAreaMm2: Double = 0.5,
    val simplifyToleranceMm: Double = 0.1,
    val alphaThreshold: Int = 128,
    val matteRgb: Int = 0xFFFFFF,
    val threadPalette: List<EmbThread> = emptyList(),
    val runningMaxWidthMm: Double = 0.6,
    val satinMaxWidthMm: Double = 6.0,
    val minimumColumnAspect: Double = 3.0,
) {
    init {
        require(widthMm.isFinite() && widthMm in 0.1..1000.0)
        require(colorCount in 1..64 && alphaThreshold in 1..255)
        require(minimumRegionAreaMm2.isFinite() && minimumRegionAreaMm2 >= 0)
        require(simplifyToleranceMm.isFinite() && simplifyToleranceMm in 0.0..10.0)
        require(runningMaxWidthMm.isFinite() && runningMaxWidthMm >= 0)
        require(satinMaxWidthMm.isFinite() && satinMaxWidthMm >= runningMaxWidthMm)
        require(minimumColumnAspect.isFinite() && minimumColumnAspect >= 1)
        require(threadPalette.size <= 4096)
    }
}

data class ImageColor(val rgb: Int, val thread: EmbThread, val pixelCount: Int)

/** Palette indices are -1 for transparent pixels. Defensive index access for callers. */
class QuantizedImage internal constructor(
    val width: Int, val height: Int, val colors: List<ImageColor>, internal val labels: IntArray,
) {
    fun colorAt(x: Int, y: Int): Int {
        require(x in 0 until width && y in 0 until height)
        return labels[y * width + x]
    }
    fun copyIndices(): IntArray = labels.copyOf()
}

/** Deterministic population-weighted median cut; nearest catalog thread uses CIELAB ΔE76. */
object ImageQuantizer {
    fun quantize(image: RasterImage, options: ImageTraceOptions): QuantizedImage {
        // 5-bit histogram bounds memory independently of photographic color diversity. Each bin
        // retains full 8-bit channel sums; centroids do not become 5-bit palette colors.
        val counts = IntArray(32768)
        val red = LongArray(32768); val green = LongArray(32768); val blue = LongArray(32768)
        val buckets = IntArray(image.pixels.size) { -1 }
        image.pixels.forEachIndexed { i, pixel ->
            val alpha = pixel ushr 24
            if (alpha < options.alphaThreshold) return@forEachIndexed
            fun channel(shift: Int): Int = (((pixel ushr shift and 255) * alpha +
                (options.matteRgb ushr shift and 255) * (255 - alpha) + 127) / 255)
            val r = channel(16); val g = channel(8); val b = channel(0)
            val key = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
            buckets[i] = key
            counts[key]++; red[key] += r.toLong(); green[key] += g.toLong(); blue[key] += b.toLong()
        }
        data class Bin(val key: Int, val count: Int, val r: Double, val g: Double, val b: Double) {
            fun channel(axis: Int) = when (axis) { 0 -> r; 1 -> g; else -> b }
        }
        val bins = counts.indices.filter { counts[it] > 0 }.map { k ->
            Bin(k, counts[k], red[k].toDouble() / counts[k], green[k].toDouble() / counts[k], blue[k].toDouble() / counts[k])
        }
        if (bins.isEmpty()) return QuantizedImage(image.width, image.height, emptyList(), buckets)
        class Box(val entries: List<Bin>) {
            val population = entries.sumOf { it.count }
            val axis = (0..2).maxBy { a -> entries.maxOf { it.channel(a) } - entries.minOf { it.channel(a) } }
            val range = entries.maxOf { it.channel(axis) } - entries.minOf { it.channel(axis) }
            fun split(): Pair<Box, Box> {
                val sorted = entries.sortedWith(compareBy<Bin> { it.channel(axis) }.thenBy { it.key })
                var sum = 0; var split = 0
                while (split < sorted.lastIndex && sum * 2 < population) { sum += sorted[split].count; split++ }
                split = split.coerceIn(1, sorted.lastIndex)
                return Box(sorted.take(split)) to Box(sorted.drop(split))
            }
            fun rgb(): Int {
                fun average(channel: (Bin) -> Double) = (entries.sumOf { channel(it) * it.count } / population).roundToInt().coerceIn(0, 255)
                return (average { it.r } shl 16) or (average { it.g } shl 8) or average { it.b }
            }
        }
        val boxes = mutableListOf(Box(bins))
        while (boxes.size < options.colorCount) {
            val index = boxes.indices.filter { boxes[it].entries.size > 1 }.maxByOrNull { boxes[it].range * boxes[it].population } ?: break
            val (a, b) = boxes.removeAt(index).split()
            boxes.add(index, b); boxes.add(index, a)
        }
        val palette = options.threadPalette.toList()
        val paletteLab = palette.map { lab(it.color) }
        val centroids = boxes.map { it.rgb() }.distinct().sorted()
        val threads = centroids.map { rgb ->
            if (palette.isEmpty()) EmbThread(0xFF000000.toInt() or rgb)
            else {
                val source = lab(rgb)
                palette[paletteLab.indices.minBy { distance(source, paletteLab[it]) }]
            }
        }
        // Merge regions assigned the same physical catalog thread before component extraction.
        val uniqueThreads = threads.distinct()
        val bucketLabel = IntArray(32768) { -1 }
        val centroidLab = centroids.map(::lab)
        for (bin in bins) {
            val source = lab((bin.r.roundToInt() shl 16) or (bin.g.roundToInt() shl 8) or bin.b.roundToInt())
            val nearest = centroidLab.indices.minBy { distance(source, centroidLab[it]) }
            bucketLabel[bin.key] = uniqueThreads.indexOf(threads[nearest])
        }
        val labels = IntArray(buckets.size) { i -> if (buckets[i] < 0) -1 else bucketLabel[buckets[i]] }
        val used = IntArray(uniqueThreads.size)
        labels.forEach { if (it >= 0) used[it]++ }
        val remap = IntArray(used.size) { -1 }
        val colors = uniqueThreads.indices.filter { used[it] > 0 }.mapIndexed { i, old ->
            remap[old] = i
            ImageColor(uniqueThreads[old].color and 0xFFFFFF, uniqueThreads[old], used[old])
        }
        labels.indices.forEach { if (labels[it] >= 0) labels[it] = remap[labels[it]] }
        return QuantizedImage(image.width, image.height, colors, labels)
    }

    internal fun lab(rgb: Int): DoubleArray {
        fun linear(shift: Int): Double {
            val v = (rgb ushr shift and 255) / 255.0
            return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        val r = linear(16); val g = linear(8); val b = linear(0)
        fun f(v: Double) = if (v > 216.0 / 24389) cbrt(v) else (24389.0 / 27 * v + 16) / 116
        val x = f((0.4124564*r + 0.3575761*g + 0.1804375*b) / 0.95047)
        val y = f(0.2126729*r + 0.7151522*g + 0.0721750*b)
        val z = f((0.0193339*r + 0.1191920*g + 0.9503041*b) / 1.08883)
        return doubleArrayOf(116*y - 16, 500*(x-y), 200*(y-z))
    }
    private fun distance(a: DoubleArray, b: DoubleArray): Double = a.indices.sumOf { (a[it] - b[it]).pow(2) }
}
