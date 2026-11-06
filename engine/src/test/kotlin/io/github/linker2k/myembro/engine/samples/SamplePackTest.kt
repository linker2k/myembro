// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.samples

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.io.PesReader
import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.sewout.DesignOrigin
import io.github.linker2k.myembro.engine.sewout.PecViewportCheck
import io.github.linker2k.myembro.engine.sewout.PesOutputSafety
import java.io.File
import java.security.MessageDigest
import kotlin.test.*
import kotlinx.serialization.json.*

/** The starter pack installed by tools/samples/install_pack.py. */
class SamplePackTest {
    private fun dir(path: String) = listOf(File("../$path"), File(path)).first { it.exists() }
    private val assets = dir("app/src/main/assets/samples")
    private val manifest = Json.parseToJsonElement(File(assets, "manifest.json").readText()).jsonObject
    private val samples = manifest.getValue("samples").jsonArray.map { it.jsonObject }
    private val catalog = Json.parseToJsonElement(dir("tools/samples/catalog.json").readText()).jsonObject
        .getValue("designs").jsonArray.map { it.jsonObject }
    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.mm(key: String) = getValue(key).jsonArray.map { it.jsonPrimitive.int }
    // The only hoops the pack targets (catalog hoopMm); anything else is a catalog error.
    private val hoops = mapOf(listOf(100, 100) to HoopSpec.H100, listOf(130, 180) to HoopSpec.H130x180)

    @Test fun everyBundledDesignMatchesItsInventory() {
        val files = samples.map { it.string("file") }
        assertTrue(samples.size >= 130, "${samples.size}")
        assertEquals(files.size, files.distinct().size)
        assertEquals(files.toSet(), assets.listFiles()!!.filter { it.name.startsWith("sample_") && it.extension == "pes" }.map { it.name }.toSet())
        val byFile = catalog.associateBy { it.string("file") }
        for (entry in samples) {
            val name = entry.string("file")
            val design = assertNotNull(byFile[name], name)
            assertEquals(design.string("title"), entry.string("title"), name)
            assertEquals(design.string("titleEn"), entry.string("titleEn"), name)
            assertEquals(design.mm("hoopMm"), entry.mm("hoopMm"), name)
            val bytes = File(assets, name).readBytes()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(entry.string("sha256"), hash, name)
            val pattern = PesReader.read(bytes)
            assertTrue(pattern.warnings.isEmpty(), "$name: ${pattern.warnings}")
            assertEquals(entry.getValue("stitches").jsonPrimitive.int, pattern.stitchCount, name)
            assertEquals(entry.getValue("colors").jsonPrimitive.int, pattern.colorBlocks.size, name)
            assertEquals(Command.END, pattern.stitches.last().command, name)
        }
        assertEquals(samples.size, samples.map { it.string("title") }.distinct().size, "Korean names must be unique")
        assertEquals(samples.size, samples.map { it.string("titleEn") }.distinct().size, "English names must be unique")
    }

    /** Same encoded-PES gate as saving and USB: P1–P8 and the PEC viewport, against the design's own hoop. */
    @Test fun everyBundledDesignPassesTheSewingGateForItsHoop() {
        for (entry in samples) {
            val name = entry.string("file")
            val hoop = assertNotNull(hoops[entry.mm("hoopMm")], name)
            val bytes = File(assets, name).readBytes()
            val decoded = PesReader.read(bytes)
            val report = PesOutputSafety.check(bytes, hoop, decoded, origin = DesignOrigin.APP_GENERATED).report
            assertTrue(report.analysisComplete && !report.hasBlockingIssues, "$name: $report")
            assertTrue(PecViewportCheck.check(bytes, hoop).valid, name)
            // Fit box from the catalog, +1mm as measured by the pack verifier (verification-round2.log).
            val fit = entry.mm("fitMm")
            val b = assertNotNull(decoded.bounds, name)
            assertTrue(b.width <= fit[0] * 10 + 10 && b.height <= fit[1] * 10 + 10, "$name: $b vs $fit")
        }
    }

    /** Home shows the manifest's `order` 1..6, all for the 100mm hoop (user decision 2026-11-01). */
    @Test fun homeRowIsSixFixedDesignsThatFitThe100mmHoop() {
        val home = samples.filter { "order" in it }.sortedBy { it.getValue("order").jsonPrimitive.int }
        assertEquals((1..6).toList(), home.map { it.getValue("order").jsonPrimitive.int })
        home.forEach { assertEquals(listOf(100, 100), it.mm("hoopMm"), it.string("file")) }
    }

    @Test fun licensesAndProvenanceCoverThePack() {
        val notices = File(assets, "LICENSES.md").readText()
        assertTrue(File(assets, "CC0-1.0.txt").readText().contains("CC0"))
        assertTrue("## Starter designs 2026-10 — CC0-1.0" in notices)
        assertTrue("1998.84.50A–F" in notices)
        val provenance = Json.parseToJsonElement(dir("docs/samples/provenance.json").readText()).jsonObject
            .getValue("designs").jsonArray.map { it.jsonObject }.associateBy { it.string("file") }
        for (entry in samples) {
            val name = entry.string("file")
            assertEquals("CC0-1.0", entry.string("license"), name)
            val record = assertNotNull(provenance[name], name)
            assertTrue(record.string("prompt").isNotBlank() && record.string("model").isNotBlank(), name)
            assertFalse("/tmp/" in record.toString() || "/home/" in record.toString(), "$name: local paths in provenance")
            if (record.string("id").startsWith("F")) assertTrue("Smithsonian" in record.string("source"), name)
        }
    }
}
