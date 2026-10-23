// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.lettering

import io.github.linker2k.myembro.engine.lettering.ValidatedHeightRange
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest
import kotlin.test.*

class LetteringHeightLimitsTest {
    private val asset = File("src/main/assets/${LetteringHeightLimits.ASSET}").readText()
    private val limits = LetteringHeightLimits.decode(asset)
    private val rows = Json.parseToJsonElement(asset).jsonObject.getValue("profiles").jsonArray
    private fun range(id: String): ValidatedHeightRange {
        val row = rows.single { it.jsonObject.getValue("fontId").jsonPrimitive.content == id }.jsonObject
        return limits.outlineRanges(id, row.getValue("fontSha256").jsonPrimitive.content + ":glyph-extractor-v3").single()
    }

    @Test fun everyRangeMatchesSweepAndAppCap() {
        val source = File("../docs/s3-ttf-fill-sweep/summary.json")
        val root = Json.parseToJsonElement(asset).jsonObject
        val sha = MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(root.getValue("sourceSha256").jsonPrimitive.content, sha)
        val profiles = Json.parseToJsonElement(source.readText()).jsonObject.getValue("profiles").jsonArray
        assertEquals(33, rows.size)
        for (entry in profiles) {
            val p = entry.jsonObject
            val expected = p.getValue("longest_sampled_passing_interval").jsonObject
            val actual = range(p.getValue("font_id").jsonPrimitive.content)
            assertEquals(expected.getValue("min_mm").jsonPrimitive.double, actual.minMm)
            assertEquals(minOf(60.0, expected.getValue("max_mm").jsonPrimitive.double), actual.maxMm)
        }
    }

    @Test fun restrictiveAndFractionalFontBoundsArePreserved() {
        assertEquals(ValidatedHeightRange(26.25, 60.0), range("noto-sans-kr"))
        assertEquals(ValidatedHeightRange(5.0, 6.0), range("east-sea-dokdo"))
        assertEquals(ValidatedHeightRange(5.0, 49.0), range("nanum-pen-script"))
        assertEquals(ValidatedHeightRange(5.0, 40.0), range("dongle"))
        assertEquals(60.0, range("jua").maxMm)
    }

    @Test fun changedOrUnknownFontCannotInheritBounds() {
        assertTrue(limits.outlineRanges("jua", "0".repeat(64) + ":glyph-extractor-v3").isEmpty())
        assertTrue(limits.outlineRanges("private-font", "0".repeat(64)).isEmpty())
    }

    @Test fun capAlsoAppliesToSatinAndDropsRangesEntirelyAboveSixty() {
        assertEquals(listOf(ValidatedHeightRange(10.0, 60.0)), LetteringHeightLimits.capRanges(listOf(
            ValidatedHeightRange(10.0, 90.0), ValidatedHeightRange(61.0, 90.0))))
    }

    @Test fun malformedOrDuplicateDataIsRejected() {
        assertFailsWith<IllegalArgumentException> { LetteringHeightLimits.decode(asset.replace("\"maxMm\": 60.0", "\"maxMm\": 90.0")) }
        val root = Json.parseToJsonElement(asset).jsonObject
        val duplicate = JsonObject(root + ("profiles" to JsonArray(rows + rows.first())))
        assertFailsWith<IllegalArgumentException> { LetteringHeightLimits.decode(duplicate.toString()) }
    }
}
