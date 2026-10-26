// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.agent

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import io.github.linker2k.myembro.engine.edit.Project
import io.github.linker2k.myembro.ui.RenderData
import io.github.linker2k.myembro.ui.theme.myembroColorScheme
import androidx.compose.ui.graphics.toArgb
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Android-only relay image renderer. Caller supplies one captured current-project snapshot.
 * Pure read: no session commits, writers, files, selection or camera mutation. Call off the UI thread.
 */
object ProjectPreviewRenderer {
    /** Returns one MCP image content block, ready for the relay's result.content array. */
    fun renderPreview(project: Project, widthPx: Int): JsonObject {
        require(widthPx in 256..1024) { "widthPx must be an integer from 256 through 1024" }
        // Match the existing project preview: visible placed patterns, in project sewing order.
        // Do not flatten/export/recenter: these operations could change placement or stitch data.
        val data = project.objects.filter { it.visible }.map { RenderData(it.placedPattern()) }
        val bitmap = Bitmap.createBitmap(widthPx, widthPx, Bitmap.Config.ARGB_8888)
        try {
            bitmap.setHasAlpha(false)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE) // Contract requires opaque white, independently of app theme.
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
            drawProjectPreview(canvas, widthPx.toFloat(), widthPx.toFloat(), project.hoop, data,
                myembroColorScheme(dark = false).outline.toArgb(), paint)
            val png = ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "PNG encoding failed" }
                output.toByteArray()
            }
            return buildJsonObject {
                put("type", "image")
                put("data", Base64.encodeToString(png, Base64.NO_WRAP))
                put("mimeType", "image/png")
            }
        } finally { bitmap.recycle() }
    }
}
