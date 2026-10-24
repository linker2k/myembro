// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.photo

internal data class PhotoCrop(val left: Int, val top: Int, val width: Int, val height: Int)

/** Bounded source-pixel crop, preserving hoop aspect ratio and excluding blank margins. */
internal fun photoCrop(width: Int, height: Int, aspect: Double, zoom: Float, panX: Float, panY: Float): PhotoCrop {
    require(width > 0 && height > 0 && aspect.isFinite() && aspect > 0)
    val z = zoom.coerceIn(1f, 4f)
    val w = (minOf(width.toDouble(), height * aspect) / z).toInt().coerceIn(1, width)
    val h = (w / aspect).toInt().coerceIn(1, height)
    return PhotoCrop(((width - w) * (panX.coerceIn(-1f, 1f) + 1) / 2).toInt(),
        ((height - h) * (panY.coerceIn(-1f, 1f) + 1) / 2).toInt(), w, h)
}

/** Detail controls raster sampling only, never stitch parameters. */
internal fun photoRasterSize(detail: Int, widthMm: Double = 100.0, heightMm: Double = 100.0): Int =
    minOf(intArrayOf(128, 192, 256)[detail.coerceIn(0, 2)],
        kotlin.math.ceil(maxOf(widthMm,heightMm) / 0.2).toInt()).coerceAtLeast(1)

// Photo simplification preset, not an Ink/Stitch stitch parameter. Potrace's area filter is reused.
internal fun photoMinimumArea(detail: Int) = doubleArrayOf(1.0, 0.5, 0.25)[detail.coerceIn(0,2)]


/** Per-channel linear min/max contrast stretch, then the same upstream Wu quantizer retries once. */
internal fun photoContrastStretch(rgb: IntArray): IntArray {
    val low=IntArray(3) { 255 }; val high=IntArray(3)
    for (pixel in rgb) for (c in 0..2) {
        val value=(pixel ushr (16-c*8)) and 255
        low[c]=minOf(low[c],value); high[c]=maxOf(high[c],value)
    }
    return IntArray(rgb.size) { i ->
        var result=0
        for (c in 0..2) {
            val value=(rgb[i] ushr (16-c*8)) and 255
            val stretched=if(high[c]==low[c]) value else (value-low[c])*255/(high[c]-low[c])
            result=result or (stretched shl (16-c*8))
        }
        result
    }
}
