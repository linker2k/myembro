// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import kotlin.math.PI
import kotlin.test.*

class SampleColorRegionsTest {
    private val white = 0xffffffff.toInt()
    private val red = 0xffdd2222.toInt()
    private val blue = 0xff2233cc.toInt()

    @Test fun labMatchesScikitImage() {
        // skimage 0.25.2 color.rgb2lab / lab2rgb (tools/color-trace/README.md).
        val expected = mapOf(
            0xff0000 to doubleArrayOf(53.240587943744885, 80.09230822569225, 67.20275104442868),
            0x24354b to doubleArrayOf(21.68617970323738, 0.1815348608647238, -15.69078595145651),
            0xe1ba6f to doubleArrayOf(77.44555139159742, 4.791900874583199, 43.023436899975344),
            0x0ac81e to doubleArrayOf(70.5001319946117, -70.51383107333552, 64.94083996261955))
        for ((rgb, lab) in expected) {
            val out = DoubleArray(3); SampleColorRegions.rgbToLab(rgb, out, 0)
            for (k in 0..2) assertEquals(lab[k], out[k], 1e-9)
            assertEquals(rgb, SampleColorRegions.labToRgb(out))
        }
        // Out-of-gamut centers clip as skimage does, including negative Z.
        assertEquals(0x009800, SampleColorRegions.labToRgb(doubleArrayOf(50.0, -120.0, 100.0)))
        assertEquals(0x4d2900, SampleColorRegions.labToRgb(doubleArrayOf(20.0, 10.0, 120.0)))
    }

    @Test fun edgeLimitIs1024Inclusive() {
        for (edge in listOf(1023, 1024)) {
            SampleColorRegions.labels(edge, 1, IntArray(edge) { if (it in 300..600) red else white }, 99.9, 2)
            SampleColorRegions.labels(1, edge, IntArray(edge) { if (it in 300..600) red else white }, 99.9, 2)
        }
        assertFailsWith<IllegalArgumentException> { SampleColorRegions.labels(1025, 1, IntArray(1025) { white }, 99.9, 2) }
        assertFailsWith<IllegalArgumentException> { SampleColorRegions.labels(1, 1025, IntArray(1025) { white }, 99.9, 2) }
        assertFailsWith<IllegalArgumentException> { SampleColorRegions.labels(4, 4, IntArray(16) { white }, 10.0, 7) }
    }

    @Test fun borderWhiteIsBackgroundButEnclosedWhiteIsSewn() {
        val size = 200
        val pixels = IntArray(size * size) { p ->
            val x = p % size; val y = p / size
            when { x in 80..119 && y in 80..119 -> white; x in 20..179 && y in 20..179 -> red; else -> white }
        }
        val r = SampleColorRegions.labels(size, size, pixels, 20.0, 2)
        assertEquals(-1, r.labels[0])
        assertEquals(listOf(0xdd2222, 0xffffff), r.palette)
        assertEquals(1, r.labels[100 * size + 100])
    }

    @Test fun transparentPixelsAreBackground() {
        val size = 100
        val pixels = IntArray(size * size) { p -> if (p % size in 20..79 && p / size in 20..79) red else 0 }
        val r = SampleColorRegions.labels(size, size, pixels, 20.0, 2)
        assertEquals(-1, r.labels[0])
        assertEquals(0, r.labels[50 * size + 50])
    }

    /** At 0.1mm/px the 2mm-diameter limit is π·10² = 314.16px: 314 is repainted, 315 and 316 stay. */
    @Test fun piecesUnderTwoMillimeterDiameterTakeTheSurroundingColor() {
        assertEquals(314.159, PI * 10.0 * 10.0, 1e-3)
        for ((area, kept) in listOf(314 to false, 315 to true, 316 to true)) {
            val size = 400
            val pixels = IntArray(size * size) { white }
            for (y in 20..379) for (x in 20..199) pixels[y * size + x] = red
            for (y in 20..379) for (x in 220..379) pixels[y * size + x] = blue
            // A blue speck of exactly [area] pixels inside red: rows of 20, then a remainder row.
            for (i in 0 until area) pixels[(100 + i / 20) * size + 60 + i % 20] = blue
            val r = SampleColorRegions.labels(size, size, pixels, 40.0, 2)
            val speck = r.labels[100 * size + 60]
            val redLabel = r.labels[50 * size + 50]
            val blueLabel = r.labels[50 * size + 300]
            assertNotEquals(redLabel, blueLabel)
            assertEquals(if (kept) blueLabel else redLabel, speck, "area $area")
        }
    }

    private fun speck(area: Int, minPieceAreaMm2: Double): Boolean {
        val size = 400
        val pixels = IntArray(size * size) { white }
        for (y in 20..379) for (x in 20..199) pixels[y * size + x] = red
        for (y in 20..379) for (x in 220..379) pixels[y * size + x] = blue
        for (i in 0 until area) pixels[(100 + i / 20) * size + 60 + i % 20] = blue
        val r = SampleColorRegions.labels(size, size, pixels, 40.0, 2, minPieceAreaMm2 = minPieceAreaMm2)
        return r.labels[100 * size + 60] == r.labels[50 * size + 300]
    }

    /** Chosen piece area at 0.1mm/px: 1mm² = 100px, so 99 is repainted and 100, 101 stay; 0 keeps a 1px speck. */
    @Test fun chosenPieceAreaIsAnExclusivePixelLimit() {
        assertEquals(listOf(false, true, true), listOf(99, 100, 101).map { speck(it, 1.0) })
        assertTrue(speck(1, 0.0))
        assertFalse(speck(1, 0.25))
    }

    @Test fun explicitDefaultPieceAreaGivesTheConverterLabels() {
        val size = 300
        val pixels = IntArray(size * size) { i -> val x = i % size; val y = i / size
            if ((x / 7 + y / 5) % 3 == 0) red else if (x in 40..260 && y in 40..260) blue else white }
        val default = SampleColorRegions.labels(size, size, pixels, 37.3, 3)
        val explicit = SampleColorRegions.labels(size, size, pixels, 37.3, 3, minPieceAreaMm2 = PI)
        assertContentEquals(default.labels, explicit.labels)
        assertEquals(default.palette, explicit.palette)
    }

    @Test fun maximumColorsFollowTheSlider() {
        val colors = intArrayOf(0xffdd2222.toInt(), 0xff2233cc.toInt(), 0xff22aa33.toInt(), 0xffeecc11.toInt(), 0xff662288.toInt())
        val size = 250
        val pixels = IntArray(size * size) { p ->
            val x = p % size; val y = p / size
            if (y in 25..224 && x in 25..224) colors[(x - 25) / 40] else white
        }
        for (max in 2..6) {
            val r = SampleColorRegions.labels(size, size, pixels, 50.0, max)
            assertEquals(minOf(max, 5), r.palette.size, "max $max")
            assertTrue(r.labels.all { it in -1 until r.palette.size })
        }
    }

    @Test fun maskTracingEqualsTheWuRouteAndAcceptsFullSize() {
        val size = 120
        val pixels = IntArray(size * size) { p ->
            val x = p % size; val y = p / size
            when { (x - 60) * (x - 60) + (y - 60) * (y - 60) < 900 -> blue; x in 10..109 && y in 10..109 -> red; else -> white }
        }
        val preview = ColorDrawingPipeline.traceRegions(size, size, pixels, 30.0, 2)
        val labels = preview.indices()
        for (layer in preview.layers) {
            val wu = RedworkPipeline.trace(preview.width, preview.height,
                IntArray(labels.size) { if (labels[it] == layer.paletteIndex) 0 else 0xffffff },
                preview.canvasWidthMm, 0, layer.thread, ColorDrawingPipeline.MINIMUM_REGION_AREA_MM2,
                checkpoint = {}, preserveSmallContours = true)
            assertEquals(wu.paths, layer.outline.paths)
            assertEquals(wu.contourBudgetCount, layer.outline.contourBudgetCount)
        }
        // 1024² pads to 1026², beyond Wu's 1,048,576-pixel input.
        val edge = 1024
        val full = IntArray(edge * edge) { p -> if (p % edge in 100..900 && p / edge in 100..900) red else white }
        assertEquals(1, ColorDrawingPipeline.traceRegions(edge, edge, full, 99.9, 2).layers.size)
    }

    /** 20×20px squares at 99.9mm/1024px are 3.8mm²: each is one budget contour above 3mm² and
     * above the 2mm-diameter cleanup (330px). 127 and 128 fit, 129 is too complex. */
    @Test fun contourBudgetIs128Inclusive() {
        assertEquals(128, ColorDrawingPipeline.MAX_REGION_CONTOURS)
        val edge = 1024
        fun squares(count: Int): ColorDrawingPipeline.Preview {
            val pixels = IntArray(edge * edge) { white }
            for (k in 0 until count) {
                val left = 40 + (k % 30) * 30; val top = 40 + (k / 30) * 30
                for (y in top until top + 20) for (x in left until left + 20) pixels[y * edge + x] = red
            }
            return ColorDrawingPipeline.traceRegions(edge, edge, pixels, 99.9, 2)
        }
        for (count in listOf(127, 128)) assertEquals(count, squares(count).layers.sumOf { it.outline.contourBudgetCount })
        val failure = assertFailsWith<IllegalArgumentException> { squares(129) }
        assertTrue(failure.message!!.contains("PHOTO_TOO_COMPLEX"))
    }
}
