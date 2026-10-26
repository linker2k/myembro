// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor.block

import io.github.linker2k.myembro.engine.edit.ShapeObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockEditStateTest {
    private fun square(id: String, x0: Double, y0: Double, x1: Double, y1: Double, hole: Boolean = false) =
        BlockRegion(id, listOfNotNull(RegionPath(doubleArrayOf(x0, x1, x1, x0), doubleArrayOf(y0, y0, y1, y1), true),
            if (hole) RegionPath(doubleArrayOf(x0 + 2, x1 - 2, x1 - 2, x0 + 2), doubleArrayOf(y0 + 2, y0 + 2, y1 - 2, y1 - 2), true) else null),
            0xFF000000.toInt(), BlockStitchDraft())

    /** a (0..10) below b (5..15, sewn later = on top); c is a ring around 20..30 with a hole 22..28. */
    private fun state() = BlockEditState(listOf(square("a", 0.0, 0.0, 10.0, 10.0), square("b", 5.0, 5.0, 15.0, 15.0),
        square("c", 20.0, 20.0, 30.0, 30.0, hole = true)))

    @Test fun overlapPicksTheTopRegionAndMultiTapToggles() {
        val s = state()
        s.tap(7.0, 7.0, multi = false); assertEquals(setOf("b"), s.selected)
        s.tap(2.0, 2.0, multi = true); assertEquals(setOf("b", "a"), s.selected)
        s.tap(2.0, 2.0, multi = true); assertEquals(setOf("b"), s.selected)
        s.tap(40.0, 40.0, multi = false); assertEquals(emptySet<String>(), s.selected)
    }

    @Test fun holesAreNotPartOfTheRegion() {
        val s = state()
        s.tap(25.0, 25.0, false); assertEquals(emptySet<String>(), s.selected)
        s.tap(21.0, 25.0, false); assertEquals(setOf("c"), s.selected)
    }

    @Test fun selectionSurvivesARebuildForIdsThatStillExist() {
        val s = state(); s.restore(setOf("a", "gone"))
        assertEquals(setOf("a"), s.selected)
    }

    @Test fun angleBoundaries() {
        BlockStitchDraft(angleDeg = 0); BlockStitchDraft(angleDeg = 179)
        assertThrows(IllegalArgumentException::class.java) { BlockStitchDraft(angleDeg = 180) }
        assertThrows(IllegalArgumentException::class.java) { BlockStitchDraft(angleDeg = -1) }
    }

    @Test fun shapeObjectRegionsAreTappableInTheirOwnMillimeters() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><rect x="0" y="0" width="100" height="100" fill="#3366CC"/>""" +
            """<circle cx="50" cy="50" r="20" fill="#FFCC00"/></svg>"""
        val s = BlockEditState.of(ShapeObject.fromSvg("o", "two", svg, 40.0))
        assertEquals(2, s.regions.size)
        s.tap(0.0, 0.0, false)                      // center: the circle, drawn on top
        assertEquals(setOf(s.regions.last().id), s.selected)
        s.tap(-18.0, -18.0, false)                  // corner: only the square (with the circle cut out)
        assertEquals(setOf(s.regions.first().id), s.selected)
        assertTrue(s.bounds!!.maxX - s.bounds!!.minX in 39.0..41.0)
    }

    @Test fun everyBundledMeanderTileRenders() {
        val ids = MeanderThumbnails.ids()
        assertEquals(75, ids.size)   // 77 files in tiles/: 75 SVGs + index.txt + ORIGIN.txt
        ids.forEach { assertTrue(it, MeanderThumbnails.lines(it).coords.size >= 4) }
    }
}
