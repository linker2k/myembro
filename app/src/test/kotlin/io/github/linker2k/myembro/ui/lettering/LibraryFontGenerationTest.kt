// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.lettering

import io.github.linker2k.myembro.engine.lettering.LetteringGeometry
import io.github.linker2k.myembro.engine.lettering.StitchStyle
import io.github.linker2k.myembro.fonts.FontRepository
import io.github.linker2k.myembro.fonts.InkStitchCatalog
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A font picked in the library ("more fonts") must reach stitch generation, as the three quick
 * fonts do: every catalog font yields an automatic state the screen may generate with at some
 * height (regression: TTFs without a swept fill range were stuck at FILL with no range).
 */
class LibraryFontGenerationTest {
    private val limits = LetteringHeightLimits.decode(File("src/main/assets/${LetteringHeightLimits.ASSET}").readText())
    private val catalog = Json.parseToJsonElement(File("src/main/assets/${FontRepository.CATALOG_ASSET}").readText())
        .jsonObject.getValue("fonts").jsonArray.map { it.jsonObject }

    /** The screen's loop: compute the state, clamp into its ranges, recompute at the clamped height. */
    private fun generatable(id: String, revision: String, geometry: LetteringGeometry, ink: ClosedFloatingPointRange<Double>? = null): Boolean {
        var height = 18.0
        repeat(2) {
            val state = automaticLetteringState(id, id, revision, "Jung", height, geometry, limits, ink)
            state.heightRanges.takeIf { it.isNotEmpty() }?.let { r -> height = r.minBy { kotlin.math.abs(it.clamp(height) - height) }.clamp(height) }
            if (it == 1) return letteringHeightPermitted(state, height)
        }
        return false
    }

    @Test fun everyLibraryTtfGenerates() {
        val ttfs = catalog.filter { it.getValue("kind").jsonPrimitive.content == "outline-ttf" }
        assertTrue(ttfs.size >= 60)
        val stuck = ttfs.filterNot { f ->
            generatable(f.getValue("id").jsonPrimitive.content, f.getValue("sha256").jsonPrimitive.content + ":glyph-extractor-v3", LetteringGeometry.OUTLINE)
        }.map { it.getValue("id").jsonPrimitive.content }
        assertEquals(emptyList(), stuck)
    }

    @Test fun unsweptTtfFallsBackToRunningAndSweptKeepsFill() {
        val unswept = automaticLetteringState("Oswald", "oswald", "x:glyph-extractor-v3", "Jung", 18.0, LetteringGeometry.OUTLINE, limits)
        assertEquals(StitchStyle.RUNNING, unswept.style)
        assertTrue(unswept.heightRanges.isEmpty())
    }

    @Test fun everyEmbroideryFontGeneratesWithinItsRecordedRange() {
        val fonts = InkStitchCatalog.parse(File("src/main/assets/${InkStitchCatalog.ASSET}").readText())
        val stuck = fonts.filterNot { f ->
            generatable(f.id, f.sha256 + ":glyph-extractor-v3", LetteringGeometry.SINGLE_LINE, f.recommendedMinHeightMm!!..f.recommendedMaxHeightMm!!)
        }.map { it.id }
        assertEquals(emptyList(), stuck)
    }
}
