// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.lettering

import android.app.Activity
import android.os.Bundle
import android.graphics.*
import android.widget.TextView
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.geometry.SvgPath
import io.github.linker2k.myembro.engine.digitizing.RunningStitch
import io.github.linker2k.myembro.engine.lettering.StitchStyle
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Temporary debug-only generator, compiled into an isolated package by the tools script. */
class S1SewTestActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val status = TextView(this).apply { text = "Generating S1 scrap-fabric samples…" }
        setContentView(status)
        Thread {
            val output = File(filesDir, "s1").apply { mkdirs() }
            try {
                val records = JSONArray()
                val previews = mutableListOf<Bitmap>()
                val thread = EmbThread(0xFF000000.toInt(), "Black")
                for (height in listOf(5, 8, 12)) {
                    val settings = LetteringSettings("ABC 가나다", "noto-sans-kr", height.toDouble(), 1.0,
                        LetteringAlignment.CENTER, false, LetteringCurve.STRAIGHT, 0.0,
                        stitchStyle = StitchStyle.RUNNING)
                    val result = renderLettering(this, settings, thread, HoopSpec.H100)
                    check(!result.unsupported && !result.outsideHoop) { "Unsupported text or hoop overflow at ${height}mm" }
                    val project = Project(HoopSpec.H100, listOf(result.textObject))
                    val stem = "TXT${height.toString().padStart(2, '0')}"
                    val pair = export(output, stem, "ABC 가나다 · ${height}mm · Noto Sans KR · running", project, 1200, 420)
                    records.put(pair.first.put("nominal_height_mm", height).put("text", settings.text).put("font", settings.fontId))
                    previews += pair.second
                }
                for ((stem, path) in listOf(
                    "CIRCLE" to "M0 -50 A50 50 0 1 1 0 50 A50 50 0 1 1 0 -50 Z",
                    "SQUARE" to "M-50 -50 H50 V50 H-50 Z")) {
                    val pattern = RunningStitch.generate(SvgPath.flatten(path), thread = thread)
                    val project = Project(HoopSpec.H100, listOf(StitchObject(stem, stem, pattern)))
                    val pair = export(output, stem, "$stem · 10×10mm · running", project, 600, 600)
                    records.put(pair.first.put("nominal_size_mm", "10×10").put("svg_native_01mm", path))
                    previews += pair.second
                }
                val sheet = Bitmap.createBitmap(1200, 1860, Bitmap.Config.ARGB_8888)
                Canvas(sheet).apply {
                    drawColor(Color.rgb(246, 240, 230))
                    for (i in 0..2) drawBitmap(previews[i], 0f, i * 420f, null)
                    drawBitmap(previews[3], 0f, 1260f, null)
                    drawBitmap(previews[4], 600f, 1260f, null)
                }
                File(output, "overview.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
                File(output, "manifest.json").writeText(JSONObject().put("generator", "actual Android renderLettering / RunningStitch -> Project.flatten -> default PES v1 writer")
                    .put("hoop_mm", "100×100").put("personal_designs", false).put("cases", records).toString(2))
                File(output, "DONE").writeText("5 PES + 5 previews + overview\n")
                runOnUiThread { status.text = "S1 samples ready" }
            } catch (error: Throwable) {
                File(output, "ERROR.txt").writeText(error.stackTraceToString())
                runOnUiThread { status.text = error.toString() }
            }
        }.start()
    }

    private fun export(folder: File, stem: String, label: String, project: Project, width: Int, height: Int): Pair<JSONObject, Bitmap> {
        val source = project.flatten()
        check(!project.exceedsHoop())
        val bytes = PesWriter.write(source, PesWriteSettings()) // Same default path as app editor export.
        val decoded = PesReader.read(bytes)
        check(decoded.stitches == PecWriter.normalize(source).stitches) { "PES round trip mismatch: $stem" }
        File(folder, "$stem.PES").writeBytes(bytes)
        val preview = drawPreview(decoded, label, width, height)
        File(folder, "$stem.png").outputStream().use { preview.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val bounds = checkNotNull(decoded.bounds)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        return JSONObject().put("file", "$stem.PES").put("preview", "$stem.png").put("bytes", bytes.size)
            .put("stitches", decoded.stitchCount).put("width_mm", bounds.width / 10.0).put("height_mm", bounds.height / 10.0)
            .put("sha256", digest).put("style", "RUNNING").put("writer", "PES v1, app default") to preview
    }

    private fun drawPreview(pattern: EmbPattern, label: String, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(246, 240, 230))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(43, 36, 32)
            typeface = resources.getFont(R.font.noto_sans_kr)
            textSize = if (width > 600) 26f else 20f
        }
        canvas.drawText(label, 28f, 40f, paint)
        val b = checkNotNull(pattern.bounds)
        val scale = minOf((width - 100f) / maxOf(1, b.width), (height - 135f) / maxOf(1, b.height))
        val cx = (b.minX.toDouble() + b.maxX) / 2
        val cy = (b.minY.toDouble() + b.maxY) / 2
        fun x(value: Int) = (width / 2 + (value - cx) * scale).toFloat()
        fun y(value: Int) = (height / 2 + 5 + (value - cy) * scale).toFloat()
        paint.strokeWidth = 1.6f
        var previous: Stitch? = null
        for (stitch in pattern.stitches) {
            val old = previous
            if (stitch.command == Command.STITCH) {
                if (old != null) canvas.drawLine(x(old.x), y(old.y), x(stitch.x), y(stitch.y), paint)
                canvas.drawCircle(x(stitch.x), y(stitch.y), 1.9f, paint)
            }
            previous = stitch
        }
        paint.textSize = 18f
        canvas.drawText("${pattern.stitchCount} stitches · ${b.width / 10.0}×${b.height / 10.0}mm · jumps hidden", 28f, height - 20f, paint)
        return bitmap
    }
}
