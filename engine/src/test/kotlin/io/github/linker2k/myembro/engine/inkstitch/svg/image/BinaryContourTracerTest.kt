// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BinaryContourTracerTest {
    // Fixtures are written top-down for readability, then flipped to upstream bitmap coordinates.
    private fun trace(vararg rows: String, turdsize: Int = 2): List<BinaryContour> {
        val width = rows.firstOrNull()?.length ?: 0
        require(rows.all { it.length == width })
        return BinaryContourTracer.trace(width, rows.size,
            rows.reversed().flatMap { row -> row.map { it == '#' } }.toBooleanArray(), turdsize)
    }

    @Test fun emptyMaskHasNoContours() {
        assertTrue(trace("....", "....").isEmpty())
        assertTrue(BinaryContourTracer.trace(0, 0, booleanArrayOf()).isEmpty())
    }

    @Test fun upstreamTurdsizeIsStrictAreaCutoffInPixels() {
        assertTrue(trace("#").isEmpty())
        assertTrue(trace("##").isEmpty())
        assertEquals(3L, trace("###").single().area)
        assertEquals(1L, trace("#", turdsize = 0).single().area)
    }

    @Test fun rectangleRetainsOriginalCornerOrderAndExactOptimalPolygon() {
        val rectangle = trace("######", "######", "######", "######").single()
        assertTrue(rectangle.positive)
        assertEquals(24L, rectangle.area)
        assertEquals(20, rectangle.points.size)
        assertEquals(listOf(PixelPoint(0, 4), PixelPoint(0, 3), PixelPoint(0, 2)), rectangle.points.take(3))
        val polygon = OptimalPolygon.indices(rectangle).map { rectangle.points[it] }
        assertEquals(listOf(PixelPoint(0, 4), PixelPoint(0, 0), PixelPoint(6, 0), PixelPoint(6, 4)), polygon)
    }

    @Test fun holeAndNestedIslandKeepTheirSignsAndAreas() {
        val contours = trace("#######", "#.....#", "#.###.#", "#.###.#", "#.###.#", "#.....#", "#######")
        assertEquals(listOf(49L, 25L, 9L), contours.map { it.area })
        assertEquals(listOf(true, false, true), contours.map { it.positive })
        assertTrue(contours.all { OptimalPolygon.indices(it).size == 4 })
    }

    @Test fun defaultMinorityJoinsDiagonalPixelsAtAmbiguousTurn() {
        val contour = trace("#.", ".#", turdsize = 0).single()
        assertEquals(2L, contour.area)
        assertEquals(8, contour.points.size)
    }

    @Test fun staircaseProducesCompactPolygonWithoutChangingInput() {
        val contour = trace("#....", "##...", "###..", "####.", "#####").single()
        val original = contour.points.toList()
        val indices = OptimalPolygon.indices(contour)
        assertTrue(indices.size in 3 until contour.points.size)
        assertEquals(0, indices.first())
        assertEquals(indices.sorted(), indices)
        assertEquals(original, contour.points)
    }

    @Test fun tracingLeavesCallerMaskUntouchedAndSeparatesIslands() {
        val pixels = booleanArrayOf(true, true, true, false, true, true, true)
        val original = pixels.copyOf()
        assertEquals(listOf(3L, 3L), BinaryContourTracer.trace(7, 1, pixels).map { it.area })
        assertContentEquals(original, pixels)
    }

    @Test fun malformedInputsAreRejectedBeforeTracing() {
        assertFailsWith<IllegalArgumentException> { BinaryContourTracer.trace(2, 3, booleanArrayOf()) }
        assertFailsWith<IllegalArgumentException> { BinaryContourTracer.trace(-1, 0, booleanArrayOf()) }
        assertFailsWith<IllegalArgumentException> { BinaryContourTracer.trace(0, 0, booleanArrayOf(), -1) }
        assertFailsWith<IllegalArgumentException> {
            OptimalPolygon.indices(BinaryContour(listOf(PixelPoint(0, 0), PixelPoint(2, 0), PixelPoint(2, 2), PixelPoint(0, 2)), 4, true))
        }
    }
}
