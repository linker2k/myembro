// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import io.github.linker2k.myembro.engine.edit.FontKind
import io.github.linker2k.myembro.engine.edit.Project
import io.github.linker2k.myembro.engine.edit.TextObject
import io.github.linker2k.myembro.engine.io.PesReader
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.model.EmbThread
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutlineLetteringTest {
    private fun box(x: Double = 0.0, y: Double = -100.0, width: Double = 80.0, height: Double = 100.0) =
        listOf(x to y, x + width to y, x + width to y + height, x to y + height, x to y)

    @Test
    fun normalizesHeightAspectRatioAndCenter() {
        val bounds = assertNotNull(LetteringEngine.fromContours(listOf(box()), heightMm = 12.0).bounds)
        assertEquals(120, bounds.height)
        assertEquals(96, bounds.width)
        assertEquals(0, bounds.minX + bounds.maxX)
        assertEquals(0, bounds.minY + bounds.maxY)
        val movedSource = box().map { (x, y) -> x + 500 to y + 700 }
        assertEquals(
            LetteringEngine.fromContours(listOf(box()), 12.0).stitches,
            LetteringEngine.fromContours(listOf(movedSource), 12.0, baseline = 700.0).stitches,
        )
    }

    @Test
    fun respectsStitchLengthAfterArcAndCoordinateRounding() {
        val contours = listOf(box(), box(x = 150.0, width = 20.0), listOf(-50.0 to -100.0, 200.0 to 0.0))
        for (length in listOf(0.2, 0.7, 2.5)) for (angle in listOf(-180.0, 0.0, 120.0)) {
            val pattern = LetteringEngine.fromContours(contours, 25.0, length, angle)
            pattern.stitches.zipWithNext().forEach { (a, b) ->
                if (b.command == Command.STITCH) {
                    assertTrue(hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()) <= length * 10 + 1e-9)
                }
            }
            val bounds = assertNotNull(pattern.bounds)
            assertTrue(abs(bounds.minX + bounds.maxX) <= 1)
            assertTrue(abs(bounds.minY + bounds.maxY) <= 1)
        }
    }

    @Test
    fun outerContoursAndHolesRemainSeparateAndClosed() {
        val contours = listOf(box(), box(20.0, -80.0, 30.0, 50.0), box(200.0))
        val pattern = LetteringEngine.fromContours(contours, 10.0)
        assertEquals(3, pattern.jumpCount)
        assertEquals(2, pattern.trimCount)
        assertEquals(0, pattern.colorChangeCount)
        var start: Pair<Int, Int>? = null
        pattern.stitches.forEachIndexed { i, s ->
            if (s.command == Command.JUMP) {
                if (i > 0) assertEquals(Command.TRIM, pattern.stitches[i - 1].command)
                start = s.x to s.y
                assertEquals(s.copy(command = Command.STITCH), pattern.stitches[i + 1])
            }
            if (s.command == Command.TRIM || s.command == Command.END) {
                assertEquals(start, s.x to s.y, "each contour returns to its own start")
            }
        }
        assertEquals(0, LetteringEngine.fromContours(contours, 10.0, trimBetweenStrokes = false).trimCount)
    }

    @Test
    fun mergesDenseShortSegmentsWhilePreservingCornersAndClosure() {
        val dense = (0..1000).map { it / 10.0 to -100.0 } + listOf(100.0 to 0.0, 0.0 to 0.0, 0.0 to -100.0)
        val merged = LetteringEngine.fromContours(listOf(dense), 10.0)
        val unmerged = LetteringEngine.fromContours(listOf(dense), 10.0, mergeToleranceMm = 0.0)
        assertTrue(merged.stitchCount < unmerged.stitchCount / 3)
        assertEquals(unmerged.bounds, merged.bounds)
        val sewn = merged.stitches.filter { it.command == Command.STITCH }
        assertEquals(sewn.first(), sewn.last())
        for (corner in listOf(-50 to -50, 50 to -50, 50 to 50, -50 to 50)) {
            assertTrue(sewn.any { (it.x to it.y) == corner }, "lost corner $corner")
        }
    }

    @Test
    fun baselineAndReferenceHeightMakeTranslatedSourcesEquivalentOnArcs() {
        val original = box()
        val moved = original.map { (x, y) -> x + 200.0 to y + 300.0 }
        val a = LetteringEngine.fromContours(listOf(original), 10.0, arcDeg = 90.0, sourceHeight = 100.0)
        val b = LetteringEngine.fromContours(listOf(moved), 10.0, arcDeg = 90.0, baseline = 300.0, sourceHeight = 100.0)
        assertEquals(a.stitches, b.stitches)
        val taller = LetteringEngine.fromContours(listOf(original), 10.0, sourceHeight = 50.0)
        assertEquals(200, assertNotNull(taller.bounds).height)
    }

    @Test
    fun handlesEmptyDegenerateAndInvalidContours() {
        assertNull(LetteringEngine.fromContours(emptyList(), 10.0).bounds)
        assertNull(LetteringEngine.fromContours(listOf(emptyList(), listOf(1.0 to 1.0)), 10.0).bounds)
        assertEquals(0, assertNotNull(LetteringEngine.fromContours(listOf(listOf(0.0 to 0.0, 50.0 to 0.0)), 10.0).bounds).height)
        assertFailsWith<IllegalArgumentException> { LetteringEngine.fromContours(listOf(listOf(Double.NaN to 0.0)), 10.0) }
        assertFailsWith<IllegalArgumentException> { LetteringEngine.fromContours(listOf(box()), 0.0) }
        assertFailsWith<IllegalArgumentException> { LetteringEngine.fromContours(listOf(box()), 10.0, stitchLengthMm = 0.0) }
        assertFailsWith<IllegalArgumentException> { LetteringEngine.fromContours(listOf(box()), 10.0, sourceHeight = 0.0) }
        assertFailsWith<IllegalArgumentException> { LetteringEngine.fromContours(listOf(box()), 10.0, baseline = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { LetteringEngine.fromContours(listOf(box()), 10.0, mergeToleranceMm = -1.0) }
    }

    @Test
    fun outlineTextObjectFlattensAndExports() {
        val thread = EmbThread(0xFF000000.toInt(), "Black")
        val obj = TextObject(
            id = "hangul", text = "자수", fontId = "jua", fontKind = FontKind.OUTLINE,
            contours = listOf(box(), box(150.0)), heightMm = 10.0, thread = thread,
        )
        assertEquals(listOf(thread), obj.localPattern().threads)
        assertEquals(100, assertNotNull(obj.localPattern().bounds).height)
        val pattern = Project(objects = listOf(obj)).flatten()
        val decoded = PesReader.read(PesWriter.write(pattern))
        assertEquals(pattern.bounds, decoded.bounds)
        assertEquals(io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor.forSewout(pattern).stitchCount, decoded.stitchCount)
        assertTrue(decoded.trimCount > 0)
        assertEquals(200, assertNotNull(obj.copy(heightMm = 20.0).localPattern().bounds).height)
    }
}
