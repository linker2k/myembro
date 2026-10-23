// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import io.github.linker2k.myembro.engine.model.EmbThread
import kotlin.test.*

class ImageQuantizerTest {
    @Test fun exactColorsDeterminismAndDefensiveCopies() {
        val pixels = intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFFFF0000.toInt(), 0xFF0000FF.toInt())
        val image = RasterImage(4, 1, pixels)
        pixels.fill(0)
        val options = ImageTraceOptions(40.0, colorCount = 3)
        val first = ImageQuantizer.quantize(image, options)
        assertEquals(setOf(0xFF0000, 0x00FF00, 0x0000FF), first.colors.map { it.rgb }.toSet())
        assertEquals(listOf(1, 1, 2), first.colors.map { it.pixelCount }.sorted())
        assertContentEquals(first.copyIndices(), ImageQuantizer.quantize(image, options).copyIndices())
        first.copyIndices().fill(-1)
        assertTrue(first.colorAt(0, 0) >= 0)
    }

    @Test fun populationWeightedMedianCutPreservesDominantColorAndFullEightBitMeans() {
        val colors = IntArray(100) { if (it == 0) 0xFF0000FF.toInt() else 0xFFFC0202.toInt() }
        val result = ImageQuantizer.quantize(RasterImage(100, 1, colors), ImageTraceOptions(50.0, colorCount = 1))
        assertEquals(1, result.colors.size)
        assertEquals(100, result.colors.single().pixelCount)
        val rgb = result.colors.single().rgb
        assertEquals(249, rgb ushr 16)
        assertEquals(2, rgb ushr 8 and 255)
        assertEquals(5, rgb and 255)
    }

    @Test fun alphaThresholdAndStraightAlphaMattingAreExplicit() {
        val result = ImageQuantizer.quantize(RasterImage(3, 1, intArrayOf(0x00FFFFFF, 0x7FFFFFFF, 0x80000000.toInt())),
            ImageTraceOptions(3.0, colorCount = 3))
        assertEquals(-1, result.colorAt(0, 0)); assertEquals(-1, result.colorAt(1, 0))
        assertEquals(0x7F7F7F, result.colors.single().rgb)
        val empty = ImageQuantizer.quantize(RasterImage(1, 1, intArrayOf(0)), ImageTraceOptions(1.0))
        assertTrue(empty.colors.isEmpty()); assertEquals(-1, empty.colorAt(0, 0))
    }

    @Test fun nearestThreadRetainsCatalogIdentityAndMergesLabels() {
        val red = EmbThread(0xFFEF1010.toInt(), "Red", "123", "Test")
        val blue = EmbThread(0xFF1010EF.toInt(), "Blue", "456", "Test")
        val result = ImageQuantizer.quantize(RasterImage(3, 1, intArrayOf(0xFFFF0000.toInt(), 0xFFE01010.toInt(), 0xFF0000FF.toInt())),
            ImageTraceOptions(3.0, colorCount = 3, threadPalette = listOf(red, blue)))
        assertEquals(2, result.colors.size)
        assertEquals(result.colorAt(0, 0), result.colorAt(1, 0))
        assertEquals(red, result.colors[result.colorAt(0, 0)].thread)
        assertEquals(blue, result.colors[result.colorAt(2, 0)].thread)
    }

    @Test fun malformedAndUnboundedInputsAreRejected() {
        assertFailsWith<IllegalArgumentException> { RasterImage(Int.MAX_VALUE, 2, intArrayOf()) }
        assertFailsWith<IllegalArgumentException> { RasterImage(2, 2, intArrayOf(0)) }
        assertFailsWith<IllegalArgumentException> { ImageTraceOptions(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { ImageTraceOptions(1.0, colorCount = 65) }
        assertFailsWith<IllegalArgumentException> { ImageTraceOptions(1.0, alphaThreshold = 0) }
        assertFailsWith<IllegalArgumentException> { ImageTraceOptions(1.0, minimumRegionAreaMm2 = -1.0) }
    }
}
