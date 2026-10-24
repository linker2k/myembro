// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Goldens are the original C main's actual LUT/Qadd, with only its I/O placeholders supplied. */
class ImageQuantizerOracleTest {
    @Test fun primariesAndEarlyPaletteExhaustionMatchOriginalCExactly() = compare("primaries")
    @Test fun gradientMatchesOriginalCExactly() = compare("gradient")
    @Test fun bucketBoundariesAndFloatMomentsMatchOriginalCExactly() = compare("bucket-boundaries")

    private fun compare(name: String) {
        val tokens = resource("$name.rgb").trim().split(Regex("\\s+"))
        val count = tokens.first().toInt()
        val pixels = tokens.drop(1).take(count).map { it.toInt(16) }.toIntArray()
        val requested = tokens.last().toInt()
        val unchanged = pixels.copyOf()
        val expected = Json.parseToJsonElement(resource("$name.json")).jsonObject
        assertEquals("ca4898517a93b6b45444a9cda5fd50132694b864", expected.getValue("upstream").jsonPrimitive.content)
        val palette = expected.getValue("palette").jsonArray.map { it.jsonPrimitive.int }
        val indices = expected.getValue("indices").jsonArray.map { it.jsonPrimitive.int }.toIntArray()
        val actual = ImageQuantizer.quantize(pixels, requested)
        assertEquals(palette, actual.palette, "$name ordered RGB palette")
        assertContentEquals(indices, actual.copyIndices(), "$name every pixel label")
        assertContentEquals(unchanged, pixels)
        assertEquals(palette, ImageQuantizer.quantize(pixels, requested).palette)
        assertContentEquals(indices, ImageQuantizer.quantize(pixels, requested).copyIndices())
        val editedCopy = actual.copyIndices()
        editedCopy[0] = -1
        assertContentEquals(indices, actual.copyIndices())
        if (name == "primaries") assertEquals(3, actual.palette.size) // request six; source stops at three.
    }

    @Test fun invalidInputIsRejectedBeforeHistogramConstruction() {
        assertFailsWith<IllegalArgumentException> { ImageQuantizer.quantize(intArrayOf(), 2) }
        assertFailsWith<IllegalArgumentException> { ImageQuantizer.quantize(intArrayOf(0), 0) }
        assertFailsWith<IllegalArgumentException> { ImageQuantizer.quantize(intArrayOf(0), 257) }
        assertFailsWith<IllegalArgumentException> { ImageQuantizer.quantize(intArrayOf(-1), 2) }
    }
    private fun resource(name: String) = requireNotNull(javaClass.getResource("/wu/$name")).readText()
}
