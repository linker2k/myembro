// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.geometry

import org.locationtech.jts.io.WKTReader
import org.locationtech.jts.geom.util.AffineTransformation
import kotlin.test.*

class StrokeWidthMeasurementTest {
    @Test fun rectangleHasTwoMillimetreInteriorDiametersWithoutInventingThreshold() {
        val result=StrokeWidthMeasurement.measure(WKTReader().read("POLYGON((0 0,20 0,20 2,0 2,0 0))"))
        assertEquals(2.0,result.maximumSampledMm,0.001)
        assertTrue(result.minimumSampledMm>0 && result.minimumSampledMm<=result.p10Mm)
        assertTrue(result.p10Mm<=result.medianMm && result.medianMm<=result.maximumSampledMm)
        assertNull(result.approvedThresholdMm)
    }
    @Test fun proportionalBoundaryResolutionScalesMeasurements() {
        val shape=WKTReader().read("POLYGON((0 0,10 0,10 2,0 2,0 0))")
        val a=StrokeWidthMeasurement.measure(shape,0.1)
        val b=StrokeWidthMeasurement.measure(AffineTransformation.scaleInstance(2.0,2.0).transform(shape),0.2)
        assertEquals(2*a.maximumSampledMm,b.maximumSampledMm,0.001)
        assertEquals(2*a.p10Mm,b.p10Mm,0.001)
    }
    @Test fun holesAreNotMistakenForInk() {
        val result=StrokeWidthMeasurement.measure(WKTReader().read("POLYGON((0 0,10 0,10 10,0 10,0 0),(2 2,2 8,8 8,8 2,2 2))"))
        assertTrue(result.maximumSampledMm<3.0)
        assertFailsWith<IllegalArgumentException>{StrokeWidthMeasurement.measure(WKTReader().read("POLYGON EMPTY"))}
    }
}
