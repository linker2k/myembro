// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/stitch_plan/lock_stitch.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.stitch_plan

import io.github.linker2k.myembro.engine.inkstitch.svg.PathEndPoints
import io.github.linker2k.myembro.engine.inkstitch.svg.Units
import io.github.linker2k.myembro.engine.inkstitch.utils.Point
import kotlin.test.*

/** Analytical fixtures from source formulas; the upstream test suite has no lock unit fixtures. */
class LockStitchesTest {
    @Test fun svgAttributesShareNamespaceDefaultAndScaleSemantics() {
        assertEquals(LockStitchParameters(), LockStitchParameters.fromAttributes(emptyMap()))
        val parsed = LockStitchParameters.fromAttributes(mapOf(
            "{http://inkstitch.org/namespace}ties" to "3", "inkstitch:force_lock_stitches" to "yes",
            "lock_start" to "triangle", "inkstitch:lock_end" to "custom",
            "lock_start_scale_mm" to "1.2", "lock_end_scale_percent" to "75",
            "lock_custom_end" to "1 1 -1 -1",
        ))
        assertEquals(3, parsed.ties)
        assertTrue(parsed.force_lock_stitches)
        assertEquals("triangle", parsed.lock_start)
        assertEquals(1.2, parsed.lock_start_scale_mm)
        assertEquals(75.0, parsed.lock_end_scale_percent)
        assertEquals("1 1 -1 -1", parsed.lock_custom_end)
        assertNull(LockStitches.fromParameters(parsed).start)
        assertNotNull(LockStitches.fromParameters(parsed).end)
    }
    private val ppm = Units.PIXELS_PER_MM
    private val line = listOf(Stitch(0.0, 0.0), Stitch(10 * ppm, 0.0))
    private fun assertPoints(expected: List<Point>, actual: List<Stitch>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (e, a) ->
            assertEquals(e.x, a.x, 1e-9)
            assertEquals(e.y, a.y, 1e-9)
        }
    }
    private fun mmPoints(vararg x: Double) = x.map { Point(it * ppm, 0.0) }

    @Test fun halfStitchUsesClampedLengthAndOmitsEndAnchor() {
        val lock = InkLockStitch()
        assertPoints(mmPoints(0.0, 0.75, 1.5, 0.75, 0.0), lock.stitches(line, LockPosition.START))
        assertPoints(mmPoints(9.25, 8.5, 9.25, 10.0), lock.stitches(line, LockPosition.END))
        assertTrue(lock.stitches(line, LockPosition.START).all { it.isLock })
        assertEquals(lock.stitches(line, LockPosition.START), InkLockStitch(scale_percent = 2.0, scale_mm = 12.0).stitches(line, LockPosition.START))
    }

    @Test fun halfStitchSkipsShortDirectionCandidatesAndPreservesPixelFallback() {
        val input = listOf(Stitch(0.0, 0.0), Stitch(0.5 * ppm, 0.0), Stitch(0.0, 0.8 * ppm))
        assertPoints(listOf(0.0, 0.4, 0.8, 0.4, 0.0).map { Point(0.0, it * ppm) }, InkLockStitch().stitches(input, LockPosition.START))
        val coincident = List(2) { Stitch(9.0, 3.0) }
        assertPoints(listOf(0.0, 0.25, 0.5, 0.25, 0.0).map { Point(9.0 + it, 3.0) }, InkLockStitch().stitches(coincident, LockPosition.START))
        val tooLate = listOf(Stitch(0.0, 0.0)) + List(15) { Stitch(0.1, 0.0) } + Stitch(20.0, 0.0)
        assertEquals(0.5, InkLockStitch().stitches(tooLate, LockPosition.START)[2].x)
    }

    @Test fun backForthUsesAbsoluteMmScaleAndFollowsPolyline() {
        val lock = InkLockStitch("back_forth")
        assertPoints(mmPoints(0.0, 0.7, 1.4, 0.7), lock.stitches(line, LockPosition.START))
        assertPoints(mmPoints(9.3, 8.6, 9.3, 10.0), lock.stitches(line, LockPosition.END))
        val corner = listOf(Stitch(0.0, 0.0), Stitch(1 * ppm, 0.0), Stitch(1 * ppm, 2 * ppm))
        assertPoints(listOf(Point(0.0, 0.0), Point(0.7 * ppm, 0.0), Point(ppm, 0.4 * ppm), Point(0.7 * ppm, 0.0)), lock.stitches(corner, LockPosition.START))
        assertEquals(lock.stitches(line, LockPosition.START), InkLockStitch("back_forth", scale_percent = 200.0).stitches(line, LockPosition.START))
    }

    @Test fun absoluteNegativeBranchAndLineClampingMatchSource() {
        val lock = InkLockStitch("custom", custom_path = "1")
        val start = lock.stitches(line, LockPosition.START)
        assertPoints(mmPoints(-0.7), start)
        assertFalse(start.single().isLock) // Source negative-distance branch does not attach the tag.
        val tiny = listOf(Stitch(0.0, 0.0), Stitch(0.1 * ppm, 0.0))
        assertPoints(mmPoints(0.0), lock.stitches(tiny, LockPosition.END))
        assertTrue(lock.stitches(tiny, LockPosition.END).single().isLock)
    }

    @Test fun everyBuiltinSvgLockRetainsEndpointCountsAndReverseOrientation() {
        val counts = mapOf("arrow" to 6, "bowtie" to 4, "cross" to 6, "star" to 5, "simple" to 3, "triangle" to 3, "zigzag" to 4)
        for ((type, count) in counts) {
            val start = InkLockStitch(type).stitches(line, LockPosition.START)
            val end = InkLockStitch(type).stitches(line, LockPosition.END)
            assertEquals(count, start.size, type)
            assertEquals(count, end.size, type)
            assertTrue(start.all { it.isLock } && end.all { it.isLock }, type)
            assertPoints(start.asReversed().map { Point(10 * ppm - it.x, -it.y) }, end)
            val twice = InkLockStitch(type, scale_percent = 200.0).stitches(line, LockPosition.START)
            assertPoints(start.map { Point(2 * it.x, 2 * it.y) }, twice)
        }
        // The star's C control points are not additional needle penetrations.
        assertEquals(5, InkLockStitch("star").stitches(line, LockPosition.START).size)
    }

    @Test fun svgAnchorsRotateTranslateScaleAndAreNotEmbroidered() {
        val lock = InkLockStitch("custom", scale_percent = 200.0, custom_path = "M 0 0 L 0 1 L 0 2 L 0 3")
        assertPoints(mmPoints(-4.0, -2.0), lock.stitches(line, LockPosition.START))
        assertPoints(mmPoints(12.0, 14.0), lock.stitches(line, LockPosition.END))
        val vertical = listOf(Stitch(4.0, 6.0), Stitch(4.0, 30.0))
        assertPoints(listOf(Point(4.0, 6.0 - 4 * ppm), Point(4.0, 6.0 - 2 * ppm)), lock.stitches(vertical, LockPosition.START))
    }

    @Test fun customClassificationAndUnknownTypeFallbackFollowSource() {
        val half = InkLockStitch().stitches(line, LockPosition.START)
        for (path in listOf("", " ", "1,2", "M0 0 L1 0")) {
            assertEquals(half, InkLockStitch("custom", custom_path = path).stitches(line, LockPosition.START), path)
        }
        assertEquals(half, InkLockStitch("unknown").stitches(line, LockPosition.START))
        assertEquals(InkLockStitch("back_forth").stitches(line, LockPosition.START), InkLockStitch("custom", custom_path = "1  1 -1 -1").stitches(line, LockPosition.START))
        assertFailsWith<IllegalArgumentException> { InkLockStitch("custom", custom_path = "not a path").stitches(line, LockPosition.START) }
    }

    @Test fun tiesAndForceUseSourceStartEndRules() {
        for (ties in 0..3) for (force in listOf(false, true)) {
            val locks = LockStitches.fromParameters(LockStitchParameters(ties = ties, force_lock_stitches = force))
            assertEquals(ties == 0 || ties == 1, locks.start != null)
            assertEquals(ties == 0 || ties == 2 || force, locks.end != null)
            val group = StitchGroup(stitches = line, lockStart = locks.start, lockEnd = locks.end)
            assertTrue(group.lockStitches(LockPosition.START, disableTies = true).isEmpty())
            assertTrue(group.lockStitches(LockPosition.END, disableTies = true).isEmpty())
        }
        val pair = LockStitches.fromParameters(LockStitchParameters(lock_start = "custom", lock_custom_start = "1", lock_start_scale_mm = 2.0, lock_end = "custom", lock_custom_end = "1", lock_end_scale_mm = 3.0))
        assertPoints(mmPoints(-2.0), pair.start!!.stitches(line, LockPosition.START))
        assertPoints(mmPoints(7.0), pair.end!!.stitches(line, LockPosition.END))
    }

    @Test fun endpointParserHandlesAllSvgCommandsWithoutCurveSampling() {
        val path = "M1 2 3 4 l1 -2 h3 v4 C0 0 1 1 8 9 s1 2 3 4 Q0 0 5 6 t2 3 A2 3 0 0 1 9 10 z m2 3"
        assertEquals(listOf(Point(1.0, 2.0), Point(3.0, 4.0), Point(4.0, 2.0), Point(7.0, 2.0), Point(7.0, 6.0), Point(8.0, 9.0), Point(11.0, 13.0), Point(5.0, 6.0), Point(7.0, 9.0), Point(9.0, 10.0), Point(1.0, 2.0), Point(3.0, 5.0)), PathEndPoints.parse(path))
        assertEquals(listOf(Point(100.0, -0.5), Point(0.2, 3.0)), PathEndPoints.parse("M1e2-.5L.2+3"))
        assertEquals(listOf(Point(0.0, 0.0), Point(1.0, 2.0)), PathEndPoints.parse("M0 0 A1 1 0 011 2"))
        for (invalid in listOf("M1", "M1 2 L", "M1 2 ! L3 4", "X1 2")) assertFailsWith<IllegalArgumentException>(invalid) { PathEndPoints.parse(invalid) }
    }

    @Test fun emptyOrOnePointInputsDoNotInventLocksAndNonFiniteScaleIsRejected() {
        for (type in LockStitches.types) {
            assertTrue(InkLockStitch(type).stitches(emptyList(), LockPosition.START).isEmpty())
            assertTrue(InkLockStitch(type).stitches(line.take(1), LockPosition.END).isEmpty())
        }
        assertFailsWith<IllegalArgumentException> { InkLockStitch(scale_mm = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { InkLockStitch(scale_percent = Double.POSITIVE_INFINITY) }
    }
}
