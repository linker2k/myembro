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
                    val found = runCatching { PesInvariants.violations(PesWriter.write(pattern, PesWriteSettings(version, hoop = hoop)), hoop) }
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
}
