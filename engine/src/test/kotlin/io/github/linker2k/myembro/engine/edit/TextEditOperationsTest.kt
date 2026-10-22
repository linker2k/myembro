// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

import io.github.linker2k.myembro.engine.model.Stitch
import kotlin.math.abs
import kotlin.test.*

class TextEditOperationsTest {
    private fun objects() = listOf(TextObject("hershey", text = "Ab", thread = blue), outlineText())

    @Test fun duplicateKeepsBothKindsEditableAndOnlyChangesTheId() {
        for (obj in objects()) {
            val p = Project(objects = listOf(obj))
            val duplicated = p.duplicate(obj.id, "copy")
            val copy = assertIs<TextObject>(duplicated.find("copy"))
            assertEquals(obj.copy(id = "copy"), copy)
            assertEquals(obj.localPattern().stitches, copy.localPattern().stitches)
            assertNotEquals(obj.id, copy.id)
            assertSame(obj, p.objects.single())
            assertEquals(1, p.objects.size)
            assertEquals(duplicated, ProjectJson.decode(duplicated.toJson()))
        }
    }

    @Test fun mirrorsAndQuarterTurnTransformStitchesForBothKinds() {
        for (obj in objects()) {
            val p = Project(objects = listOf(obj))
            val original = obj.placedPattern().stitches
            val mirrorX = assertIs<TextObject>(p.mirrorX(obj.id).find(obj.id))
            val mirrorY = assertIs<TextObject>(p.mirrorY(obj.id).find(obj.id))
            val rotated = assertIs<TextObject>(p.rotateBy(obj.id, 90.0).find(obj.id))
            assertEquals(original.map { it.copy(x = -it.x) }, mirrorX.placedPattern().stitches)
            assertEquals(original.map { it.copy(y = -it.y) }, mirrorY.placedPattern().stitches)
            assertEquals(original.map { Stitch(-it.y, it.x, it.command) }, rotated.placedPattern().stitches)
            assertEquals(obj.copy(transform = mirrorX.transform), mirrorX)
            assertEquals(obj.copy(transform = mirrorY.transform), mirrorY)
            assertEquals(obj.copy(transform = rotated.transform), rotated)
            assertEquals(Transform(), obj.transform)
        }
    }

    @Test fun centerAndAllSelectionAlignmentsWorkAcrossTextAndImportedObjects() {
        val text = TextObject("text", text = "Ab", transform = Transform(-200.0, -100.0, 27.0))
        val outline = outlineText().copy(transform = Transform(180.0, 200.0))
        val p = Project(objects = listOf(text, outline, rectangle("stitch", 300.0)))
        for (obj in listOf(text, outline)) {
            val centered = p.alignCenter(obj.id).placedBounds(obj.id)!!
            assertTrue(abs(centered.minX + centered.maxX) <= 1)
            assertTrue(abs(centered.minY + centered.maxY) <= 1)
        }
        for (mode in Alignment.entries) {
            val result = p.align(setOf(text.id, outline.id), mode)
            assertIs<TextObject>(result.find(text.id)); assertIs<TextObject>(result.find(outline.id))
            val a = result.placedBounds(text.id)!!; val b = result.placedBounds(outline.id)!!
            when (mode) {
                Alignment.LEFT -> assertEquals(a.minX, b.minX)
                Alignment.RIGHT -> assertEquals(a.maxX, b.maxX)
                Alignment.TOP -> assertEquals(a.minY, b.minY)
                Alignment.BOTTOM -> assertEquals(a.maxY, b.maxY)
                Alignment.CENTER_X -> assertTrue(abs(a.minX + a.maxX - b.minX - b.maxX) <= 1)
                Alignment.CENTER_Y -> assertTrue(abs(a.minY + a.maxY - b.minY - b.maxY) <= 1)
            }
            assertSame(p.find("stitch"), result.find("stitch"))
            assertEquals(p.objects.map { it.id }, result.objects.map { it.id })
        }
    }

    @Test fun scalingDensityAndDistributionRetainTextData() {
        for (obj in objects()) {
            val p = Project(objects = listOf(obj))
            val scaled = assertIs<TextObject>(p.scaleBy(obj.id, 1.3).find(obj.id))
            assertTrue(scaled.densityWarning)
            assertEquals(obj.copy(transform = scaled.transform), scaled)
            assertFalse(p.mirrorX(obj.id).find(obj.id)!!.densityWarning)
        }
        val a = TextObject("a", text = "A", transform = Transform(-300.0))
        val b = outlineText("b").copy(transform = Transform(-200.0))
        val c = TextObject("c", text = "B", transform = Transform(300.0))
        val p = Project(objects = listOf(a, b, c))
        val result = p.distribute(setOf("a", "b", "c"), Axis.HORIZONTAL)
        val ba = result.placedBounds("a")!!; val bb = result.placedBounds("b")!!; val bc = result.placedBounds("c")!!
        assertTrue(abs((bb.minX - ba.maxX) - (bc.minX - bb.maxX)) <= 1)
        assertSame(a, result.find("a")); assertSame(c, result.find("c"))
        val middle = assertIs<TextObject>(result.find("b"))
        assertEquals(b.copy(transform = middle.transform), middle)
    }
}
