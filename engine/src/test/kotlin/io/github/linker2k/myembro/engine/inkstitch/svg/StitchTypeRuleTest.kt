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

    @Test fun boundsAreInclusiveSatinBetween09And6mm() {
        assertEquals(Type.RUNNING, StitchTypeRule.classify(0.89))
        assertEquals(Type.SATIN, StitchTypeRule.classify(0.9))
        assertEquals(Type.SATIN, StitchTypeRule.classify(6.0))
        assertEquals(Type.TATAMI, StitchTypeRule.classify(6.01))
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
}
