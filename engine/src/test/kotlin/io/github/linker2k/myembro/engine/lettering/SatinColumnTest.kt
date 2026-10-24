// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.io.PesReader
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor
import io.github.linker2k.myembro.engine.model.*
import kotlinx.serialization.json.JsonObject
import kotlin.math.*
import kotlin.test.*

class SatinColumnTest {
    private fun column(width: Double = 4.0, options: SatinOptions = SatinOptions()) = SatinColumn.generate(
        listOf(0.0 to 0.0, 0.0 to 10.0), listOf(width to 0.0, width to 10.0), options,
    )
    private fun body(p: EmbPattern): EmbPattern {
        assertTrue(p.metadata[GeneratedPatternProcessor.LOCK_INDICES_KEY].orEmpty().isNotEmpty())
        return GeneratedPatternProcessor.stripGeneratedLocks(p)
    }
    private fun sewn(p: EmbPattern) = body(p).stitches.filter { it.command == Command.STITCH }
    private fun assertLimit(p: EmbPattern, limitMm: Double = 7.0) {
        p.stitches.zipWithNext().forEach { (a, b) ->
            if (a.command == Command.STITCH && b.command == Command.STITCH)
                assertTrue(hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()) <= limitMm * 10 + 1e-9, "$a → $b")
        }
    }

    @Test fun densityIsSameRailSpacingAndWidthIsRailDistance() {
        val p = column()
        val points = sewn(p)
        assertEquals(52, body(p).stitchCount) // 25 full 0.4 mm cycles + terminal pair, excluding locks
        assertEquals(40, p.bounds!!.width)
        assertEquals(100, p.bounds!!.height)
        assertEquals(setOf(-20, 20), points.map { it.x }.toSet())
        points.filter { it.x == -20 }.zipWithNext().forEach { (a, b) -> assertEquals(4, b.y - a.y) }
        points.filter { it.x == 20 }.zipWithNext().forEach { (a, b) -> assertTrue(b.y - a.y in 2..4) }
        assertEquals(102, body(column(options = SatinOptions(densityMm = 0.2))).stitchCount)
        assertEquals(1, p.jumpCount)
        assertEquals(Command.END, p.stitches.last().command)
        assertLimit(p)
    }

    @Test fun pullCompensationExtendsEachSideAndDoesNotExtendTheColumnLength() {
        val p = column(options = SatinOptions(pullCompensationMm = 0.3))
        assertEquals(46, p.bounds!!.width)
        assertEquals(100, p.bounds!!.height)
        assertEquals(column().stitchCount, p.stitchCount)
    }

    @Test fun wideAndDiagonalStitchesSplitAfterQuantization() {
        val wide = column(16.0)
        assertTrue(wide.stitchCount > column().stitchCount)
        assertEquals(160, wide.bounds!!.width)
        assertTrue(sewn(wide).any { it.x != -80 && it.x != 80 })
        assertLimit(wide)
        val diagonal = SatinColumn.generate(listOf(0.0 to 0.0, 9.31 to 10.17), listOf(7.0 to 0.0, 16.31 to 10.17))
        assertLimit(diagonal)
        assertLimit(column(7.0)) // Hypotenuse is just greater than 7 mm.
        assertLimit(column(6.0, SatinOptions(maxStitchLengthMm = 3.0)), 3.0)
    }

    @Test fun centerAndZigzagUnderlaysPrecedeTheSameTopLayerWithoutExtraJumps() {
        val top = sewn(column())
        val center = column(options = SatinOptions(centerUnderlay = true))
        val zigzag = column(options = SatinOptions(zigzagUnderlay = true))
        val both = column(options = SatinOptions(centerUnderlay = true, zigzagUnderlay = true))
        assertEquals(top.size + 9, body(center).stitchCount) // center anchor + 4 out + 4 back
        assertEquals(88, body(zigzag).stitchCount) // coarse zigzag, split underlay legs, center return, top
        assertEquals(96, body(both).stitchCount) // center pass shares its anchor with the zigzag pass
        for (p in listOf(center, zigzag, both)) {
            assertEquals(top, sewn(p).takeLast(top.size))
            assertEquals(1, p.jumpCount)
            assertEquals(0, p.trimCount)
            assertEquals(column().bounds, p.bounds)
            assertLimit(p)
        }
        assertTrue(zigzag.stitchCount > top.size)
        assertTrue(both.stitchCount > zigzag.stitchCount)
        assertTrue(sewn(center).take(9).all { it.x == 0 })
        val under = sewn(both).dropLast(top.size)
        under.zipWithNext().forEach { (a, b) -> assertTrue(hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()) <= 25.0) }
    }

    @Test fun differentRailSamplingAndReversedDirectionProduceTheSameColumn() {
        val a = listOf(0.0 to 0.0, 0.0 to 10.0)
        val b = listOf(4.0 to 0.0, 4.0 to 2.5, 4.0 to 10.0)
        assertEquals(column().stitches, SatinColumn.generate(a, b.reversed()).stitches)
        assertEquals(column().stitches, SatinColumn.generate(a + a.last(), b).stitches)
    }

    @Test fun offsetCurvesHaveRequestedWidthAndClosedSeamsRemainClosed() {
        val arc = (0..40).map { val t = it * PI / 80; 10 * cos(t) to 10 * sin(t) }
        val (left, right) = SatinColumn.rails(arc, 2.0)
        left.zip(right).forEach { (a, b) -> assertEquals(2.0, hypot(a.first - b.first, a.second - b.second), 0.002) }
        assertLimit(SatinColumn.strokeToSatin(arc, 2.0))
        val closed = listOf(0.0 to 0.0, 10.0 to 0.0, 10.0 to 10.0, 0.0 to 10.0, 0.0 to 0.0)
        val rails = SatinColumn.rails(closed, 2.0)
        assertEquals(rails.first.first(), rails.first.last())
        assertEquals(rails.second.first(), rails.second.last())
        assertLimit(SatinColumn.strokeToSatin(closed, 2.0))
        assertEquals(20, SatinColumn.strokeToSatin(listOf(0.0 to 0.0, 0.0 to 10.0), 2.0).bounds!!.width)
        val cusp = SatinColumn.rails(listOf(0.0 to 0.0, 1.0 to 0.0, 0.0 to 0.001), 2.0)
        assertTrue((cusp.first + cusp.second).all { it.first.isFinite() && it.second.isFinite() })
    }

    @Test fun letteringPersistsSatinSettingsAndRetainsStrokeAndColorSeparation() {
        val red = EmbThread(0xFFB5492A.toInt())
        val blue = EmbThread(0xFF0000FF.toInt())
        val options = SatinOptions(widthMm = 3.0, densityMm = 0.5, maxStitchLengthMm = 6.0,
            pullCompensationMm = 0.2, centerUnderlay = true, zigzagUnderlay = true,
            underlayStitchLengthMm = 2.0, underlayDensityMm = 1.0)
        for (kind in FontKind.entries) {
            val obj = TextObject("satin", text = "HI", fontKind = kind, singleLine = true,
                contours = listOf(listOf(0.0 to 0.0, 0.0 to 10.0), listOf(5.0 to 0.0, 5.0 to 10.0)),
                contourSourceHeight = 10.0, contourCharacters = listOf(0, 1),
                thread = red, characterThreads = mapOf(1 to blue), stitchStyle = StitchStyle.SATIN, satinOptions = options)
            val p = obj.localPattern()
            assertEquals(listOf(red, blue), p.threads)
            assertEquals(1, p.colorChangeCount)
            assertTrue(p.trimCount > 0)
            assertLimit(p, 6.0)
            val project = Project(objects = listOf(obj))
            val restored = ProjectJson.decode(project.toJson())
            assertEquals(project, restored)
            assertEquals(project.flatten().stitches, restored.flatten().stitches)
            val pes = PesReader.read(PesWriter.write(p))
            assertEquals(p.bounds, pes.bounds)
            assertEquals(io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor.forSewout(p).stitchCount, pes.stitchCount)
        }
        val old = TextObject("legacy", text = "AB")
        assertEquals(SatinOptions(autoDensity = false), (TextObjectCodec.decode(JsonObject(TextObjectCodec.encode(old).filterKeys { it != "satinOptions" })) as TextObject).satinOptions)
        assertEquals(StitchStyle.BEAN, old.stitchStyle)
    }

    @Test fun emptyDegenerateAndInvalidInputsAreHandled() {
        assertEquals(0, SatinColumn.generate(emptyList(), emptyList()).stitchCount)
        assertEquals(0, SatinColumn.strokeToSatin(listOf(1.0 to 1.0, 1.0 to 1.0), 2.0).stitchCount)
        assertFailsWith<IllegalArgumentException> { SatinOptions(densityMm = 0.0) }
        assertFailsWith<IllegalArgumentException> { SatinOptions(maxStitchLengthMm = 7.1) }
        assertFailsWith<IllegalArgumentException> { SatinOptions(pullCompensationMm = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { SatinColumn.rails(listOf(0.0 to 0.0, 1.0 to 0.0), -1.0) }
        assertFailsWith<IllegalArgumentException> { SatinColumn.generate(listOf(0.0 to Double.NaN), emptyList()) }
    }
}
