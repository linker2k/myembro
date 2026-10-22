// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.model.*
import kotlin.test.*

class HoopSnapRegressionTest {
    private fun pattern(id: String = "a", transform: Transform = Transform()) = StitchObject(id, id,
        EmbPattern(listOf(Stitch(-43, -27, Command.STITCH), Stitch(61, -27, Command.STITCH),
            Stitch(61, 31, Command.STITCH), Stitch(-43, 31, Command.STITCH),
            Stitch(-43, -27, Command.STITCH), Stitch(-43, -27, Command.END)), emptyList()), transform)

    @Test fun `four sides and four corners snap inside and one mm beyond warns`() {
        val directions = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1,
            -1 to -1, -1 to 1, 1 to -1, 1 to 1)
        for (hoop in HoopSpec.ALL + HoopSpec("odd", 1001, 1501)) {
            for (transform in listOf(Transform(x = 0.49, y = -0.51),
                Transform(x = -0.51, y = 0.49, rotationDeg = 33.7, scaleX = -1.13, scaleY = 1.27))) {
                for ((sx, sy) in directions) {
                    val initial = Project(hoop, listOf(pattern(transform = transform)))
                    val b = initial.selectionBounds(setOf("a"))!!
                    val edge = hoop.sewingArea.snapBounds
                    val dx = when (sx) { -1 -> edge.minX - b.minX - 2.0; 1 -> edge.maxX - b.maxX + 2.0; else -> 0.0 }
                    val dy = when (sy) { -1 -> edge.minY - b.minY - 2.0; 1 -> edge.maxY - b.maxY + 2.0; else -> 0.0 }
                    val raw = initial.transformGroup(setOf("a"), dx, dy, 1.0, 0.0)
                    assertTrue(raw.exceedsHoop())
                    val snapped = raw.snapSelection(setOf("a"), raw.snapTargets(setOf("a")), 4.0)
                    val placed = snapped.project.selectionBounds(setOf("a"))!!
                    val case = "${hoop.name} side=($sx,$sy) transform=$transform"
                    assertFalse(snapped.project.exceedsHoop(), case)
                    assertFalse(snapped.project.statistics().exceedsHoop, case)
                    if (sx < 0) { assertEquals(edge.minX, placed.minX, case); assertEquals(edge.minX.toDouble(), snapped.guides.x) }
                    if (sx > 0) { assertEquals(edge.maxX, placed.maxX, case); assertEquals(edge.maxX.toDouble(), snapped.guides.x) }
                    if (sy < 0) { assertEquals(edge.minY, placed.minY, case); assertEquals(edge.minY.toDouble(), snapped.guides.y) }
                    if (sy > 0) { assertEquals(edge.maxY, placed.maxY, case); assertEquals(edge.maxY.toDouble(), snapped.guides.y) }
                    val outside = snapped.project.transformGroup(setOf("a"), sx * 10.0, sy * 10.0, 1.0, 0.0)
                    assertTrue(outside.exceedsHoop(), "one mm beyond: $case")
                    assertTrue(outside.statistics().exceedsHoop, case)
                }
            }
        }
    }

    @Test fun `opposite edge and outside object magnets cannot snap a design outside`() {
        val edge = HoopSpec.H100.sewingArea.snapBounds
        val obj = pattern()
        val width = obj.placedBounds()!!.width
        // Put the left edge on the right hoop boundary: this must never count as a fit.
        val t = Transform(x = edge.maxX - obj.placedBounds()!!.minX.toDouble(), y = 150.0)
        val p = Project(objects = listOf(obj.withTransform(t), pattern("other", t.copy(x = t.x + width))))
        val snapped = p.snapSelection(setOf("a"), p.snapTargets(setOf("a")), 3.0)
        assertNull(snapped.guides.x)
        assertEquals(p.find("a")!!.transform.x, snapped.project.find("a")!!.transform.x)
        assertTrue(snapped.project.exceedsHoop())
    }

    @Test fun `oversized designs are not falsely snapped to fit`() {
        val p = Project(objects = listOf(pattern(transform = Transform(scaleX = 20.0))))
        val snapped = p.snapSelection(setOf("a"), p.snapTargets(setOf("a")), 1000.0)
        assertNull(snapped.guides.x)
        assertTrue(snapped.project.exceedsHoop())
    }

    @Test fun `warning remains hidden for entire drag then evaluates final rounded stitches`() {
        val initial = Project(objects = listOf(pattern()))
        val session = EditorSession(initial)
        assertFalse(session.showsHoopOverflow)
        session.beginGesture()
        val outside = initial.transformGroup(setOf("a"), 1000.0, 0.0, 1.0, 0.0)
        session.updateGesture(outside)
        assertTrue(session.project.exceedsHoop())
        assertFalse(session.showsHoopOverflow)
        session.endGesture()
        assertTrue(session.showsHoopOverflow)
        session.beginGesture()
        assertFalse(session.showsHoopOverflow)
        session.updateGesture(initial)
        session.endGesture()
        assertFalse(session.showsHoopOverflow)
    }

    @Test fun `odd hoop object magnet is corrected inward after final stitch rounding`() {
        val hoop = HoopSpec("odd", 1001, 1001)
        val edge = hoop.sewingArea.snapBounds
        val a = pattern(transform = Transform(x = edge.maxX - 52 + 1.49, y = 150.49))
        val other = pattern("other", a.transform.copy(y = -200.0))
        val p = Project(hoop, listOf(a, other))
        // Integer max=491 is within the odd hoop's 0.05 mm tolerance but a snap must
        // use the stricter inward grid edge=490, even if another object's edge is nearer.
        assertEquals(edge.maxX + 1, a.placedBounds()!!.maxX)
        assertTrue(hoop.contains(a.placedBounds()!!))
        val snapped = p.snapSelection(setOf("a"), p.snapTargets(setOf("a")), 2.0)
        assertEquals(edge.maxX, snapped.project.find("a")!!.placedBounds()!!.maxX)
        assertEquals(edge.maxX.toDouble(), snapped.guides.x)
        assertTrue(hoop.contains(snapped.project.find("a")!!.placedBounds()!!))
    }
}
