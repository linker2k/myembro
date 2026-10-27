// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from DrawingBotV3 image/{PixelDataARGBY,RawData,PixelTargetDarkestArea}.java
// @0e7c900638def19b9a8d16445099f3f1129aee52 (GPL-3.0), Copyright DrawingBotV3 contributors.
package io.github.linker2k.myembro.engine.texture

/** Processing pixels, NOT embroidery coordinates. Inputs are copied; a mask is sampled at integer pixels. */
class SketchRaster(val width: Int, val height: Int, argb: IntArray, clipMask: BooleanArray? = null, private val trace: ((String) -> Unit)? = null) {
    private val reference = argb.copyOf()
    private val pixels = argb.copyOf()
    private val mask = clipMask?.copyOf()
    private val luminance: IntArray
    private var sum = 0.0
    val pixelCount: Int
    internal var onChange: ((Int, Int, Int, Int) -> Unit)? = null

    init {
        require(width > 0 && height > 0 && width.toLong() * height == argb.size.toLong())
        require(mask == null || mask.size == argb.size)
        luminance = IntArray(pixels.size) { perceivedLuminance(pixels[it]) }
        pixelCount = pixels.indices.count { mask == null || mask[it] }
        for (x in 0 until width) for (y in 0 until height) if (drawable(x, y)) sum += luminance[y * width + x]
    }
    fun within(x: Int, y: Int) = x in 0 until width && y in 0 until height
    fun drawable(x: Int, y: Int) = within(x, y) && (mask == null || mask[y * width + x])
    fun luminance(x: Int, y: Int): Int = luminance[y * width + x]
    fun averageLuminance(): Double = sum / pixelCount
    fun referenceArgb(x: Int, y: Int): Int = reference[y * width + x]
    fun copyPixels(): IntArray = pixels.copyOf()

    internal fun setArgb(x: Int, y: Int, argb: Int) {
        val i = y * width + x
        val oldArgb = pixels[i]
        val old = luminance[i]
        pixels[i] = argb
        val next = perceivedLuminance(argb)
        luminance[i] = next
        if (drawable(x, y)) { sum -= old; sum += next }
        onChange?.invoke(x, y, old, next)
        trace?.invoke("PIXEL $x $y $oldArgb $argb")
    }
    internal fun whiten(x: Int, y: Int) {
        val alpha = pixels[y * width + x] and -0x1000000
        setArgb(x, y, alpha or 0xffffff)
    }
    internal fun brighten(x: Int, y: Int, amount: Int) {
        val old = pixels[y * width + x]
        var next = old and -0x1000000
        for (shift in intArrayOf(16, 8, 0)) next = next or ((((old ushr shift) and 255) + amount).coerceIn(0, 255) shl shift)
        setArgb(x, y, next)
    }
    companion object {
        fun perceivedLuminance(argb: Int): Int =
            (0.2126 * ((argb ushr 16) and 255) + 0.7152 * ((argb ushr 8) and 255) + 0.0722 * (argb and 255)).toInt()
    }
}

/** Same tile traversal, integer-cast selection and incremental average updates as the active cache. */
internal class DarkestAreaCache(private val raster: SketchRaster, private val trace: ((String) -> Unit)? = null) {
    private val nominalWidth = maxOf(10, raster.width / 200)
    private val nominalHeight = maxOf(10, raster.height / 200)
    private val sampleWidth = minOf(raster.width, nominalWidth)
    private val sampleHeight = minOf(raster.height, nominalHeight)
    private val nx = maxOf(1, raster.width / sampleWidth)
    private val ny = maxOf(1, raster.height / sampleHeight)
    private val samples = Array(nx) { DoubleArray(ny) }
    private val counts = Array(nx) { IntArray(ny) }
    private var cached: PixelPoint? = null
    init {
        for (tx in 0 until nx) for (ty in 0 until ny) {
            var total = 0.0
            for (x in tx * sampleWidth until endX(tx)) for (y in ty * sampleHeight until endY(ty)) {
                if (raster.drawable(x, y)) { total += raster.luminance(x, y); counts[tx][ty]++ }
            }
            samples[tx][ty] = total / counts[tx][ty]
        }
        raster.onChange = { x, y, old, next ->
            if (raster.drawable(x, y)) {
                val tx = minOf(nx - 1, x / nominalWidth)
                val ty = minOf(ny - 1, y / nominalHeight)
                samples[tx][ty] += (next - old).toDouble() / counts[tx][ty]
                if (cached == PixelPoint(x, y)) cached = null
            }
        }
    }
    private fun endX(tx: Int) = if (tx == raster.width / sampleWidth - 1) raster.width else (tx + 1) * sampleWidth
    private fun endY(ty: Int) = if (ty == raster.height / sampleHeight - 1) raster.height else (ty + 1) * sampleHeight
    fun next(): PixelPoint? {
        cached?.let { return it }
        var best = Double.MAX_VALUE
        var bx = -1; var by = -1
        for (x in 0 until nx) for (y in 0 until ny) {
            trace?.invoke("TILE $x $y ${counts[x][y]} ${java.lang.Double.doubleToLongBits(samples[x][y])}")
            if (counts[x][y] == 0) continue
            val sample = samples[x][y].toInt()
            if (bx == -1 || sample < best) { best = sample.toDouble(); bx = x; by = y }
        }
        if (bx == -1) return null // Defensive extension for a fully clipped raster.
        var point: PixelPoint? = null
        var darkest = Int.MAX_VALUE
        for (x in bx * sampleWidth until endX(bx)) for (y in by * sampleHeight until endY(by)) {
            if (raster.drawable(x, y)) {
                val l = raster.luminance(x, y)
                if (point == null || l < darkest) { point = PixelPoint(x, y); darkest = l }
            }
        }
        cached = point
        return point
    }
    fun close() { raster.onChange = null }
}
