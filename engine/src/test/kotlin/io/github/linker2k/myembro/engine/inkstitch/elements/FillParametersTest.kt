// Ported from Ink/Stitch lib/elements/fill_stitch.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.elements

import io.github.linker2k.myembro.engine.inkstitch.svg.PIXELS_PER_MM
import kotlin.test.*

class FillParametersTest {
    @Test fun getterDefaultsRetainSvgUnitsAndResolveUnderlayInheritance() {
        val p = FillParameters()
        assertTrue(p.fill && p.fill_underlay && p.underpath && p.underlay_underpath)
        assertEquals("tatami_fill", p.fill_method)
        assertEquals(0.25, p.row_spacing_mm)
        assertEquals(0.25 * PIXELS_PER_MM, p.rowSpacingPx)
        assertEquals(0.75 * PIXELS_PER_MM, p.underlayRowSpacingPx, 1e-9)
        assertEquals(4.0 * PIXELS_PER_MM, p.maxStitchLengthPx)
        assertEquals(p.maxStitchLengthPx, p.underlayMaxStitchLengthPx)
        assertEquals(2.5 * PIXELS_PER_MM, p.runningLengthPx)
        assertEquals(0.2 * PIXELS_PER_MM, p.runningTolerancePx)
        assertEquals(listOf(90.0), p.resolvedUnderlayAnglesDegrees())
        assertNull(p.min_stitch_length_mm)
        assertNull(p.min_jump_stitch_length_mm)
        assertEquals("half_stitch", p.lock_start)
        assertEquals("half_stitch", p.lock_end)
    }

    @Test fun runningMinimumIsInPixelsAndRowMinimumIsInMillimetres() {
        val p = FillParameters(row_spacing_mm = 0.0, max_stitch_length_mm = -2.0,
            running_stitch_length_mm = 0.0, running_stitch_tolerance_mm = -1.0)
        assertEquals(0.1 * PIXELS_PER_MM, p.rowSpacingPx)
        assertEquals(0.1 * PIXELS_PER_MM, p.maxStitchLengthPx)
        assertEquals(0.01, p.runningLengthPx)
        assertEquals(0.01, p.runningTolerancePx)
        assertEquals(listOf(0.0, 0.0), p.pullCompensationPx)
    }

    @Test fun underlayZeroFallsBackAndExplicitValuesDoNotUseTopFillClamps() {
        val p = FillParameters(row_spacing_mm = 1.0, max_stitch_length_mm = 3.0,
            fill_underlay_row_spacing_mm = 0.0, fill_underlay_max_stitch_length_mm = 0.0)
        assertEquals(3.0 * PIXELS_PER_MM, p.underlayRowSpacingPx)
        assertEquals(3.0 * PIXELS_PER_MM, p.underlayMaxStitchLengthPx)
        val explicit = p.copy(fill_underlay_row_spacing_mm = 0.01, fill_underlay_max_stitch_length_mm = 0.02)
        assertEquals(0.01 * PIXELS_PER_MM, explicit.underlayRowSpacingPx)
        assertEquals(0.02 * PIXELS_PER_MM, explicit.underlayMaxStitchLengthPx)
        assertNull(p.endRowSpacingPx)
        assertNull(p.copy(end_row_spacing_mm = 0.0).endRowSpacingPx)
        assertEquals(0.0, p.copy(end_row_spacing_mm = -1.0).endRowSpacingPx)
    }

    @Test fun angleListMatchesOriginalTrailingCommaAndMalformedFallback() {
        val p = FillParameters(angle = -30.0)
        assertEquals(listOf(60.0), p.resolvedUnderlayAnglesDegrees())
        assertEquals(listOf(0.0, 90.0, -45.0), p.copy(fill_underlay_angle = "0, 90, -45").resolvedUnderlayAnglesDegrees())
        assertEquals(listOf(60.0), p.copy(fill_underlay_angle = "0,90").resolvedUnderlayAnglesDegrees())
        assertEquals(listOf(60.0), p.copy(fill_underlay_angle = "0  90").resolvedUnderlayAnglesDegrees())
        assertEquals(listOf(60.0), p.copy(fill_underlay_angle = "nope").resolvedUnderlayAnglesDegrees())
        assertEquals(listOf(-40.0), p.copy(fill_method = "linear_gradient_fill").resolvedUnderlayAnglesDegrees(40.0))
        assertEquals(listOf(0.0), p.copy(fill_method = "linear_gradient_fill", fill_underlay_angle = "0").resolvedUnderlayAnglesDegrees(40.0))
    }

    @Test fun svgAttributesUseOriginalBooleanAndScalarFallbackRules() {
        val p = FillParameters.fromSvgAttributes(mapOf("inkstitch:angle" to "45", "row_spacing_mm" to "1.25",
            "inkstitch:running_stitch_tolerance_mm" to "invalid", "fill_underlay" to "no", "underpath" to "YES",
            "trim_after" to "t", "stop_after" to "1", "pull_compensation_mm" to "-1 .5",
            "pull_compensation_percent" to "10", "fill_underlay_angle" to "0, 90", "random_seed" to "sample"))
        assertEquals(45.0, p.angle)
        assertEquals(1.25, p.row_spacing_mm)
        assertEquals(0.2, p.running_stitch_tolerance_mm)
        assertFalse(p.fill_underlay)
        assertTrue(p.underpath && p.trim_after && p.stop_after)
        assertEquals(listOf(0.0, 0.5 * PIXELS_PER_MM), p.pullCompensationPx)
        assertEquals(listOf(0.1, 0.1), p.pullCompensationRatio)
        assertEquals("sample", p.random_seed)
        assertEquals(listOf(0.0, 90.0), p.resolvedUnderlayAnglesDegrees())
        assertEquals(listOf(0.0, 0.0), FillParameters.fromSvgAttributes(mapOf("pull_compensation_mm" to "bad 1")).pullCompensationPx)
    }

    @Test fun nonFiniteAndUnsupportedSettingsAreRejected() {
        assertFailsWith<IllegalArgumentException> { FillParameters(angle = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { FillParameters(fill_underlay_row_spacing_mm = -1.0) }
        assertFailsWith<IllegalArgumentException> { FillParameters(pull_compensation_mm = emptyList()) }
        assertFailsWith<IllegalArgumentException> { FillParameters(enable_random_stitch_length = true) }
    }
}
