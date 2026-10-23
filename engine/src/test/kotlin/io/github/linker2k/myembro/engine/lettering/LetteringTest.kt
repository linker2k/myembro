// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import io.github.linker2k.myembro.engine.edit.Project
import io.github.linker2k.myembro.engine.edit.TextObject
import io.github.linker2k.myembro.engine.edit.Transform
import io.github.linker2k.myembro.engine.io.PesReader
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.model.EmbThread
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LetteringTest {
    @Test
    fun parsesBundledBearingsAndPenLifts() {
        val font = HersheyFont.DEFAULT
        assertEquals(96, font.glyphs.size)
        val a = assertNotNull(font['A'])
        assertEquals(-9, a.left)
        assertEquals(9, a.right)
        assertEquals(3, a.strokes.size)
        assertEquals(listOf(HersheyPoint(0, -12), HersheyPoint(-8, 9)), a.strokes.first())
        assertEquals(16, assertNotNull(font[' ']).advance)
        assertTrue(assertNotNull(font[' ']).strokes.isEmpty())
        assertTrue(font.supports('~'))
        assertFalse(font.supports('가'))
    }

    @Test
    fun rejectsMalformedFontRecords() {
        for (record in listOf("", "123", "xxxxx  1JZ", "12345  xJZ", "12345  2JZ", "12345  1ZJ")) {
            assertFailsWith<IllegalArgumentException>(record) { HersheyFont.parse(record) }
        }
        // The final space is a coordinate, and must not be stripped by the parser.
        val glyph = assertNotNull(HersheyFont.parse("12345  2JZR ")[' '])
        assertEquals(HersheyPoint(0, -50), glyph.strokes.single().single())
    }

    @Test
    fun capHeightAndBoundsScaleInMillimeters() {
        val small = assertNotNull(LetteringEngine.generate("H", heightMm = 10.0).bounds)
        val large = assertNotNull(LetteringEngine.generate("H", heightMm = 20.0).bounds)
        assertEquals(100, small.height)
        assertEquals(200, large.height)
        assertTrue(abs(large.width - small.width * 2) <= 2)
        assertEquals(0, small.minY + small.maxY)
    }

    @Test
    fun spacingAndNewlinesUseMillimeters() {
        val compact = assertNotNull(LetteringEngine.generate("HH", spacing = 0.0).bounds)
        val spaced = assertNotNull(LetteringEngine.generate("HH", spacing = 3.0).bounds)
        assertEquals(30, spaced.width - compact.width)
        val multiline = assertNotNull(LetteringEngine.generate("H\nH").bounds)
        assertEquals(250, multiline.height) // 10 mm cap + 15 mm baseline distance.
        assertEquals(LetteringEngine.generate("H\nH").stitches, LetteringEngine.generate("H\r\nH").stitches)
        assertEquals(LetteringEngine.generate("H\nH").stitches, LetteringEngine.generate("H\rH").stitches)
        assertEquals(LetteringEngine.generate("H    H").stitches, LetteringEngine.generate("H\tH").stitches)
    }

    @Test
    fun outputIsCenteredForStraightCurvedAndMultilineText() {
        for (text in listOf("Hello!", "W\ni", "gj", " A ")) {
            for (angle in listOf(-180.0, -60.0, 0.0, 0.000001, 60.0, 180.0, 360.0)) {
                val pattern = LetteringEngine.generate(text, arcDeg = angle)
                val bounds = assertNotNull(pattern.bounds)
                assertTrue(abs(bounds.minX + bounds.maxX) <= 1, "$text / $angle")
                assertTrue(abs(bounds.minY + bounds.maxY) <= 1, "$text / $angle")
                assertEquals(Command.END, pattern.stitches.last().command)
                assertEquals(1, pattern.colorBlocks.size)
            }
        }
    }

    @Test
    fun arcBendsBaselineAndRotatesGlyphs() {
        val flat = LetteringEngine.generate("IIIIII", spacing = 3.0)
        val positive = LetteringEngine.generate("IIIIII", spacing = 3.0, arcDeg = 120.0)
        val negative = LetteringEngine.generate("IIIIII", spacing = 3.0, arcDeg = -120.0)
        val starts = positive.stitches.filter { it.command == Command.JUMP }
        assertTrue(starts.first().y > starts[starts.size / 2].y)
        val reverseStarts = negative.stitches.filter { it.command == Command.JUMP }
        assertTrue(reverseStarts.first().y < reverseStarts[reverseStarts.size / 2].y)
        assertTrue(assertNotNull(positive.bounds).height > assertNotNull(flat.bounds).height)
        val firstEnd = positive.stitches.takeWhile { it.command != Command.TRIM }.last()
        assertTrue(firstEnd.x != starts.first().x, "outer glyph rotates along tangent")
    }

    @Test
    fun stitchesRespectLengthAfterQuantizationIncludingArcs() {
        for (length in listOf(0.5, 0.7, 2.5, 4.0)) {
            for (angle in listOf(-150.0, 0.0, 90.0)) {
                val pattern = LetteringEngine.generate("AVWgy!?\nhello", heightMm = 23.4, arcDeg = angle, stitchLengthMm = length, stitchStyle = StitchStyle.RUNNING)
                for ((previous, next) in pattern.stitches.zipWithNext()) {
                    if (next.command != Command.STITCH) continue
                    val distance = hypot((next.x - previous.x).toDouble(), (next.y - previous.y).toDouble())
                    assertTrue(distance <= length * 10 + 1e-9, "$distance > ${length * 10}, $previous → $next")
                }
            }
        }
        // Check the default explicitly against 2.5 mm.
        assertTrue(LetteringEngine.generate("I", heightMm = 30.0).stitches.zipWithNext().all { (a, b) ->
            b.command != Command.STITCH || hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()) <= 25.0
        })
    }

    @Test
    fun separateStrokesHaveTrimThenJumpWithoutConnectingStitches() {
        val pattern = LetteringEngine.generate("A A")
        assertEquals(6, pattern.jumpCount)
        assertEquals(5, pattern.trimCount)
        pattern.stitches.forEachIndexed { i, stitch ->
            if (stitch.command == Command.JUMP) {
                if (i > 0) assertEquals(Command.TRIM, pattern.stitches[i - 1].command)
                val next = pattern.stitches[i + 1]
                assertEquals(Command.STITCH, next.command)
                val lockLength = hypot((stitch.x-next.x).toDouble(),(stitch.y-next.y).toDouble())
                assertEquals(0.0, lockLength) // Source half-stitch starts at the jump anchor.
            }
        }
        val untrimmed = LetteringEngine.generate("A A", trimBetweenStrokes = false)
        assertEquals(6, untrimmed.jumpCount)
        assertEquals(0, untrimmed.trimCount)
    }

    @Test
    fun unsupportedUnicodeIsVisibleAndReportedPerCodePoint() {
        val pattern = LetteringEngine.generate("한😀한")
        assertEquals(LetteringEngine.generate("???").stitches, pattern.stitches)
        assertEquals(2, pattern.warnings.size)
        assertTrue(pattern.warnings.any { "U+D55C" in it })
        assertTrue(pattern.warnings.any { "U+1F600" in it })
    }

    @Test
    fun emptyAndWhitespaceTextProduceOnlyEnd() {
        for (text in listOf("", "   ", "\r\n\t")) {
            val pattern = LetteringEngine.generate(text)
            assertEquals(1, pattern.stitches.size)
            assertEquals(Command.END, pattern.stitches.single().command)
            assertNull(pattern.bounds)
            assertTrue(pattern.threads.isEmpty())
            assertTrue(pattern.warnings.isEmpty())
        }
    }

    @Test
    fun invalidOptionsFailClearly() {
        for (height in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { LetteringEngine.generate("A", heightMm = height) }
        }
        for (gap in listOf(-1.0, Double.NaN)) {
            assertFailsWith<IllegalArgumentException> { LetteringEngine.generate("A", spacing = gap) }
        }
        for (angle in listOf(361.0, Double.NaN)) {
            assertFailsWith<IllegalArgumentException> { LetteringEngine.generate("A", arcDeg = angle) }
        }
        for (length in listOf(0.0, 0.1, Double.NaN)) {
            assertFailsWith<IllegalArgumentException> { LetteringEngine.generate("A", stitchLengthMm = length) }
        }
        assertFailsWith<IllegalArgumentException> { LetteringEngine.generate("A", fontId = "unknown") }
    }

    @Test
    fun generatedTextCanBeExportedToPes() {
        val pattern = Project(objects = listOf(TextObject(id = "export", text = "Sew!", arcDeg = 45.0))).flatten()
        val read = PesReader.read(PesWriter.write(pattern))
        assertNotNull(read.bounds)
        assertEquals(pattern.bounds, read.bounds)
        assertEquals(pattern.stitchCount, read.stitchCount)
        assertTrue(read.trimCount > 0)
    }

    @Test
    fun textObjectPreservesEditorContractAndThread() {
        val thread = EmbThread(0xFF123456.toInt(), "Test thread")
        val original = TextObject(id = "text-1", text = "Hello", thread = thread)
        val moved = original.withTransform(Transform(x = 100.0, y = 200.0))
        assertEquals(Transform(), original.transform)
        assertFalse(original.withVisible(false).visible)
        assertTrue(original.visible)
        original.localPattern().stitches.zip(moved.placedPattern().stitches).forEach { (a, b) ->
            assertEquals(a.x + 100, b.x)
            assertEquals(a.y + 200, b.y)
            assertEquals(a.command, b.command)
        }
        assertEquals(listOf(thread), Project(objects = listOf(moved)).flatten().threads)
        val changed = original.copy(text = "I", heightMm = 20.0)
        assertEquals(200, assertNotNull(changed.localPattern().bounds).height)
    }
}
