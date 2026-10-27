// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.texture

import java.util.Random
import kotlin.test.*

/** Analytic/local contract checks, NOT an upstream DrawingBot oracle. No external program is run. */
class SketchLinesTest {
    private val black = 0xff000000.toInt()
    private val white = -1
    @Test fun whiteInputTerminatesWithoutLines() {
        val r = SketchLines.render(20, 20, IntArray(400) { white })
        assertEquals(emptyList(), r.lines)
        assertEquals(SketchTermination.LUMINANCE, r.termination)
        assertEquals(1.0, r.progress.actual)
    }
    @Test fun fullyClippedInputTerminatesWithoutLines() {
        val r = SketchLines.render(20, 20, IntArray(400) { black }, BooleanArray(400))
        assertTrue(r.lines.isEmpty())
        assertEquals(SketchTermination.NO_DRAWABLE_PIXELS, r.termination)
    }
    @Test fun repeatedSeedProducesIdenticalOrderedLinesAndColors() {
        val pixels = IntArray(400) { i -> if (i % 20 < 10) 0xff314069.toInt() else 0xff96552a.toInt() }
        val original = pixels.copyOf()
        val s = SketchSettings(maxLines = 80)
        val a = SketchLines.render(20, 20, pixels, settings = s)
        val b = SketchLines.render(20, 20, pixels, settings = s)
        assertEquals(a, b)
        assertTrue(a.lines.isNotEmpty())
        assertContentEquals(original, pixels)
    }
    @Test fun singleBlackDotStartsAtThatDot() {
        val pixels = IntArray(25) { white }.also { it[2 * 5 + 3] = black }
        val r = SketchLines.render(5, 5, pixels, settings = SketchSettings(maxLines = 1))
        assertEquals(PixelPoint(3, 2), r.lines.first().start)
    }
    @Test fun axisPenaltyIsExactlyOne() {
        val p = PixelPoint(2, 2)
        assertEquals(1, SketchLineSampler.axisPenalty(p, PixelPoint(2, 8)))
        assertEquals(1, SketchLineSampler.axisPenalty(p, PixelPoint(8, 2)))
        assertEquals(0, SketchLineSampler.axisPenalty(p, PixelPoint(8, 8)))
        val sampler = SketchLineSampler(SketchRaster(9, 9, IntArray(81) { black }))
        val axis = sampler.find(p, 2, 4, 1, 0f, 0f, false)!!
        assertEquals(PixelPoint(6, 2), axis.destination)
        assertEquals(1f / 5, axis.average)
        val diagonal = sampler.find(p, 2, 4, 1, 45f, 0f, false)!!
        assertEquals(0f, diagonal.average)
        // Strict winner test keeps the earliest equally dark prefix once count > 2.
        assertEquals(PixelPoint(4, 4), diagonal.destination)
    }
    @Test fun defaultRandomBoundsAreUpperExclusive() {
        val rng = SketchRandom(0)
        val lengths = mutableSetOf<Int>()
        repeat(10000) {
            val angle = rng.float(-72f, -52f)
            assertTrue(angle >= -72f && angle < -52f)
            val length = rng.int(2, 40)
            assertTrue(length in 2..39)
            lengths += length
        }
        assertEquals((2..39).toSet(), lengths)
    }
    @Test fun javaBoundedSequenceIsUnmodifiedIncludingPowerOfTwoAndOverflowRanges() {
        val rng = SketchRandom(782)
        val jdk = Random(782)
        repeat(1000) {
            for ((a, b) in listOf(2 to 40, -8 to 8, 7 to 8, Int.MIN_VALUE to Int.MAX_VALUE))
                assertEquals(jdk.nextInt(a, b), rng.int(a, b))
            assertEquals(jdk.nextFloat(-72f, -52f), rng.float(-72f, -52f))
        }
    }
    @Test fun equalBoundsDoNotConsumeRandomDrawsAndReversedBoundsSwap() {
        val a = SketchRandom(42); val b = SketchRandom(42)
        assertEquals(9, a.int(9, 9)); assertEquals(3f, a.float(3f, 3f))
        assertEquals(b.int(2, 40), a.int(40, 2))
        assertEquals(b.float(-72f, -52f), a.float(-52f, -72f))
    }
    @Test fun progressUsesUpstreamDensityDenominatorAndMaxNotSum() {
        val p = SketchLines.progress(100.0, 150.0, 5, SketchSettings(maxLines = 20))
        assertEquals(.25, p.lineProgress)
        assertEquals(50.0 / ((253.5 - 100) * .75), p.luminanceProgress)
        assertEquals(p.luminanceProgress, p.actual)
        assertEquals(1.0, SketchLines.progress(100.0, 253.5, 0, SketchSettings()).actual)
        assertEquals(1.0, SketchLines.progress(100.0, 110.0, 20, SketchSettings(maxLines = 20)).actual)
    }
    @Test fun zeroDensityAndZeroLineLimitKeepOriginalFloatingPointFormula() {
        assertTrue(SketchLines.progress(0.0, 0.0, 0, SketchSettings(lineDensity = 0f)).actual.isNaN())
        assertTrue(SketchLines.progress(0.0, 0.0, 0, SketchSettings(maxLines = 0)).actual.isNaN())
    }
    @Test fun finiteLineLimitStopsExactlyAndReportsProgress() {
        val updates = mutableListOf<SketchProgress>()
        val r = SketchLines.render(20, 20, IntArray(400) { black }, settings = SketchSettings(maxLines = 7), onProgress = updates::add)
        assertEquals(7, r.lines.size)
        assertEquals(SketchTermination.LINE_LIMIT, r.termination)
        assertEquals(1.0, r.progress.lineProgress)
        assertEquals(8, updates.size)
    }
    @Test fun cancellationReturnsTypedPartialResult() {
        var calls = 0
        val r = SketchLines.render(20, 20, IntArray(400) { black }, isCancelled = { ++calls > 15 })
        assertEquals(SketchTermination.CANCELLED, r.termination)
        val before = SketchLines.render(20, 20, IntArray(400) { black }, isCancelled = { true })
        assertTrue(before.lines.isEmpty())
    }
    @Test fun darkestTileMeanWinsOverGlobalDarkestAndTiesAreXMajor() {
        val pixels = IntArray(20 * 10) { i -> if (i % 20 < 10) 0xff323232.toInt() else white }
        pixels[19] = black
        val raster = SketchRaster(20, 10, pixels)
        val cache = DarkestAreaCache(raster)
        assertEquals(PixelPoint(0, 0), cache.next())
        cache.close()
    }
    @Test fun cacheInvalidatesAfterBrightnessChangeAndAbsorbsRemainder() {
        val pixels = IntArray(23 * 13) { white }
        pixels[12 * 23 + 22] = black
        val raster = SketchRaster(23, 13, pixels)
        val cache = DarkestAreaCache(raster)
        assertEquals(PixelPoint(22, 12), cache.next())
        raster.whiten(22, 12)
        assertEquals(PixelPoint(0, 0), cache.next())
        cache.close()
    }
    @Test fun clipAndAverageUseOnlyDrawablePixelsButReferenceIsUnchanged() {
        val source = intArrayOf(black, white, 0xff285078.toInt(), black)
        val raster = SketchRaster(2, 2, source, booleanArrayOf(true, false, false, false))
        assertEquals(0.0, raster.averageLuminance())
        raster.brighten(0, 0, 50)
        assertEquals(SketchRaster.perceivedLuminance(0xff323232.toInt()).toDouble(), raster.averageLuminance())
        assertEquals(black, raster.referenceArgb(0, 0))
        assertContentEquals(intArrayOf(black, white, 0xff285078.toInt(), black), source)
    }
    @Test fun sampledColorsComeFromReferenceNotBrightenedRaster() {
        val color = 0xff314069.toInt()
        val r = SketchLines.render(20, 20, IntArray(400) { color }, settings = SketchSettings(maxLines = 40))
        assertTrue(r.lines.isNotEmpty())
        assertTrue(r.lines.all { it.sampledArgb == color })
    }
    @Test fun pixelTraversalKeepsLineEndpointsAndCircleOrder() {
        val line = mutableListOf<PixelPoint>()
        SketchBresenham.line(PixelPoint(0, 0), PixelPoint(3, 1)) { x, y -> line += PixelPoint(x, y) }
        assertEquals(listOf(PixelPoint(0, 0), PixelPoint(1, 0), PixelPoint(2, 1), PixelPoint(3, 1)), line)
        val circle = mutableListOf<PixelPoint>()
        SketchBresenham.circle(0, 0, 2) { x, y -> circle += PixelPoint(x, y) }
        assertEquals(listOf(PixelPoint(2, 0), PixelPoint(0, 2), PixelPoint(-2, 0), PixelPoint(0, -2)), circle.take(4))
        assertEquals(circle.size, SketchBresenham.circleSize(2))
    }
    @Test fun smallRasterNoValidLineStillTerminates() {
        val r = SketchLines.render(1, 1, intArrayOf(black))
        assertEquals(SketchTermination.FAILED_SEARCH, r.termination)
        assertTrue(r.lines.isEmpty())
    }
    @Test fun settingsNormalizeReversedBoundsLikeSetup() {
        val s = SketchSettings(minLineLength = 40, maxLineLength = 2, eraseMin = 125f, eraseMax = 50f).normalized()
        assertEquals(2, s.minLineLength); assertEquals(40, s.maxLineLength)
        assertEquals(50f, s.eraseMin); assertEquals(125f, s.eraseMax)
        assertFailsWith<IllegalArgumentException> { SketchSettings(lineDensity = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { SketchLines.render(0, 1, intArrayOf()) }
    }
}
