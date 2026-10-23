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
internal fun photoRasterSize(detail: Int) = intArrayOf(128, 192, 256)[detail.coerceIn(0, 2)]
