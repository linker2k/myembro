// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.library

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import io.github.linker2k.myembro.ui.RenderData

/** Renders a whole design onto a transparent square so the surrounding theme supplies fabric. */
object Thumbnails {
    fun render(data: RenderData, sizePx: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bounds = data.pattern.bounds ?: return bitmap
        val margin = sizePx * 0.08f
        val scale = minOf(
            (sizePx - 2 * margin) / maxOf(1, bounds.width),
            (sizePx - 2 * margin) / maxOf(1, bounds.height),
        )
        canvas.translate(sizePx / 2f, sizePx / 2f)
        canvas.scale(scale, scale)
        canvas.translate(-(bounds.minX + bounds.maxX) / 2f, -(bounds.minY + bounds.maxY) / 2f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            // At least ~1 px so small thumbnails of large designs do not vanish.
            strokeWidth = maxOf(4f, 1.2f / scale)
        }
        for (run in data.runs) {
            paint.color = run.color
            canvas.drawLines(data.stitchLines, run.start * 4, (run.end - run.start) * 4, paint)
        }
        return bitmap
    }
}
