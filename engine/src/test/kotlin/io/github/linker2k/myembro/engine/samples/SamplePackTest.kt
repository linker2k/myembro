// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.samples

import io.github.linker2k.myembro.engine.io.PesReader
import io.github.linker2k.myembro.engine.model.Command
import java.io.File
import java.security.MessageDigest
import kotlin.math.hypot
import kotlin.test.*
import kotlinx.serialization.json.*

class SamplePackTest {
    private val assets = listOf(File("../app/src/main/assets/samples"), File("app/src/main/assets/samples"))
        .first { it.isDirectory }
    private val manifest = Json.parseToJsonElement(File(assets, "manifest.json").readText()).jsonObject
    private val samples = manifest.getValue("samples").jsonArray.map { it.jsonObject }

    @Test fun everyBundledDesignMatchesItsInventoryAndFits100mmHoop() {
        assertTrue(samples.size >= 20)
        assertEquals(samples.size, samples.map { it.getValue("file").jsonPrimitive.content }.distinct().size)
        assertEquals(samples.map { it.getValue("file").jsonPrimitive.content }.toSet(),
            assets.listFiles()!!.filter { it.name.startsWith("sample_") && it.extension == "pes" }.map { it.name }.toSet())
        for (entry in samples) {
            val file = File(assets, entry.getValue("file").jsonPrimitive.content)
            val bytes = file.readBytes()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(entry.getValue("sha256").jsonPrimitive.content, hash, file.name)
            val pattern = PesReader.read(bytes)
            assertTrue(pattern.warnings.isEmpty(), "${file.name}: ${pattern.warnings}")
            assertEquals(entry.getValue("stitches").jsonPrimitive.int, pattern.stitchCount, file.name)
            assertEquals(entry.getValue("colors").jsonPrimitive.int, pattern.colorBlocks.size, file.name)
            assertTrue(pattern.stitchCount > 0, file.name)
            val b = assertNotNull(pattern.bounds, file.name)
            assertTrue(b.minX >= -500 && b.maxX <= 500 && b.minY >= -500 && b.maxY <= 500, "${file.name}: $b")
            assertEquals(Command.END, pattern.stitches.last().command, file.name)
        }
    }

    @Test fun travelIsTrimmedAndSewnMovesAreAtMostFourMillimeters() {
        for (entry in samples) {
            val name = entry.getValue("file").jsonPrimitive.content
            val p = PesReader.read(File(assets, name).readBytes())
            var trimmed = true
            p.stitches.forEachIndexed { i, s ->
                when (s.command) {
                    Command.TRIM -> trimmed = true
                    Command.JUMP -> assertTrue(trimmed, "$name: untrimmed jump at $i")
                    Command.STITCH -> {
                        if (i > 0) {
                            val previous = p.stitches[i-1]
                            assertTrue(hypot(s.x.toDouble()-previous.x, s.y.toDouble()-previous.y) <= 40.01,
                                "$name: needle-down move longer than 4 mm at $i")
                        }
                        trimmed = false
                    }
                    else -> Unit
                }
            }
        }
    }

    @Test fun licensesAndRequiredTechniquesArePresent() {
        val names = samples.map { it.getValue("file").jsonPrimitive.content }
        val notices = File(assets, "LICENSES.md").readText()
        assertTrue(File(assets, "CC0-1.0.txt").readText().contains("CC0"))
        for (entry in samples) {
            assertTrue(entry.getValue("license").jsonPrimitive.content in setOf("CC0-1.0", "CC0-1.0 AND LicenseRef-Hershey", "MIT"))
            assertTrue(notices.contains(entry.getValue("file").jsonPrimitive.content))
            assertTrue(entry.getValue("source").jsonPrimitive.content.isNotBlank())
        }
        for (required in listOf("heart", "star", "snowflake", "mandala", "leaf", "cloud", "petal_ring", "frame_square", "frame_circle", "fill_", "monogram_", "korean_embroidery", "korean_love")) {
            assertTrue(names.any { it.contains(required) }, required)
        }
        assertTrue(samples.any { it.getValue("colors").jsonPrimitive.int == 3 })
        assertTrue(samples.any { it.getValue("colors").jsonPrimitive.int == 4 })
        assertTrue(samples.any { it.getValue("license").jsonPrimitive.content == "MIT" })
    }
}
