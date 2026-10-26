// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.agent

import android.graphics.Canvas
import android.graphics.Paint
import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.ui.RenderData

/** Extracted unchanged from AgentScreens.PreviewPane (editor-style project preview).
 * Centered hoop placement, 90% fit, 1.5px outline, 1.2px round thread lines; no jumps/selection chrome.
 */
internal fun drawProjectPreview(canvas: Canvas, width: Float, height: Float, hoop: HoopSpec,
    data: List<RenderData>, hoopColor: Int, paint: Paint) {
    val w = hoop.width.toFloat(); val h = hoop.height.toFloat()
    val scale = minOf(width / w, height / h) * 0.9f
    canvas.save()
    try {
        canvas.translate(width / 2, height / 2); canvas.scale(scale, scale)
        paint.style = Paint.Style.STROKE; paint.color = hoopColor; paint.strokeWidth = 1.5f / scale
        canvas.drawRect(-w / 2, -h / 2, w / 2, h / 2, paint)
        paint.strokeWidth = 1.2f / scale
        data.forEach { d -> d.runs.forEach { run ->
            paint.color = run.color
            canvas.drawLines(d.stitchLines, run.start * 4, (run.end - run.start) * 4, paint)
        } }
    } finally { canvas.restore() }
}
