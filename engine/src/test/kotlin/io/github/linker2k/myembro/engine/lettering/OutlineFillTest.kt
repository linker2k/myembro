// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.geometry.*
import io.github.linker2k.myembro.engine.geometry.Polyline as GeometryPath
import io.github.linker2k.myembro.engine.io.PesReader
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor
import io.github.linker2k.myembro.engine.model.*
import kotlinx.serialization.json.JsonObject
import kotlin.math.*
import kotlin.test.*

class OutlineFillTest {
    private fun Double?.orZero() = this ?: 0.0
    private fun box(x: Double, y: Double, w: Double, h: Double) = listOf(x to y, x + w to y, x + w to y + h, x to y + h, x to y)
    private val ring = listOf(box(-6.0, -6.0, 12.0, 12.0), box(-3.0, -3.0, 6.0, 6.0))
    private val noExtras = FillOptions(pullCompensationMm = 0.0, underlay = false, border = FillBorder.NONE,
        strategy = FillStrategy.TATAMI, autoDensity = false)
    private fun assertLengths(pattern: EmbPattern) {
        // All penetrations remain finite, nonzero and under the machine maximum.
        // The fill-body minimum excludes deliberate short reversals/corners of locks.
        pattern.stitches.zipWithNext().forEach { (a, b) ->
            if (a.command == Command.STITCH && b.command == Command.STITCH) {
                val d = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())
                assertTrue(d in 1.0..70.0, "${d / 10}mm: $a → $b")
            }
        }
    }
    private fun assertAvoidsHole(pattern: EmbPattern, rawHole: PolygonRegion) {
        val hole = rawHole.transformed(Affine2D.translation(-pattern.metadata["geometry.centerXUnits"].orEmpty().toDoubleOrNull().orZero(),
            -pattern.metadata["geometry.centerYUnits"].orEmpty().toDoubleOrNull().orZero()))
        pattern.stitches.zipWithNext().forEach { (a, b) ->
            if (a.command == Command.STITCH && b.command == Command.STITCH) {
                val start = Point(a.x.toDouble(), a.y.toDouble())
                val end = Point(b.x.toDouble(), b.y.toDouble())
                for (i in 0..20) assertFalse(hole.covers(start.interpolate(end, i / 20.0)), "Sewn through a counter: $a → $b")
            }
        }
    }
    private fun region(paths: List<List<Pair<Double, Double>>>) = PolygonRegion.fromPaths(paths.map { path ->
        GeometryPath(path.map { (x, y) -> Point(x * 10, y * 10) }, true)
    }, FillRule.EVEN_ODD)

    @Test fun evenOddPreservesMieumIeungAndBRegardlessOfWinding() {
        val circle = (0..64).map { i -> val t = i * PI / 32; 6 * cos(t) to 6 * sin(t) }
        val inner = circle.map { (x, y) -> x / 2 to y / 2 }
        val b = listOf(box(-6.0, -6.0, 12.0, 12.0), box(-3.0, -4.0, 6.0, 2.0), box(-3.0, 2.0, 6.0, 2.0))
        for (shape in listOf(ring, listOf(circle, inner), b)) for (angle in listOf(0.0, 37.0, 90.0)) {
            val p = OutlineFill.generate(shape, noExtras.copy(angleDeg = angle))
            assertTrue(p.stitchCount > 10)
            assertLengths(p)
            val holes = if (shape == b) listOf(box(-2.8, -3.8, 5.6, 1.6), box(-2.8, 2.2, 5.6, 1.6))
                else if (shape == ring) listOf(box(-2.8, -2.8, 5.6, 5.6))
                else listOf(inner.map { (x, y) -> x * 0.9 to y * 0.9 })
            holes.forEach { assertAvoidsHole(p, region(listOf(it))) }
            val reversed = OutlineFill.generate(shape.map { it.reversed() }, noExtras.copy(angleDeg = angle))
            assertEquals(p.bounds, reversed.bounds)
        }
    }

    @Test fun tinyCountersSurvivePullCompensationAndNestedIslandsKeepParity() {
        val shape = listOf(box(-5.0, -5.0, 10.0, 10.0), box(-1.0, -1.0, 2.0, 2.0))
        val p = OutlineFill.generate(shape, FillOptions(pullCompensationMm = 1.0))
        assertAvoidsHole(p, region(listOf(box(-0.8, -0.8, 1.6, 1.6))))
        assertEquals(120, p.bounds!!.width)
        val nested = OutlineFill.generate(ring + listOf(box(-1.0, -1.0, 2.0, 2.0)), noExtras)
        assertTrue(nested.stitches.any { it.command == Command.STITCH && abs(it.x) < 10 && abs(it.y) < 10 })
    }

    @Test fun densityAngleUnderlayAndBorderAffectActualNeedlePositions() {
        val shape = listOf(box(-6.0, -6.0, 12.0, 12.0))
        val plain = OutlineFill.generate(shape, noExtras)
        val dense = OutlineFill.generate(shape, noExtras.copy(densityMm = 0.3))
        assertTrue(dense.stitchCount > plain.stitchCount)
        val vertical = OutlineFill.generate(shape, noExtras.copy(angleDeg = 90.0))
        assertNotEquals(plain.stitches, vertical.stitches)
        val underlay = OutlineFill.generate(shape, noExtras.copy(underlay = true))
        val border = OutlineFill.generate(shape, noExtras.copy(border = FillBorder.RUNNING))
        val bean = OutlineFill.generate(shape, noExtras.copy(border = FillBorder.BEAN))
        assertTrue(underlay.stitchCount > plain.stitchCount)
        assertTrue(border.stitchCount > plain.stitchCount)
        assertTrue(bean.stitchCount > border.stitchCount)
        listOf(plain, dense, vertical, underlay, border, bean).forEach(::assertLengths)
        assertEquals(1, plain.jumpCount)
    }

    @Test fun disconnectedRowsUseHiddenRunsAroundCountersAndLettersStillTrimAndJump() {
        val one = OutlineFill.generate(ring, noExtras)
        assertTrue(one.jumpCount <= 3, "Expected hidden routing, got ${one.jumpCount} jumps")
        val two = TextObject("letters", text = "ㅇㅇ", fontKind = FontKind.OUTLINE,
            contours = ring + ring.map { path -> path.map { (x, y) -> x + 20 to y } },
            contourCharacters = listOf(0, 0, 1, 1), heightMm = 12.0, contourSourceHeight = 12.0,
            stitchStyle = StitchStyle.FILL, fillOptions = noExtras).localPattern()
        assertEquals(1, two.trimCount)
        assertTrue(two.jumpCount >= 2)
        assertLengths(two)
    }

    @Test fun narrowAndWideTtfRequestsUseFillWithAnExplicitWarning() {
        val narrow = OutlineFill.generate(listOf(box(-1.5, -6.0, 3.0, 12.0)), noExtras, satin = true)
        assertTrue(narrow.warnings.isNotEmpty())
        assertLengths(narrow)
        val wide = OutlineFill.generate(listOf(box(-6.0, -12.0, 12.0, 24.0)), noExtras, satin = true)
        assertTrue(wide.warnings.isNotEmpty())
        assertLengths(wide)
        wide.stitches.zipWithNext().forEach { (a, b) ->
            if (a.command == Command.STITCH && b.command == Command.STITCH)
                assertTrue(hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()) <= 40.0)
        }
    }

    @Test fun multiColorFillPersistsAndRoundTripsThroughPesWithTheSameHoles() {
        val red = EmbThread(0xFFB5492A.toInt())
        val blue = EmbThread(0xFF0000FF.toInt())
        val obj = TextObject("fill", text = "B\nB", fontKind = FontKind.OUTLINE, fontId = "offline-user-font",
            contours = ring + ring.map { path -> path.map { (x, y) -> x to y + 20 } },
            contourCharacters = listOf(0, 0, 2, 2), contourBaselines = listOf(0.0, 0.0, 20.0, 20.0),
            heightMm = 12.0, contourSourceHeight = 12.0, thread = red, characterThreads = mapOf(2 to blue),
            stitchStyle = StitchStyle.FILL, fillOptions = FillOptions(densityMm = 0.5, angleDeg = 45.0, border = FillBorder.RUNNING, underlay = false))
        val project = Project(objects = listOf(obj))
        val restored = ProjectJson.decode(project.toJson())
        assertEquals(project, restored)
        assertEquals(project.flatten().stitches, restored.flatten().stitches)
        val pattern = obj.localPattern()
        assertLengths(pattern)
        assertEquals(listOf(red, blue), pattern.threads)
        assertEquals(1, pattern.colorChangeCount)
        val pes = PesReader.read(PesWriter.write(pattern))
        assertEquals(pattern.bounds, pes.bounds)
        assertEquals(pattern.stitchCount, pes.stitchCount)
        assertLengths(pes)
        val old = TextObject("old", text = "AB")
        val payload = JsonObject(TextObjectCodec.encode(old).filterKeys { it != "fillOptions" })
        val legacy = TextObjectCodec.decode(payload) as TextObject
        assertEquals(FillStrategy.TATAMI, legacy.fillOptions.strategy)
        assertFalse(legacy.fillOptions.autoDensity)
        assertEquals(old.localPattern().stitches, legacy.localPattern().stitches)
    }

    @Test fun denseBeanRetainsSourceNeedlesAndUsesSharedLocks() {
        val obj = TextObject("dense", text = "B\nB", fontKind = FontKind.OUTLINE,
            contours = ring + ring.map { path -> path.map { (x,y) -> x to y+20 } },
            contourCharacters = listOf(0,0,2,2), contourBaselines = listOf(0.0,0.0,20.0,20.0),
            heightMm = 12.0, contourSourceHeight = 12.0, stitchStyle = StitchStyle.FILL,
            fillOptions = FillOptions(densityMm = 0.5, angleDeg = 45.0, border = FillBorder.BEAN))
        val pattern = obj.localPattern()
        assertTrue(pattern.stitchCount > 100)
        assertTrue(pattern.metadata.containsKey(io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor.FINISHED_KEY))
        assertLengths(pattern)
    }

    @Test fun emptyDegenerateAndInvalidFillInputsAreSafe() {
        assertEquals(0, OutlineFill.generate(emptyList()).stitchCount)
        assertEquals(0, OutlineFill.generate(listOf(listOf(0.0 to 0.0, 1.0 to 1.0))).stitchCount)
        assertFailsWith<IllegalArgumentException> { FillOptions(densityMm = 0.1) }
        assertFailsWith<IllegalArgumentException> { FillOptions(angleDeg = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { LetteringEngine.generate("A", stitchStyle = StitchStyle.FILL) }
    }
}
