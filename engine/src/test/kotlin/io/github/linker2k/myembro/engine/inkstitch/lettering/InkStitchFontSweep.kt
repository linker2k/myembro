// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.lettering

import io.github.linker2k.myembro.engine.sewout.DesignOrigin
import io.github.linker2k.myembro.engine.sewout.FindingCode
import io.github.linker2k.myembro.engine.sewout.SewoutCheck
import io.github.linker2k.myembro.engine.sewout.SewoutOptions
import io.github.linker2k.myembro.engine.sewout.Severity
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.test.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Opt-in release sweep: every font × heights from its recorded minimum to min(recorded maximum, cap)
 * in fixed steps (both ends included) × sample texts, judged by [SewoutCheck] as app-generated
 * output. A font's allowed range is the longest run of consecutive passing heights; a failure in
 * the middle splits the range. Hoop findings depend on text length and the chosen hoop, not on the
 * font, so they are counted but do not narrow the range.
 *
 * Fonts run in parallel; each font's result is written to `fonts/<id>.json` as soon as it is done,
 * and `sweep.json` + `failures.tsv` merge every per-font file present (so reruns can resume).
 * INKSTITCH_FONTS=<embroidery-fonts>/src INKSTITCH_SWEEP=<out dir> [INKSTITCH_SWEEP_FONTS=a,b]
 * [INKSTITCH_SWEEP_STEP=2] [INKSTITCH_SWEEP_CAP=60] [INKSTITCH_SWEEP_THREADS=8]
 */
class InkStitchFontSweep {
    private val texts = listOf("Hamburg", "Jung Min", "2024", "가나다")
    private val placement = setOf(FindingCode.HOOP_EXCEEDED, FindingCode.MACHINE_AREA_EXCEEDED)

    @Test fun sweep() {
        val src = System.getenv("INKSTITCH_FONTS")?.let(::File) ?: return
        val out = System.getenv("INKSTITCH_SWEEP")?.let(::File) ?: return
        val only = System.getenv("INKSTITCH_SWEEP_FONTS")?.split(',')?.toSet()
        val step = System.getenv("INKSTITCH_SWEEP_STEP")?.toDouble() ?: 2.0
        val cap = System.getenv("INKSTITCH_SWEEP_CAP")?.toDouble() ?: 60.0
        val threads = System.getenv("INKSTITCH_SWEEP_THREADS")?.toInt() ?: 8
        val perFont = File(out, "fonts").apply { mkdirs() }
        val dirs = src.listFiles().orEmpty().filter { File(it, "ltr.svg").isFile && (only == null || it.name in only) }.sortedBy { it.name }
        val pool = Executors.newFixedThreadPool(threads)
        for (dir in dirs) pool.submit {
            val started = System.nanoTime()
            runCatching { sweepFont(dir, step, cap) }.onSuccess { (id, result, failures) ->
                val seconds = (System.nanoTime() - started) / 1e9
                File(perFont, "$id.failures.tsv").writeText(failures.joinToString("\n"))
                File(perFont, "$id.json").writeText(JsonObject(result + ("seconds" to JsonPrimitive(seconds))).toString())
                println("sweep font $id ${"%.1f".format(seconds)}s")
            }.onFailure { println("sweep font ${dir.name} failed: $it") }
        }
        pool.shutdown()
        pool.awaitTermination(6, TimeUnit.HOURS)
        merge(out, perFont)
    }

    private fun sweepFont(dir: File, step: Double, cap: Double): Triple<String, JsonObject, List<String>> {
        val id = "inkstitch-" + dir.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        val font = File(dir, "ltr.svg").inputStream().use { InkStitchFont.parse(it, File(dir, "font.json").readText()) }
        val low = font.minHeightMm ?: error("no reference glyph")
        val high = min(font.maxHeightMm ?: low, cap)
        val heights = if (low > cap) emptyList() else
            (generateSequence(low) { it + step }.takeWhile { it < high - 1e-9 }.toList() + high).distinct()
        // Only texts the font can sew: the app does not offer a font for text it lacks.
        val usable = texts.filter { t -> t.filterNot(Char::isWhitespace).all { c ->
            val cased = when (font.info.letterCase) { "upper" -> c.uppercaseChar(); "lower" -> c.lowercaseChar(); else -> c }
            cased.toString() in font.glyphs } }
        val failures = ArrayList<String>()
        var hoopOnly = 0
        val passes = heights.map { h ->
            usable.isNotEmpty() && usable.map { t ->
                val codes = runCatching {
                    SewoutCheck.check(InkStitchLettering.render(font, t, h), options = SewoutOptions(origin = DesignOrigin.APP_GENERATED))
                        .findings.filter { it.severity == Severity.BLOCKER }.map { it.code.name }.toSet()
                }.getOrElse { e -> setOf("ERROR:${e.javaClass.simpleName}: ${e.message.orEmpty().take(90).replace(Regex("[\\t\\n,]"), " ")}") }
                val blocking = codes.filter { c -> placement.none { it.name == c } }
                if (blocking.isEmpty() && codes.isNotEmpty()) hoopOnly++
                if (blocking.isNotEmpty()) failures += listOf(dir.name, "%.1f".format(h), t, blocking.joinToString(",")).joinToString("\t")
                blocking.isEmpty()
            }.all { it }
        }
        // Longest run of consecutive passing heights.
        var best: IntRange? = null
        var start = -1
        for (i in 0..heights.size) {
            if (i < heights.size && passes[i]) { if (start < 0) start = i; continue }
            if (start >= 0) { val r = start until i; if (best == null || r.count() > best.count()) best = r; start = -1 }
        }
        return Triple(id, buildJsonObject {
            put("folder", dir.name)
            put("recordedMinMm", low); put("recordedMaxMm", font.maxHeightMm ?: low); put("capMm", cap); put("stepMm", step)
            put("heightsMm", buildJsonArray { heights.forEach { add(JsonPrimitive(it)) } })
            put("pass", buildJsonArray { passes.forEach { add(JsonPrimitive(it)) } })
            put("texts", buildJsonArray { usable.forEach { add(JsonPrimitive(it)) } })
            put("hoopOnlyFindings", hoopOnly)
            best?.let { put("allowedMinMm", heights[it.first]); put("allowedMaxMm", heights[it.last]) }
        }, failures)
    }

    private fun merge(out: File, perFont: File) {
        val results = perFont.listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }
        val merged = buildJsonObject {
            for (f in results) put(f.name.removeSuffix(".json"), Json.parseToJsonElement(f.readText()).jsonObject)
        }
        val failures = perFont.listFiles { f -> f.name.endsWith(".failures.tsv") }.orEmpty().sortedBy { it.name }
            .flatMap { it.readLines().filter(String::isNotBlank) }
        File(out, "sweep.json").writeText(merged.toString() + "\n")
        File(out, "failures.tsv").writeText((listOf("font\theightMm\ttext\tcodes") + failures).joinToString("\n") + "\n")
        val byCode = failures.flatMap { it.split('\t')[3].split(',') }.groupingBy { it }.eachCount()
        println("sweep: ${merged.size} fonts, ${failures.size} failing combinations, by code $byCode -> ${out.absolutePath}")
    }
}
