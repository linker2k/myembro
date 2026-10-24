// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.sewout

import io.github.linker2k.myembro.engine.edit.FontKind
import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.edit.Project
import io.github.linker2k.myembro.engine.edit.TextObject
import io.github.linker2k.myembro.engine.edit.Transform
import io.github.linker2k.myembro.engine.inkstitch.lettering.InkStitchFont
import io.github.linker2k.myembro.engine.inkstitch.lettering.InkStitchFontRegistry
import io.github.linker2k.myembro.engine.inkstitch.lettering.InkStitchLettering
import io.github.linker2k.myembro.engine.io.PesVersion
import io.github.linker2k.myembro.engine.io.PesWriteSettings
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.model.EmbPattern
import java.io.File
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * AGENTS.md P1–P5, P7, P8 measured on the written PES bytes (own PEC decode, not the writer's
 * guard) for every APK-bundled embroidery font at both ends of its catalog height range, through
 * the engine renderer and through [Project.flatten] with one and two text objects, in PES v1 and
 * v6. P6 half_stitch is the lettering port's upstream lock. Hoop overflow is not the font's fault
 * (counted separately), so each case uses the longest sample text that fits the 100mm hoop.
 */
class BundledFontPesInvariantsTest {
    private val assets = File("../app/src/main/assets/fonts")
    private val hoop = HoopSpec.H100
    private val texts = listOf("Hamburg", "Ham", "H")

    @Test fun bundledFontsWritePesWithinInvariants() {
        val catalog = Json.parseToJsonElement(File(assets, "inkstitch-catalog.json").readText())
            .jsonObject.getValue("fonts").jsonArray.map { it.jsonObject }
            .filter { it["bundled"]?.jsonPrimitive?.booleanOrNull == true }
        assertEquals(5, catalog.size)
        val violations = ArrayList<String>()
        var cases = 0
        for (f in catalog) {
            val id = f.getValue("id").jsonPrimitive.content
            val dir = File(assets, "bundled/$id")
            val font = File(dir, "ltr.svg").inputStream().use { InkStitchFont.parse(it, File(dir, "font.json").readText()) }
            InkStitchFontRegistry.put(id, font)
            for (h in listOf("recommendedMinHeightMm", "recommendedMaxHeightMm").map { f.getValue(it).jsonPrimitive.double }) {
                val text = texts.first { t -> InkStitchLettering.render(font, t, h).fits(hoop.width - 1, hoop.height - 1) }
                val line = TextObject(id = "a", text = text, fontId = id, heightMm = h, spacing = 0.0, fontKind = FontKind.INKSTITCH, singleLine = false)
                val routes = buildMap {
                    put("render", InkStitchLettering.render(font, text, h))
                    put("flatten", Project(hoop, listOf(line)).flatten())
                    // Two objects: flatten joins them with trim + jump, the other zero-length source.
                    val second = line.copy(id = "b", transform = Transform(y = h * 12.0))
                    val two = Project(hoop, listOf(line, second)).flatten()
                    if (two.fits(hoop.width - 1, hoop.height - 1)) put("flatten×2", two)
                }
                for ((route, pattern) in routes) for (version in PesVersion.entries) {
                    cases++
                    val found = runCatching { violations(PesWriter.write(pattern, PesWriteSettings(version, hoop = hoop))) }
                        .getOrElse { listOf("write failed: ${it.message}") }
                    found.forEach { violations += "$id ${h}mm \"$text\" $route $version: $it" }
                }
            }
        }
        println("bundled-font PES invariants: $cases cases, ${violations.size} violations")
        assertEquals(emptyList(), violations)
    }

    private fun EmbPattern.fits(maxX: Int, maxY: Int): Boolean {
        val s = stitches.filter { it.command == Command.STITCH || it.command == Command.JUMP }
        return s.maxOf { it.x } - s.minOf { it.x } <= maxX && s.maxOf { it.y } - s.minOf { it.y } <= maxY
    }

    private fun violations(bytes: ByteArray): List<String> {
        val out = ArrayList<String>()
        val moves = boundedPecStitches(bytes).filter { it.command == Command.STITCH || it.command == Command.JUMP }
        val minX = moves.minOf { it.x }; val minY = moves.minOf { it.y }
        val maxX = moves.maxOf { it.x }; val maxY = moves.maxOf { it.y }
        if (minX != 0 || minY != 0) out += "P1 min=($minX,$minY)"
        if (maxX > hoop.width - 1 || maxY > hoop.height - 1) out += "P2 max=($maxX,$maxY)"
        val p = (0..3).sumOf { (bytes[8 + it].toInt() and 255) shl (8 * it) }
        fun u16(i: Int) = (bytes[i].toInt() and 255) or ((bytes[i + 1].toInt() and 255) shl 8)
        if (u16(p + 520) != maxX - minX || u16(p + 522) != maxY - minY)
            out += "P3 header=${u16(p + 520)}x${u16(p + 522)} actual=${maxX - minX}x${maxY - minY}"
        var x = 0; var y = 0; var run = 0; var worstRun = 0; var zero = 0; var longest = 0.0
        for (s in moves) {
            val length = hypot((s.x - x).toDouble(), (s.y - y).toDouble())
            if (length == 0.0) zero++
            if (s.command == Command.STITCH) {
                run = if (length == 0.0) run + 1 else 1
                worstRun = maxOf(worstRun, run)
                longest = maxOf(longest, length)
            } else run = 0
            x = s.x; y = s.y
        }
        if (zero > 0) out += "P4 zero-length moves=$zero"
        if (worstRun > 2) out += "P5 same-point run=$worstRun"
        val cells = moves.filter { it.command == Command.STITCH }.groupingBy { it.x / 5 to it.y / 5 }.eachCount()
        cells.values.maxOrNull()?.takeIf { it > 15 }?.let { out += "P7 cell=$it" }
        if (longest > 120.0) out += "P8 longest=${longest / 10}mm"
        return out
    }
}
