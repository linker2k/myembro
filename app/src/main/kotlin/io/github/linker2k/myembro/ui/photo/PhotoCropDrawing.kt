// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.photo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/** Same raster in the crop preview and tracing input; no out-of-bounds source rectangle. */
internal fun drawPhotoCrop(canvas: Canvas, bitmap: Bitmap, crop: PhotoCrop,
    width: Float, height: Float, preserveAlpha: Boolean) {
    val save = canvas.save()
    try {
        canvas.clipRect(0f, 0f, width, height)
        // Redwork uses pixel 0 as background; color cleanup already handles alpha/border labels.
        // Preserve the source corner pigment in the margins, with the existing white-paper
        // compositing contract for silhouette/outline input.
        val background = bitmap.getPixel(0, 0)
        if (!preserveAlpha) canvas.drawColor(Color.WHITE)
        canvas.drawRect(0f, 0f, width, height, Paint().apply { color = background })
        val sx = width / crop.width
        val sy = height / crop.height
        canvas.drawBitmap(bitmap, null, RectF(-crop.left * sx, -crop.top * sy,
            (bitmap.width - crop.left) * sx, (bitmap.height - crop.top) * sy),
            Paint(Paint.FILTER_BITMAP_FLAG))
    } finally { canvas.restoreToCount(save) }
}
