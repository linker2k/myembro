// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.lettering

import io.github.linker2k.myembro.engine.sewout.FindingCode
import io.github.linker2k.myembro.engine.sewout.SewoutCheck
import io.github.linker2k.myembro.engine.sewout.Severity
import java.io.File
import kotlin.test.Test
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Opt-in release sweep: every font × its recorded min/mid/max height × sample texts, judged by
 * [SewoutCheck]. A font's allowed range is the widest run of consecutive passing heights; fonts
 * with none are dropped. Hoop findings depend on text length and the chosen hoop, not on the font,
 * so they are counted but do not narrow the range.
 * INKSTITCH_FONTS=<embroidery-fonts>/src INKSTITCH_SWEEP=<out dir> [INKSTITCH_SWEEP_FONTS=a,b]
 * Writes sweep.json (read by tools/inkstitch_catalog.py) and failures.tsv.
 */
class InkStitchFontSweep {
    private val texts = listOf("Hamburg", "Jung Min", "2024", "가나다")
    private val placement = setOf(FindingCode.HOOP_EXCEEDED, FindingCode.MACHINE_AREA_EXCEEDED)

    @Test fun sweep() {
        val src = System.getenv("INKSTITCH_FONTS")?.let(::File) ?: return
        val out = System.getenv("INKSTITCH_SWEEP")?.let(::File) ?: return
        val only = System.getenv("INKSTITCH_SWEEP_FONTS")?.split(',')?.toSet()
        out.mkdirs()
        val failures = arrayListOf(listOf("font", "heightMm", "text", "codes").joinToString("\t"))
        val fonts = buildJsonObject {
            for (dir in src.listFiles().orEmpty().filter { File(it, "ltr.svg").isFile && (only == null || it.name in only) }.sortedBy { it.name }) {
                val font = runCatching { File(dir, "ltr.svg").inputStream().use { InkStitchFont.parse(it, File(dir, "font.json").readText()) } }.getOrNull() ?: continue
                val min = font.minHeightMm ?: continue
                val max = font.maxHeightMm ?: continue
                val heights = listOf(min, (min + max) / 2, max).distinct()
                // Only texts the font can sew: the app does not offer a font for text it lacks.
                val usable = texts.filter { t -> t.filterNot(Char::isWhitespace).all { c ->
                    val cased = when (font.info.letterCase) { "upper" -> c.uppercaseChar(); "lower" -> c.lowercaseChar(); else -> c }
                    cased.toString() in font.glyphs } }
                if (usable.isEmpty()) continue
                var hoopOnly = 0
                val passes = heights.map { h ->
                    usable.all { t ->
                        val codes = runCatching {
                            val report = SewoutCheck.check(InkStitchLettering.render(font, t, h))
                            report.findings.filter { it.severity == Severity.BLOCKER }.map { it.code }.toSet()
                        }.getOrElse { e -> setOf("ERROR:${e.javaClass.simpleName}") }
                        val blocking = codes.filter { it !in placement }
                        if (blocking.isEmpty() && codes.isNotEmpty()) hoopOnly++
                        if (blocking.isNotEmpty()) failures += listOf(dir.name, "%.1f".format(h), t, blocking.joinToString(",")).joinToString("\t")
                        blocking.isEmpty()
                    }
                }
                // Widest run of consecutive passing heights.
                var best: IntRange? = null
                var start = -1
                for (i in 0..heights.size) {
                    if (i < heights.size && passes[i]) { if (start < 0) start = i; continue }
                    if (start >= 0) { val r = start until i; if (best == null || r.count() > best.count()) best = r; start = -1 }
                }
                put("inkstitch-" + dir.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-'), buildJsonObject {
                    put("folder", dir.name)
                    put("heightsMm", buildJsonArray { heights.forEach { add(JsonPrimitive(it)) } })
                    put("pass", buildJsonArray { passes.forEach { add(JsonPrimitive(it)) } })
                    put("texts", buildJsonArray { usable.forEach { add(JsonPrimitive(it)) } })
                    put("hoopOnlyFindings", hoopOnly)
                    best?.let { put("allowedMinMm", heights[it.first]); put("allowedMaxMm", heights[it.last]) }
                })
            }
        }
        File(out, "sweep.json").writeText(fonts.toString() + "\n")
        File(out, "failures.tsv").writeText(failures.joinToString("\n") + "\n")
        println("sweep: ${fonts.size} fonts, ${failures.size - 1} failing combinations -> ${out.absolutePath}")
    }
}
