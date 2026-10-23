// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import io.github.linker2k.myembro.engine.geometry.Point
import org.locationtech.jts.coverage.CoverageValidator
import org.locationtech.jts.geom.Polygon
import kotlin.random.Random
import kotlin.test.*

class BitmapVectorizerTest {
    private val red = 0xFFCC3300.toInt()
    private val blue = 0xFF0033CC.toInt()
    private fun trace(w: Int, h: Int, pixels: IntArray, mm: Double = w.toDouble(), area: Double = 0.0, tolerance: Double = 0.0) =
        ImagePipeline.trace(RasterImage(w, h, pixels), ImageTraceOptions(mm, colorCount = 8,
            minimumRegionAreaMm2 = area, simplifyToleranceMm = tolerance))

    @Test fun rectanglePreservesPixelCellExtentsAndMillimeterArea() {
        val result = trace(4, 2, IntArray(8) { red }, mm = 20.0)
        assertEquals(20.0, result.vector.widthMm); assertEquals(10.0, result.vector.heightMm)
        val region = result.vector.regions.single()
        assertEquals(200.0, region.areaMm2, 1e-8)
        assertEquals(8, region.sourcePixelCount)
        val bounds = region.geometry.geometry.envelopeInternal
        assertEquals(0.0, bounds.minX); assertEquals(200.0, bounds.maxX); assertEquals(100.0, bounds.maxY)
        assertEquals(5, region.geometry.boundaries().single().points.size)
    }

    @Test fun diagonalPixelsStaySeparateAndDonutRetainsTransparentHole() {
        val diagonal = trace(2, 2, intArrayOf(red, 0, 0, red))
        assertEquals(2, diagonal.vector.regions.size)
        val pixels = IntArray(49) { i -> if (i % 7 in 2..4 && i / 7 in 2..4) 0 else red }
        val donut = trace(7, 7, pixels)
        val region = donut.vector.regions.single().geometry
        assertEquals(40.0, region.area / 100, 1e-8)
        assertEquals(1, (region.geometry.getGeometryN(0) as Polygon).numInteriorRing)
        assertFalse(region.covers(Point(35.0, 35.0)))
        assertEquals(ImageStitchType.FILL, donut.elements.single().type)
    }

    @Test fun areaThresholdUsesMmSquaredIncludingAtExactThreshold() {
        val pixels = intArrayOf(red, 0, blue, 0, blue, blue)
        val kept = trace(3, 2, pixels, area = 1.0)
        assertEquals(2, kept.vector.regions.size)
        val removed = trace(3, 2, pixels, area = 1.01)
        assertEquals(1, removed.vector.regions.size)
        assertEquals(1, removed.vector.removedComponentCount)
        assertEquals(1.0, removed.vector.removedAreaMm2)
        assertEquals(2, trace(3, 2, pixels, mm = 6.0, area = 1.01).vector.regions.size)
    }

    @Test fun sameCatalogThreadMergesAdjacentQuantizedColorsBeforeNoiseRemoval() {
        val thread = io.github.linker2k.myembro.engine.model.EmbThread(red)
        val draft = ImagePipeline.trace(RasterImage(2, 1, intArrayOf(red, 0xFFDD3300.toInt())),
            ImageTraceOptions(2.0, colorCount = 2, minimumRegionAreaMm2 = 1.5, threadPalette = listOf(thread)))
        assertEquals(2.0, draft.vector.regions.single().areaMm2)
        assertEquals(0, draft.vector.removedComponentCount)
    }

    @Test fun simplificationSharesColorBoundaryWithoutOverlapAndRetainsHoles() {
        val pixels = IntArray(40 * 40) { i ->
            val x = i % 40; val y = i / 40
            if (x in 3..5 && y in 3..5) 0 else if (x < 20 + (y / 3 % 2)) red else blue
        }
        val exact = trace(40, 40, pixels)
        val simplified = trace(40, 40, pixels, tolerance = 1.5)
        val parts = simplified.vector.regions.map { it.geometry.geometry }.toTypedArray()
        assertTrue(CoverageValidator.isValid(parts))
        assertEquals(0.0, parts[0].intersection(parts[1]).area, 1e-8)
        assertEquals(exact.vector.regions.sumOf { it.areaMm2 }, simplified.vector.regions.sumOf { it.areaMm2 }, 1e-7)
        assertEquals(1, parts.sumOf { g -> (0 until g.numGeometries).sumOf { (g.getGeometryN(it) as Polygon).numInteriorRing } })
        assertTrue(simplified.vector.regions.sumOf { it.geometry.geometry.numPoints } < exact.vector.regions.sumOf { it.geometry.geometry.numPoints })
    }

    @Test fun randomSmallRasterTracesAllOpaqueCellsWithoutAreaLossOrFalseMerges() {
        val random = Random(20261024)
        repeat(75) {
            val w = 3 + random.nextInt(10); val h = 3 + random.nextInt(10)
            val pixels = IntArray(w * h) { listOf(0, red, blue)[random.nextInt(3)] }
            val draft = trace(w, h, pixels)
            assertEquals(pixels.count { it != 0 }.toDouble(), draft.vector.regions.sumOf { it.areaMm2 }, 1e-7)
            draft.vector.regions.forEach { assertTrue(it.geometry.geometry.isValid) }
            for (y in 0 until h) for (x in 0 until w) {
                val regions = draft.vector.regions.filter { it.geometry.covers(Point((x + 0.5) * 10, (y + 0.5) * 10)) }
                assertEquals(if (pixels[y * w + x] == 0) 0 else 1, regions.size)
                if (regions.isNotEmpty()) assertEquals(pixels[y * w + x] and 0xFFFFFF, regions.single().color.rgb)
            }
        }
    }

    @Test fun emptyAndOverlyFragmentedImagesHaveExplicitOutcomes() {
        assertTrue(trace(3, 3, IntArray(9)).elements.isEmpty())
        assertFailsWith<IllegalArgumentException> {
            trace(100, 100, IntArray(10000) { i -> if ((i % 100 + i / 100) % 2 == 0) red else blue })
        }
        assertFailsWith<IllegalArgumentException> { trace(1, 2, intArrayOf(red, red), mm = 1000.0) }
    }
}
