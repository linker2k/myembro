// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg

import io.github.linker2k.myembro.engine.inkstitch.svg.StitchTypeRule.Type
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.operation.buffer.BufferParameters
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StitchTypeRuleTest {
    private val f = GeometryFactory()
    private fun line(vararg xy: Double) = f.createLineString((xy.indices step 2).map { Coordinate(xy[it], xy[it + 1]) }.toTypedArray())
    private fun near(expected: Double, actual: Double, tolerance: Double) =
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected ± $tolerance, was $actual")

    @Test fun satinNeedsMaximumAtMost6AndMedianAtLeast09() {
        fun c(max: Double, median: Double) = StitchTypeRule.classify(StitchTypeRule.Widths(max, median))
        assertEquals(Type.RUNNING, c(0.89, 0.89))
        assertEquals(Type.SATIN, c(0.9, 0.9))
        assertEquals(Type.SATIN, c(6.0, 5.0))
        assertEquals(Type.TATAMI, c(6.01, 3.0)) // one crossing over the satin limit is enough for tatami
        assertEquals(Type.RUNNING, c(4.0, 0.5))
    }

    /** Median local thickness: band width for strips, rings and bent strokes, short side for rectangles, diameter for disks. */
    @Test fun medianWidthMeasuresTheBandNotTheCornersOrBends() {
        val flat = BufferParameters().apply { endCapStyle = BufferParameters.CAP_FLAT }
        near(3.0, StitchTypeRule.medianWidth(line(0.0, 0.0, 40.0, 0.0).buffer(1.5, 8, BufferParameters.CAP_FLAT)), 0.2)
        near(5.0, StitchTypeRule.medianWidth(f.createPoint(Coordinate(0.0, 0.0)).buffer(28.5).difference(f.createPoint(Coordinate(0.0, 0.0)).buffer(23.5))), 0.25)
        near(20.0, StitchTypeRule.medianWidth(f.createPoint(Coordinate(0.0, 0.0)).buffer(10.0)), 0.5)
        near(6.0, StitchTypeRule.medianWidth(f.toGeometry(org.locationtech.jts.geom.Envelope(0.0, 6.0, 0.0, 7.0))), 0.3)
        // Zigzag stroke 6.5 wide whose legs do not merge: the median stays at the stroke width.
        val zigzag = org.locationtech.jts.operation.buffer.BufferOp.bufferOp(line(0.0, 0.0, 20.0, 16.0, 40.0, 0.0, 60.0, 16.0, 80.0, 0.0), 3.25, flat)
        near(6.5, StitchTypeRule.medianWidth(zigzag), 0.5)
        // Classification in other units: 10 units per mm (PEC 0.1mm).
        assertEquals(Type.SATIN, StitchTypeRule.classify(line(0.0, 0.0, 400.0, 0.0).buffer(15.0, 8, BufferParameters.CAP_FLAT), 10.0))
    }

    /** Recheck table (mm): logo parts at 60mm and a robot-body stand-in. Widths are printed for the report. */
    @Test fun recheckTable() {
        val logo = VectorArt.render(VectorArt.parseSvg(VectorArt.vectorDrawableToSvg(
            java.io.File("../app/src/main/res/drawable/ic_launcher_foreground.xml").readText())), 60.0)
        val names = listOf("clamp body", "clamp bar", "hoop ring", "logo M")
        val rows = logo.parts.map { names[it.shape] to (it.widths!! to it.method) }.toMutableList()
        // Robot body stand-in: 20×28mm rounded rectangle (editor's own robot fixture rechecks the same function).
        val body = f.toGeometry(org.locationtech.jts.geom.Envelope(2.0, 18.0, 2.0, 26.0)).buffer(2.0)
        rows += "robot body (20×28 stand-in)" to (StitchTypeRule.widths(body, 1.0) to StitchTypeRule.classify(body, 1.0))
        rows.forEach { (name, r) -> println("RULE | $name | max ${"%.2f".format(r.first.maxMm)} | median ${"%.2f".format(r.first.medianMm)} | ${r.second} |") }
        assertEquals(listOf("clamp bar" to Type.SATIN, "hoop ring" to Type.SATIN, "logo M" to Type.TATAMI,
            "robot body (20×28 stand-in)" to Type.TATAMI), rows.map { it.first to it.second.second })
    }
}
