// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/stitches/running_stitch.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.stitches

import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.Stitch
import io.github.linker2k.myembro.engine.inkstitch.utils.Point
import kotlin.math.hypot
import kotlin.test.*

/** Analytic fixtures; upstream tests/ has no running-stitch tests. No upstream Python is executed. */
class RunningStitchTest {
    private fun point(x: Double, y: Double = 0.0) = Point(x, y)
    private fun stitch(x: Double, y: Double = 0.0) = Stitch(x, y)
    private fun assertPoints(expected: List<Point>, actual: List<Point>, epsilon: Double = 1e-5) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (e, a) -> assertEquals(e.x, a.x, epsilon); assertEquals(e.y, a.y, epsilon) }
    }

    @Test fun evenlyDividesEachStraightCurveAndPreservesEndpoint() {
        assertPoints(listOf(point(0.0), point(2.5), point(5.0), point(7.5), point(10.0)),
            RunningStitch.runningStitch(listOf(point(0.0), point(10.0)), listOf(3.0), 0.2))
        assertPoints(listOf(point(0.0), point(1.0)), RunningStitch.evenRunningStitch(listOf(point(0.0), point(1.0)), listOf(3.0), 0.2))
    }

    @Test fun fixedLengthDoesNotRedistributeTheRemainder() {
        val result = RunningStitch.evenRunningStitch(listOf(point(0.0), point(10.0)), listOf(3.0), 0.2, adapt_to_length = false)
        assertPoints(listOf(point(0.0), point(3.0), point(6.0), point(9.0), point(10.0)), result)
    }

    @Test fun multipleLengthsCycleAcrossCorners() {
        val result = RunningStitch.evenRunningStitch(listOf(point(0.0), point(4.0), point(4.0, 4.0)), listOf(2.0, 4.0), 0.2)
        assertPoints(listOf(point(0.0), point(2.0), point(4.0), point(4.0, 2.0), point(4.0, 4.0)), result)
    }

    @Test fun sharpCornersStayExactAndZeroLengthVerticesAreSafe() {
        val points = listOf(point(0.0), point(4.0), point(4.0), point(4.0, 4.0))
        val curves = RunningStitch.pathToCurves(points, 0.4)
        assertEquals(2, curves.size)
        assertEquals(point(4.0), curves[0].last())
        assertTrue(RunningStitch.runningStitch(points, listOf(3.0), 0.2).contains(point(4.0)))
        assertEquals(listOf(point(0.0)), RunningStitch.runningStitch(List(3) { point(0.0) }, listOf(3.0), 0.2))
    }

    @Test fun toleranceSleeveCanBridgeAShallowCurveButNotALargerDeviation() {
        val points = listOf(point(0.0), point(1.0, 0.1), point(2.0))
        assertPoints(listOf(point(0.0), point(2.0)), RunningStitch.runningStitch(points, listOf(3.0), 0.2))
        val tight = RunningStitch.runningStitch(points, listOf(3.0), 0.01)
        assertTrue(tight.size > 2)
        assertTrue(tight.any { it.y > 0.05 })
        assertEquals(points.last(), tight.last())
    }

    @Test fun beanPatternsUseOriginalSegmentIndexAndHonorDestinationTags() {
        val result = RunningStitch.beanStitch((0..3).map { stitch(it.toDouble()) }, listOf(0, 1, 2))
        assertEquals(listOf(0.0,1.0,2.0,1.0,2.0,3.0,2.0,3.0,2.0,3.0), result.map { it.x })
        val source = listOf(stitch(0.0), stitch(1.0).copy(tags = setOf("travel")), stitch(2.0))
        val skipped = RunningStitch.beanStitch(source, listOf(1, 2), setOf("travel"))
        assertEquals(listOf(0.0,1.0,2.0,1.0,2.0,1.0,2.0), skipped.map { it.x })
        assertEquals(setOf("travel"), skipped[1].tags)
        assertEquals(source, source.toList())
    }

    @Test fun zigzagKeepsCompleteCyclesAndAsymmetricPullCompensation() {
        val source = listOf(stitch(0.0), stitch(4.0))
        val result = RunningStitch.zigzagStitch(source, listOf(2.0), 2.0, 0.5 to 1.0)
        assertEquals(listOf(0.0,1.0,2.0,3.0,4.0), result.map { it.x })
        assertEquals(listOf(2.0,-1.5,2.0,-1.5,2.0), result.map { it.y })
        assertEquals(listOf(stitch(0.0), stitch(4.0)), source)
        val rounded = RunningStitch.zigzagStitch(listOf(stitch(0.0), stitch(2.5)), listOf(2.0), 2.0)
        assertEquals(3, rounded.size) // Python round(2.5) == 2, then two complete segments.
    }

    @Test fun zigzagRotationAndAveragedCornerTangentsMatchAnalyticGeometry() {
        val rotated = RunningStitch.zigzagStitch(listOf(stitch(0.0), stitch(2.0)), listOf(2.0), 2.0, zigzag_angle = 90.0)
        assertEquals(-1.0, rotated.first().x, 1e-12)
        assertEquals(2.0, rotated[1].x, 1e-12)
        val corner = RunningStitch.zigzagStitch(listOf(stitch(0.0), stitch(2.0), stitch(2.0, 2.0)), listOf(2.0), 2.0)
        assertEquals(2 - 1 / kotlin.math.sqrt(2.0), corner[2].x, 1e-12)
        assertEquals(1 / kotlin.math.sqrt(2.0), corner[2].y, 1e-12)
    }

    @Test fun randomIsDeterministicAndPreservesEndpointsAndLengthBound() {
        val points = listOf(point(0.0), point(20.0))
        val a = RunningStitch.randomRunningStitch(points, listOf(3.0), 0.2, 0.1, "seed")
        val b = RunningStitch.randomRunningStitch(points, listOf(3.0), 0.2, 0.1, "seed")
        val c = RunningStitch.randomRunningStitch(points, listOf(3.0), 0.2, 0.1, "other")
        assertEquals(a, b)
        assertNotEquals(a, c)
        assertEquals(points.first(), a.first())
        assertEquals(points.last(), a.last())
        assertTrue(a.zipWithNext().all { (x, y) -> hypot(x.x - y.x, x.y - y.y) <= 3.3 + 1e-10 })
    }

    @Test fun emptySinglePointAndInvalidLengths() {
        assertEquals(emptyList(), RunningStitch.runningStitch(emptyList(), listOf(3.0), 0.2))
        assertEquals(listOf(point(0.0)), RunningStitch.runningStitch(listOf(point(0.0)), listOf(3.0), 0.2))
        assertFailsWith<IllegalArgumentException> { RunningStitch.runningStitch(listOf(point(0.0), point(1.0)), listOf(0.0), 0.2) }
        assertFailsWith<IllegalArgumentException> { RunningStitch.runningStitch(listOf(point(0.0)), listOf(3.0), -0.2) }
        assertFailsWith<IllegalArgumentException> { RunningStitch.beanStitch(listOf(stitch(0.0)), emptyList()) }
    }
}
