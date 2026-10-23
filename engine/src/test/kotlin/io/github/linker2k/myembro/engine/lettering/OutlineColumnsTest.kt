// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import io.github.linker2k.myembro.engine.geometry.*
import io.github.linker2k.myembro.engine.inkstitch.elements.InkSatin
import io.github.linker2k.myembro.engine.geometry.Polyline as GeometryPath
import kotlin.math.*
import kotlin.test.*

/** Synthetic geometry only. User-supplied commercial font/stitch references are never fixtures. */
class OutlineColumnsTest {
    @Test fun matchedSectionsDoNotSlideWhenTheRailsHaveDifferentLengths() {
        val column = OutlineColumn(listOf(0.0 to 0.0, 0.0 to 3.0, 0.0 to 10.0),
            listOf(-1.0 to 0.0, -1.0 to 3.0, -1.0 to 10.0),
            listOf(1.0 to 0.0, 4.0 to 3.0, 1.0 to 10.0))
        val points = InkSatin.pairs(column.leftRail.map { Point(it.first, it.second) },
            column.rightRail.map { Point(it.first, it.second) },
            listOf(InkSatin.Rung(Point(-1.0, 3.0), Point(4.0, 3.0))), 0.4)
        points.forEach { (a, b) -> assertEquals(a.y, b.y, 1e-5) }
    }
    private fun box(x: Double, y: Double, w: Double, h: Double) = listOf(x to y, x + w to y, x + w to y + h, x to y + h, x to y)
    @Test fun straightOutlineProducesCenterlineAndPairedNormalRails() {
        val columns = OutlineColumns.extract(listOf(box(-1.0, -5.0, 2.0, 10.0)))
        assertTrue(columns.isNotEmpty())
        val longest = columns.maxBy { it.centerline.size }
        assertTrue(longest.centerline.all { abs(it.first) < 0.08 })
        assertEquals(2.0, longest.maxWidthMm, 0.1)
        assertTrue(longest.centerline.maxOf { it.second } - longest.centerline.minOf { it.second } > 9.5)
        longest.leftRail.zip(longest.rightRail).forEach { (a, b) -> assertEquals(a.second, b.second, 0.15) }
    }
    @Test fun diagonalStrokeIsExtractedIndependentlyOfGlobalFillAngle() {
        val angle = PI / 4
        val shape = box(-0.8, -5.0, 1.6, 10.0).map { (x, y) -> x * cos(angle) - y * sin(angle) to x * sin(angle) + y * cos(angle) }
        val columns = OutlineColumns.extract(listOf(shape))
        assertTrue(columns.isNotEmpty())
        val c = columns.maxBy { it.centerline.size }
        assertEquals(1.6, c.maxWidthMm, 0.15)
        val pairs = c.leftRail.zip(c.rightRail)
        assertTrue(pairs.count { (a, b) -> (b.first - a.first) * (b.second - a.second) > 0 } > pairs.size * 0.8)
    }
    @Test fun circularCounterProducesCurvingRailsAndKeepsTheHole() {
        val outer = (0..96).map { val t = it * PI / 48; 6 * cos(t) to 6 * sin(t) }
        val inner = outer.map { (x, y) -> x / 2 to y / 2 }
        val columns = OutlineColumns.extract(listOf(outer, inner))
        assertTrue(columns.isNotEmpty())
        assertTrue(columns.sumOf { it.centerline.size } > 100)
        val widths = columns.flatMap { it.leftRail.zip(it.rightRail).map { (a, b) -> hypot(b.first - a.first, b.second - a.second) } }.sorted()
        assertEquals(3.0, widths[widths.size / 2], 0.25)
        assertTrue(columns.flatMap { it.centerline }.all { hypot(it.first, it.second) > 3.0 })
    }
    @Test fun xJunctionKeepsTwoOverlappingDiagonalColumns() {
        fun ribbon(angle: Double): PolygonRegion {
            val points = box(-0.7, -5.0, 1.4, 10.0).map { (x, y) -> Point((x * cos(angle) - y * sin(angle)) * 10, (x * sin(angle) + y * cos(angle)) * 10) }
            return PolygonRegion.fromPaths(listOf(GeometryPath(points, true)))
        }
        val region = ribbon(PI / 4).union(ribbon(-PI / 4))
        val columns = OutlineColumns.extract(region)
        assertTrue(columns.size >= 2)
        assertTrue(columns.count { it.centerline.any { p -> hypot(p.first, p.second) < 0.8 } } >= 2)
    }
    @Test fun emptyAndSubpixelOutlinesAreLeftForFallback() {
        assertEquals(emptyList(), OutlineColumns.extract(emptyList()))
        assertEquals(emptyList(), OutlineColumns.extract(listOf(box(0.0, 0.0, 0.01, 0.01))))
    }
}
