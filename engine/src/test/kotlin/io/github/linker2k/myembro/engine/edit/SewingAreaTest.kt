// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

import io.github.linker2k.myembro.engine.model.*
import kotlin.test.*

class SewingAreaTest {
    @Test fun `sewing area applies the single safety margin on all four sides`() {
        val area = HoopSpec.H100.sewingArea
        val inset = HOOP_SAFETY_MARGIN_MM * 10
        assertEquals(-500 + inset, area.left)
        assertEquals(500 - inset, area.right)
        assertEquals(-500 + inset, area.top)
        assertEquals(500 - inset, area.bottom)
        assertTrue(area.contains(area.snapBounds))
    }

    @Test fun `odd hoop rejects any excursion beyond the exact safety inset`() {
        val hoop = HoopSpec("odd", 1001, 1001)
        val area = hoop.sewingArea
        val lo = kotlin.math.floor(area.left).toInt()
        val hi = kotlin.math.ceil(area.right).toInt()
        assertEquals(0.0, HOOP_BOUNDARY_TOLERANCE_MM)
        assertFalse(hoop.contains(Bounds(lo, lo, hi, hi))) // Exactly 0.05 mm beyond each side.
        assertFalse(hoop.contains(Bounds(lo - 1, lo, hi, hi)))
        assertFalse(hoop.contains(Bounds(lo, lo - 1, hi, hi)))
        assertFalse(hoop.contains(Bounds(lo, lo, hi + 1, hi)))
        assertFalse(hoop.contains(Bounds(lo, lo, hi, hi + 1)))
        assertTrue(area.snapBounds.minX >= area.left)
        assertTrue(area.snapBounds.maxX <= area.right)
    }

    @Test fun `warning compares rounded needle positions rather than float transforms`() {
        val source = EmbPattern(listOf(Stitch(-10, -10, Command.STITCH), Stitch(10, 10, Command.STITCH)), emptyList())
        val edge = HoopSpec.H100.sewingArea.right
        fun placed(extra: Double) = Project(objects = listOf(StitchObject("a", "a", source,
            Transform(x = edge - 10 + extra))))
        assertFalse(placed(0.49).exceedsHoop()) // rounds onto the boundary
        assertTrue(placed(0.51).exceedsHoop()) // rounds 0.1 mm outside, larger than tolerance
        assertTrue(placed(10.0).exceedsHoop())
        assertFalse(placed(10.0).update("a") { it.withVisible(false) }.exceedsHoop())
    }
}
