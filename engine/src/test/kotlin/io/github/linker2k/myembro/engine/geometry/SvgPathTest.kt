// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.geometry

import kotlin.math.*
import kotlin.test.*

class SvgPathTest {
    @Test fun relativeImplicitCommandsExponentsSubpathsAndCloseAreHandled() {
        val paths = SvgPath.flatten("m1 2 3-4h2v5z m1e1 .5 l.5.5")
        assertEquals(listOf(Point(1.0, 2.0), Point(4.0, -2.0), Point(6.0, -2.0), Point(6.0, 3.0), Point(1.0, 2.0)), paths[0].points)
        assertTrue(paths[0].closed)
        assertFalse(paths[1].closed)
        assertEquals(listOf(Point(11.0, 2.5), Point(11.5, 3.0)), paths[1].points)
        assertEquals(Point(3.0, 4.0), SvgPath.flatten("M1 2Z L3 4").last().points.last())
    }

    @Test fun transformOrderCenterRotationSkewAndNestedComposition() {
        val t = Affine2D.parse("translate(10,20) scale(2 3)")
        assertEquals(Point(12.0, 26.0), t.apply(Point(1.0, 2.0)))
        val r = Affine2D.parse("rotate(90 10 20)").apply(Point(20.0, 20.0))
        assertEquals(10.0, r.x, 1e-9); assertEquals(30.0, r.y, 1e-9)
        assertEquals(Point(7.0, 10.0), Affine2D.parse("matrix(1 2 3 4 0 0)").apply(Point(1.0, 2.0)))
        val skew = Affine2D.parse("skewX(45) skewY(45)").apply(Point(1.0, 0.0))
        assertEquals(2.0, skew.x, 1e-9); assertEquals(1.0, skew.y, 1e-9)
        assertEquals(Point(12.0, 26.0), SvgPath.flatten("M1 2", t).single().points.single())
    }

    private fun distance(p: Point, paths: List<Polyline>): Double = paths.flatMap { it.points.zipWithNext() }.minOf { (a, b) ->
        val dx = b.x-a.x; val dy = b.y-a.y
        val squared = dx*dx+dy*dy
        val t = if (squared == 0.0) 0.0 else (((p.x-a.x)*dx+(p.y-a.y)*dy)/squared).coerceIn(0.0, 1.0)
        p.distance(a.interpolate(b, t))
    }

    @Test fun bezierFlatteningBoundsErrorAfterNonuniformTransformAndRetainsLoops() {
        val matrix = Affine2D.parse("scale(4 2) skewX(20)")
        val cubic = SvgPath.flatten("M0 0C0 100 100 100 100 0", matrix, 0.2)
        val quad = SvgPath.flatten("M0 0Q50 100 100 0", matrix, 0.2)
        for (i in 0..1000) {
            val t = i/1000.0; val u = 1-t
            assertTrue(distance(matrix.apply(Point(300*u*t*t+100*t*t*t, 300*u*t)), cubic) <= 0.201)
            assertTrue(distance(matrix.apply(Point(100*t, 200*u*t)), quad) <= 0.201)
        }
        assertTrue(SvgPath.flatten("M0 0C100 0 -100 0 0 0").single().points.size > 4)
        val smooth = SvgPath.flatten("M0 0C0 10 10 10 10 0S20-10 20 0Q25 10 30 0T40 0")
        assertEquals(Point(40.0, 0.0), smooth.single().points.last())
        assertTrue(smooth.single().points.any { it.y < -1 })
    }

    @Test fun arcsHandleFlagsRadiusCorrectionDegeneracyRotationAndTransforms() {
        val circle = SvgPath.flatten("M100 0A100 100 0 01100 200", tolerance = 0.2)
        assertEquals(Point(100.0, 200.0), circle.single().points.last())
        assertTrue(circle.single().points.all { abs(hypot(it.x-100, it.y-100)-100) < 1e-7 })
        val corrected = SvgPath.flatten("M0 0A1 1 0 0 1 100 0").single().points
        assertTrue(corrected.any { it.y < -40 })
        assertEquals(listOf(Point(0.0, 0.0), Point(100.0, 0.0)), SvgPath.flatten("M0 0A0 10 0 0 1 100 0").single().points)
        assertEquals(1, SvgPath.flatten("M10 10A20 20 0 1 0 10 10").single().points.size)
        val matrix = Affine2D.parse("rotate(31) scale(2 3)")
        val arc = SvgPath.flatten("M100 0 A100 100 0 1 1 -100 0", matrix, 0.2)
        for (i in 0..1000) {
            val r = PI*i/1000
            assertTrue(distance(matrix.apply(Point(100*cos(r), 100*sin(r))), arc) <= 0.201)
        }
        assertTrue(SvgPath.flatten("M0 0 A30 20 45 1 0 40 20").single().points.size > 4)
    }

    @Test fun malformedAndExcessiveInputsFailWithoutPartialOutput() {
        for (data in listOf("L0 0", "M0", "M0 0X1 2", "M0 0A1 1 0 2 0 1 1", "M0 0LNaN 0", "M0 0L1e999 0", "M0 0?")) {
            assertFailsWith<IllegalArgumentException>(data) { SvgPath.flatten(data) }
        }
        assertFailsWith<IllegalArgumentException> { SvgPath.flatten("M0 0", tolerance = 0.0) }
        assertFailsWith<IllegalArgumentException> { Affine2D.parse("translate(1 2 3)") }
        assertFailsWith<IllegalArgumentException> { SvgPath.flatten("M0 0A100 100 0 0 1 100 100", tolerance = 1e-100) }
    }
}
