// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.geometry

import kotlin.test.*

class GeometryTest {
    private val outer = "M0 0H100V100H0Z"
    private val inner = "M30 30H70V70H30Z"
    @Test fun fillRulesRespectNestedOrientationOverlapAndDuplicateContours() {
        assertEquals(8400.0, SvgPath.polygons("$outer $inner", FillRule.EVEN_ODD).area, 1e-8)
        assertEquals(10000.0, SvgPath.polygons("$outer $inner").area, 1e-8)
        assertEquals(8400.0, SvgPath.polygons("$outer M30 30V70H70V30Z").area, 1e-8)
        assertTrue(SvgPath.polygons("$outer $outer", FillRule.EVEN_ODD).isEmpty)
        assertEquals(10000.0, SvgPath.polygons("$outer $outer").area, 1e-8)
        assertTrue(SvgPath.polygons("$outer M0 0V100H100V0Z").isEmpty)
        assertEquals(5000.0, SvgPath.polygons("M0 0L100 100 0 100 100 0Z", FillRule.EVEN_ODD).area, 1e-8)
        assertEquals(10000.0, SvgPath.polygons("M0 0H100V100H0").area, 1e-8)
    }

    @Test fun polygonOperationsPreserveHolesComponentsAndInputs() {
        val a = SvgPath.polygons(outer)
        val b = SvgPath.polygons("M50 0H150V100H50Z")
        assertEquals(15000.0, a.union(b).area, 1e-8)
        assertEquals(5000.0, a.intersection(b).area, 1e-8)
        assertEquals(5000.0, a.difference(b).area, 1e-8)
        assertEquals(10000.0, a.xor(b).area, 1e-8)
        val donut = SvgPath.polygons("$outer $inner", FillRule.EVEN_ODD)
        assertEquals(2, donut.boundaries().size)
        assertTrue(donut.covers(Point(20.0, 50.0)))
        assertFalse(donut.covers(Point(50.0, 50.0)))
        assertFalse(donut.coversSegment(Point(0.0, 50.0), Point(100.0, 50.0)))
        assertEquals(listOf(0.0 to 30.0, 70.0 to 100.0), donut.horizontalSpans(50.0))
        assertEquals(donut.area, donut.transformed(Affine2D.scale(-1.0, 1.0)).area, 1e-8)
        assertTrue(a.offset(-51.0).isEmpty)
        assertEquals(6400.0, a.offset(-10.0).area, 1e-8)
        assertTrue(a.offset(10.0).area > a.area)
        assertEquals(10000.0, a.area, 1e-8)
        assertTrue(a.transformed(Affine2D.scale(0.0)).isEmpty)
        assertTrue(a.intersection(PolygonRegion.empty()).isEmpty)
        assertEquals(a.area, a.union(PolygonRegion.empty()).area, 1e-8)
    }

    @Test fun emptyAndDegeneratePathsAreSafe() {
        assertTrue(SvgPath.polygons("").isEmpty)
        assertTrue(SvgPath.polygons("M0 0L1 1 2 2Z").isEmpty)
        assertTrue(SvgPath.polygons("M0 0").isEmpty)
        assertFalse(PolygonRegion.empty().covers(Point(0.0, 0.0)))
        assertFailsWith<IllegalArgumentException> { Point(Double.NaN, 0.0) }
    }
}
