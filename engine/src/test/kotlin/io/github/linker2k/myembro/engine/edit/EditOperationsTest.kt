// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

import io.github.linker2k.myembro.engine.model.*
import kotlin.test.*

internal val blue = EmbThread(0xFF0000FF.toInt(), "Blue", "1", "brand", "chart")
internal val red = EmbThread(0xFFFF0000.toInt(), "Red")
internal fun rectangle(id: String = "a", x: Double = 0.0, y: Double = 0.0, width: Int = 100, height: Int = 40) = StitchObject(
    id, "object $id", EmbPattern(listOf(Stitch(0, 0, Command.STITCH), Stitch(width, height, Command.STITCH),
        Stitch(width, height, Command.END)), listOf(blue)), Transform(x, y),
)

class EditOperationsTest {
    @Test fun duplicateRetainsDataAndOriginalIsImmutable() {
        val original = rectangle()
        val p = Project(objects = listOf(original, rectangle("b")))
        val copy = p.duplicate("a").objects[1] as StitchObject
        assertNotEquals(original.id, copy.id)
        assertSame(original.source, copy.source)
        assertEquals(original.transform, copy.transform)
        assertEquals(listOf("a", "b"), p.objects.map { it.id })
        assertEquals(listOf("a", "new", "b"), p.duplicate("a", "new").objects.map { it.id })
        assertFailsWith<IllegalArgumentException> { p.duplicate("a", "b") }
        assertSame(p, p.duplicate("absent"))
    }

    @Test fun mirrorRotationAndCumulativeDensity() {
        val p = Project(objects = listOf(rectangle(x = 200.0, y = 100.0)))
        assertEquals(-1.0, p.mirrorX("a").find("a")!!.transform.scaleX)
        assertEquals(-1.0, p.mirrorY("a").find("a")!!.transform.scaleY)
        assertFalse(p.mirrorX("a").find("a")!!.densityWarning)
        assertEquals(Bounds(180, 50, 220, 150), p.rotateBy("a", 90.0).placedBounds("a"))
        assertEquals(Transform(200.0, 100.0), p.find("a")!!.transform)
        assertFalse(p.scaleBy("a", 0.8).find("a")!!.densityWarning)
        assertFalse(p.scaleBy("a", 1.2).find("a")!!.densityWarning)
        assertTrue(p.scaleBy("a", 0.79).find("a")!!.densityWarning)
        assertTrue(p.scaleBy("a", 1.0, 1.21).find("a")!!.densityWarning)
        assertEquals(setOf("a"), p.scaleBy("a", 1.1).scaleBy("a", 1.1).densityWarnings)
        assertFailsWith<IllegalArgumentException> { p.scaleBy("a", 0.0) }
        assertFailsWith<IllegalArgumentException> { p.rotateBy("a", Double.NaN) }
    }

    @Test fun placedBoundsCenterAndHoopOverflow() {
        val p = Project(objects = listOf(rectangle(x = 450.0), rectangle("b", -200.0, 100.0)))
        assertEquals(Bounds(-250, -20, 500, 120), p.placedBounds())
        assertFalse(p.exceedsHoop()) // touching the edge fits
        val outside = p.update("a") { it.withTransform(it.transform.copy(x = 451.0)) }
        assertTrue(outside.exceedsHoop())
        assertEquals(listOf("a"), outside.objectsOutsideHoop().map { it.id })
        assertFalse(outside.update("a") { it.withVisible(false) }.exceedsHoop())
        assertEquals(Bounds(-50, -20, 50, 20), outside.alignCenter("a").placedBounds("a"))
        assertNull(Project().placedBounds())
        assertNull(p.placedBounds("missing"))
        assertFalse(HoopSpec("odd", 101, 101).contains(Bounds(-51, 0, 0, 0)))
        assertFailsWith<IllegalArgumentException> { HoopSpec("bad", 0, 1) }
    }

    @Test fun allAlignmentModesUsePlacedEdges() {
        val p = Project(objects = listOf(rectangle("a", -100.0, -100.0), rectangle("b", 200.0, 300.0, 60, 20), rectangle("other")))
        val ids = setOf("a", "b")
        for (mode in Alignment.entries) {
            val result = p.align(ids, mode)
            val a = result.placedBounds("a")!!; val b = result.placedBounds("b")!!
            when (mode) {
                Alignment.LEFT -> assertEquals(a.minX, b.minX)
                Alignment.RIGHT -> assertEquals(a.maxX, b.maxX)
                Alignment.CENTER_X -> assertEquals(a.minX + a.maxX, b.minX + b.maxX)
                Alignment.TOP -> assertEquals(a.minY, b.minY)
                Alignment.BOTTOM -> assertEquals(a.maxY, b.maxY)
                Alignment.CENTER_Y -> assertEquals(a.minY + a.maxY, b.minY + b.maxY)
            }
            assertEquals(p.find("other"), result.find("other"))
            assertEquals(p.objects.map { it.id }, result.objects.map { it.id })
        }
    }

    @Test fun distributionUsesEqualGapsWithFixedEndpoints() {
        for (axis in Axis.entries) {
            val p = Project(objects = listOf(rectangle("a", -200.0, -200.0), rectangle("b", -100.0, -100.0, 60, 20), rectangle("c", 200.0, 200.0)))
            val result = p.distribute(setOf("a", "b", "c"), axis)
            val a = result.placedBounds("a")!!; val b = result.placedBounds("b")!!; val c = result.placedBounds("c")!!
            if (axis == Axis.HORIZONTAL) assertEquals(b.minX - a.maxX, c.minX - b.maxX)
            else assertEquals(b.minY - a.maxY, c.minY - b.maxY)
            assertEquals(p.find("a"), result.find("a")); assertEquals(p.find("c"), result.find("c"))
        }
        val p = Project(objects = listOf(rectangle()))
        assertSame(p, p.distribute(setOf("a"), Axis.HORIZONTAL))
        assertSame(p, p.align(emptySet(), Alignment.LEFT))
    }

    @Test fun projectCrudAndTransformOrder() {
        val a = rectangle(); val b = rectangle("b")
        val p = Project().add(a).add(b)
        assertEquals(listOf(b, a), p.reorder("a", 100).objects)
        assertEquals(listOf(a, b), p.reorder("b", -1).objects.reversed())
        assertEquals(listOf(b), p.remove("a").objects)
        assertSame(p, p.reorder("absent", 0))
        val (x, y) = Transform(10.0, 20.0, 90.0, 2.0, 3.0).apply(4.0, 5.0)
        assertEquals(-5.0, x, 0.00001); assertEquals(28.0, y, 0.00001)
        val empty = StitchObject("empty", "empty", EmbPattern(emptyList(), emptyList()))
        assertNull(empty.placedBounds())
        assertEquals(empty, Project(objects = listOf(empty)).alignCenter("empty").find("empty"))
    }
}
