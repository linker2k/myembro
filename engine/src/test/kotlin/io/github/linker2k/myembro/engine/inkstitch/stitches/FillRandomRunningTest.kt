// Ported from Ink/Stitch lib/stitches/running_stitch.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
// Ported from Ink/Stitch lib/utils/prng.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.stitches

import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** v3.3.0 has no running-stitch unit tests; explicit hash and analytic line fixtures are used. */
class FillRandomRunningTest {
    private val line = listOf(FillPoint(0.0, 0.0), FillPoint(100.0, 0.0))

    @Test fun hashWordOrderAndNinthDrawMatchIndependentPythonHashlibVectors() {
        // Python stdlib hashlib only; Ink/Stitch and its dependencies were never executed.
        val words = listOf(1814511174L, 4122625294L, 3917149874L, 2548421773L,
            3088444479L, 2252077958L, 3134518381L, 780811117L, 2098478400L)
        assertEquals(words.map { it.toDouble() / 4294967295.0 },
            FillRandomRunning.uniformFloats("line/0").take(9).toList())
        assertEquals(636304575L.toDouble() / 4294967295.0, FillRandomRunning.uniformFloats("σ/0").first())
    }

    @Test fun firstSixLineStitchesFollowOriginalCoupledPhaseAndUniformLengthFormula() {
        // First = r*(9+2*r), subsequent = previous+(9+2*r), seed line/0/0.
        val expected = listOf(4.159232473165594, 15.078979475104056, 25.903044528432943,
            36.089745892127496, 46.52791492433197, 56.5766204456158)
        val stitches = FillRandomRunning.run(line, 10.0, 0.1, 0.1, "line")
        for ((i, x) in expected.withIndex()) assertEquals(x, stitches[i + 1].x, 1e-10)
        assertEquals(line.first(), stitches.first())
        assertEquals(line.last(), stitches.last())
        assertTrue(stitches.all { it.y == 0.0 })
    }

    @Test fun zeroSigmaStillHasRandomPhaseThenNominalLength() {
        val stitches = FillRandomRunning.run(line, 10.0, 0.1, 0.0, "line")
        assertEquals(10 * 1814511174L.toDouble() / 4294967295.0, stitches[1].x, 1e-10)
        assertFalse(stitches[1].x == 10.0)
        for ((a, b) in stitches.drop(1).dropLast(1).zipWithNext()) assertEquals(10.0, b.x - a.x, 1e-10)
        assertTrue(stitches.last().distance(stitches[stitches.lastIndex - 1]) <= 10.0 + 1e-10)
    }

    @Test fun scalarAndSingleLengthListAreIdenticalAndSeedsAreDeterministic() {
        val expected = FillRandomRunning.run(line, 10.0, 0.1, 0.2, "fixed")
        assertEquals(expected, FillRandomRunning.run(line, listOf(10.0), 0.1, 0.2, "fixed"))
        assertEquals(expected, FillRandomRunning.run(line, 10.0, 0.1, 0.2, "fixed"))
        assertFalse(expected == FillRandomRunning.run(line, 10.0, 0.1, 0.2, "different"))
        assertFalse(expected == FillRunning.run(line, 10.0, 0.1))
    }

    @Test fun interiorStraightStitchesRespectRelativeBoundsAndFirstPhaseException() {
        val long = listOf(FillPoint(0.0, 0.0), FillPoint(5000.0, 0.0))
        val stitches = FillRandomRunning.run(long, 10.0, 0.1, 0.25, "distribution")
        val interior = stitches.drop(1).dropLast(1).zipWithNext().map { (a, b) -> a.distance(b) }
        assertTrue(interior.size > 300)
        assertTrue(interior.all { it >= 7.5 - 1e-9 && it <= 12.5 + 1e-9 })
        assertTrue(interior.min() < 8.0 && interior.max() > 12.0)
    }

    @Test fun obviousCornersAreExactAndEachCurveUsesItsOwnSeedSuffix() {
        val corner = FillPoint(50.0, 0.0)
        val path = listOf(line.first(), corner, FillPoint(50.0, 50.0))
        val stitches = FillRandomRunning.run(path, 10.0, 0.1, 0.1, "line")
        assertEquals(1, stitches.count { it == corner })
        val index = stitches.indexOf(corner)
        val r = 981164635L.toDouble() / 4294967295.0 // hashlib("line/1/0") first word
        assertEquals(r * (9 + 2 * r), stitches[index + 1].y, 1e-10)
        assertEquals(50.0, stitches[index + 1].x, 1e-10)
        assertEquals(path.last(), stitches.last())
    }

    @Test fun curveApiExcludesStartAndDoesNotAddAnExtraCurveSeedComponent() {
        val curve = FillRandomRunning.curveRandomly(line, listOf(10.0), 0.1, 0.1, "line")
        val r = 118398203L.toDouble() / 4294967295.0 // hashlib("line/0"), not "line/0/0"
        assertEquals(r * (9 + 2 * r), curve.stitches.first().x, 1e-10)
        assertEquals(line.last(), curve.stitches.last())
        assertEquals(0, curve.stitchLengthPosition)
    }

    @Test fun alternatingLengthsCycleAcrossCurvesWithoutResettingTheirPosition() {
        val points = listOf(FillPoint(0.0, 0.0), FillPoint(35.0, 0.0), FillPoint(35.0, 40.0))
        val first = FillRandomRunning.curveRandomly(points.take(2), listOf(3.0, 7.0), 0.1, 0.0, "cycle/0")
        assertEquals(first.stitches.size % 2, first.stitchLengthPosition)
        val second = FillRandomRunning.curveRandomly(points.drop(1), listOf(3.0, 7.0), 0.1, 0.0,
            "cycle/1", first.stitchLengthPosition)
        assertEquals(listOf(points.first()) + first.stitches + second.stitches,
            FillRandomRunning.run(points, listOf(3.0, 7.0), 0.1, 0.0, "cycle"))
    }

    @Test fun gentlyCurvedInputStaysInsideToleranceSleeveAndLengthCap() {
        val path = (0..200).map { i -> FillPoint(i.toDouble(), sin(i * 0.04) * 3.0) }
        val tolerance = 0.2
        val sigma = 0.15
        val stitches = FillRandomRunning.run(path, 10.0, tolerance, sigma, "wave")
        val allowed = FillGeometry.line(path).buffer(tolerance + 1e-7)
        assertTrue(stitches.zipWithNext().all { (a, b) -> a.distance(b) <= 11.5 + 1e-8 })
        assertTrue(stitches.zipWithNext().all { (a, b) -> allowed.covers(FillGeometry.line(listOf(a, b))) })
        assertEquals(path.first(), stitches.first())
        assertEquals(path.last(), stitches.last())
    }

    @Test fun singletonAndDuplicateEndpointsMatchSourcePhaseBehavior() {
        val point = FillPoint(1.0, 2.0)
        assertTrue(FillRandomRunning.run(emptyList(), 10.0, 0.1).isEmpty())
        assertEquals(listOf(point), FillRandomRunning.run(listOf(point), 10.0, 0.1))
        assertEquals(listOf(point, point), FillRandomRunning.run(listOf(point, point), 10.0, 0.1))
        assertTrue(FillRandomRunning.curveRandomly(listOf(point), listOf(10.0), 0.1, 0.1, "line").stitches.isEmpty())
    }

    @Test fun sigmaGreaterThanOneUsesZeroLowerBoundWithoutClampingTheUserValue() {
        val stitches = FillRandomRunning.run(line, 10.0, 0.1, 2.0, "line")
        val r = 1814511174L.toDouble() / 4294967295.0
        assertEquals(r * 30.0 * r, stitches[1].x, 1e-10)
        assertTrue(stitches.zipWithNext().all { (a, b) -> a.distance(b) <= 30.0 + 1e-8 })
    }

    @Test fun invalidValuesFailBeforeRandomIteration() {
        assertFailsWith<IllegalArgumentException> { FillRandomRunning.run(line, 0.0, 0.1) }
        assertFailsWith<IllegalArgumentException> { FillRandomRunning.run(line, listOf(10.0, -1.0), 0.1) }
        assertFailsWith<IllegalArgumentException> { FillRandomRunning.run(line, emptyList<Double>(), 0.1) }
        assertFailsWith<IllegalArgumentException> { FillRandomRunning.run(line, 10.0, -0.1) }
        assertFailsWith<IllegalArgumentException> { FillRandomRunning.run(line, 10.0, 0.1, -0.1) }
        assertFailsWith<IllegalArgumentException> { FillRandomRunning.run(line, 10.0, 0.1, Double.NaN) }
        assertFailsWith<IllegalArgumentException> { FillRandomRunning.run(line, 10.0, 0.1, Double.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { FillRandomRunning.curveRandomly(line, listOf(10.0), 0.1, 0.1, "line", 1) }
    }
}
