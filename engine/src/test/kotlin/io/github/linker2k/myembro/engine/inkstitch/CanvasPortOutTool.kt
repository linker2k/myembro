// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch

import io.github.linker2k.myembro.engine.inkstitch.lettering.InkStitchFont
import io.github.linker2k.myembro.engine.inkstitch.lettering.InkStitchFontInfo
import io.github.linker2k.myembro.engine.inkstitch.lettering.InkStitchLettering
import io.github.linker2k.myembro.engine.io.PesWriter
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Opt-in reproduction of claude-canvas's port output (fonts) for the fixed Ink/Stitch matrix (run by
 * tools/inkstitch_port_out.sh). For each case of the selected features it writes
 * `<out>/<feature>/<output_basename>` and lists every case once in `<out>/results.json` with the
 * matrix hash and commit, in the form the oracle comparator reads. Inputs are only read.
 */
class CanvasPortOutTool {
    @Test fun generate() {
        val matrixPath = System.getenv("MYEMBRO_PORT_MATRIX") ?: return
        val out = File(System.getenv("MYEMBRO_PORT_OUT") ?: return)
        val fixtures = File(System.getenv("MYEMBRO_PORT_FIXTURES") ?: return)
        val features = (System.getenv("MYEMBRO_PORT_FEATURES") ?: "fonts").split(',').map { it.trim() }.toSet()
        val matrixBytes = File(matrixPath).readBytes()
        val matrix = Json.parseToJsonElement(matrixBytes.decodeToString()).jsonObject
        val fonts = HashMap<String, InkStitchFont>()
        val rows = buildJsonArray {
            for (case in matrix.getValue("cases").jsonArray.map { it.jsonObject }) {
                val feature = case.string("feature")
                if (feature !in features) continue
                val id = case.string("id")
                val target = File(out, "$feature/${case.string("output_basename")}")
                target.parentFile.mkdirs()
                target.delete()
                add(buildJsonObject {
                    put("id", id); put("feature", feature); put("expected", case.string("expected"))
                    val input = File(case.string("input"))
                    if (!input.isFile || sha256(input.readBytes()) != case.string("input_sha256")) {
                        put("status", "INPUT_MISMATCH"); return@buildJsonObject
                    }
                    val started = System.nanoTime()
                    try {
                        // SVG import and clone are deferred to the next version (claude/svg-import-archive).
                        require(feature == "fonts") { "feature $feature is not part of this version" }
                        val p = case.getValue("parameters").jsonObject
                        val shape = case.string("shape")
                        val font = fonts.getOrPut(shape) { loadFont(File(fixtures, shape)) }
                        val pattern = InkStitchLettering.render(font, p.string("text"), case.getValue("reference_extent_mm").jsonPrimitive.content.toDouble())
                        val bytes = PesWriter.write(pattern)
                        target.writeBytes(bytes)
                        put("status", "GENERATED"); put("path", target.absolutePath); put("sha256", sha256(bytes))
                        put("stitches", pattern.stitchCount); put("trims", pattern.trimCount); put("color_changes", pattern.colorChangeCount)
                    } catch (e: Exception) {
                        put("status", "ERROR"); put("message", "${e.javaClass.simpleName}: ${e.message}")
                    }
                    put("seconds", (System.nanoTime() - started) / 1e9)
                })
            }
        }
        val manifest = buildJsonObject {
            put("owner", "claude-canvas"); put("branch", "claude/inkfonts")
            put("commit", System.getenv("MYEMBRO_PORT_COMMIT") ?: "unknown")
            put("matrix", matrixPath); put("matrix_sha256", sha256(matrixBytes))
            put("features", JsonArray(features.sorted().map(::JsonPrimitive)))
            put("note", "Stitches come from the engine's temporary generators until the satin/fill/stroke/ties ports " +
                "are merged; see docs/inkstitch-port.md (claude-canvas).")
            put("cases", rows)
        }
        File(out, "results.json").writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), manifest) + "\n")
        val counts = rows.groupingBy { it.jsonObject.string("status") }.eachCount()
        println("claude-canvas port out: $counts -> ${out.absolutePath}")
    }

    /** A font variant folder as Ink/Stitch reads it: `ltr.svg` or every `.svg` in `ltr/`. */
    private fun loadFont(dir: File): InkStitchFont {
        val info = InkStitchFontInfo.parse(File(dir, "font.json").readText())
        val single = File(dir, "ltr.svg")
        val files = if (single.isFile) listOf(single) else File(dir, "ltr").listFiles { f -> f.name.endsWith(".svg") }.orEmpty().sortedBy { it.name }
        require(files.isNotEmpty()) { "no ltr variant in $dir" }
        return InkStitchFont.parse(files.map { it.inputStream() }, info)
    }

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
