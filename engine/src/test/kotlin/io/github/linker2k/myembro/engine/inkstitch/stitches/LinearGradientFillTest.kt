// Ported from Ink/Stitch lib/stitches/linear_gradient_fill.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
// Ported from Ink/Stitch tests/test_threads_color.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.stitches

import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.io.WKTReader
import io.github.linker2k.myembro.engine.geometry.Affine2D
import io.github.linker2k.myembro.engine.geometry.FillRule
import io.github.linker2k.myembro.engine.geometry.SvgPath
import io.github.linker2k.myembro.engine.inkstitch.elements.FillParameters
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.EmbPatternAdapter
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.engine.model.Command
import kotlin.test.*

class LinearGradientFillTest {
    private val shape = WKTReader().read("POLYGON ((0 0,80 0,80 60,0 60,0 0),(25 20,25 40,45 40,45 20,25 20))")
    private val gradient = LinearGradientFill.Gradient(FillPoint(0.0, 0.0), FillPoint(80.0, 0.0),
        listOf(LinearGradientFill.Stop(0.0, "red"), LinearGradientFill.Stop(1.0, "blue")))

    @Test fun largeRectangleGradientDoesNotSewAnUnsampledTravelGap() {
        val scale = 24.0 * (96.0 / 25.4) / 100
        val rectangle = WKTReader().read("POLYGON ((0 0,${120 * scale} 0,${120 * scale} ${100 * scale},0 ${100 * scale},0 0))")
        val vertical = LinearGradientFill.Gradient(FillPoint(0.0, 0.0), FillPoint(0.0, 100 * scale),
            listOf(LinearGradientFill.Stop(0.0, "#c82828"), LinearGradientFill.Stop(1.0, "#2848c8")))
        val result = LinearGradientFill.generate(rectangle, gradient = vertical, startingPoint = FillPoint(107.71653543307087, 0.0))
        for (section in result) for ((a, b) in section.stitches.zipWithNext()) {
            assertTrue(a.distance(b) <= 12.1 * (96.0 / 25.4), "${section.color}: $a -> $b (${a.distance(b)}px)")
        }
    }



    @Test fun upstreamGradientColorsRetainRedThenBlackOnTenPixelRectangle() {
        // test_gradient_colors: the SVG/CSS caller resolves bad_color to black before this typed API.
        // This checks the original size/color-order expectation, not CSS parsing or stop normalization.
        val rectangle = WKTReader().read("POLYGON ((0 0,10 0,10 10,0 10,0 0))")
        val normalizedGradient = LinearGradientFill.Gradient(FillPoint(0.0, 0.0), FillPoint(10.0, 0.0),
            listOf(LinearGradientFill.Stop(0.0, "#ff0000"), LinearGradientFill.Stop(1.0, "#000000")))
        val sections = LinearGradientFill.generate(rectangle, gradient = normalizedGradient)
        assertEquals(listOf("#ff0000", "#000000"), sections.map { it.color })
        assertTrue(sections.all { it.stitches.isNotEmpty() })
    }

    @Test fun twelveLineTransitionPreservesOriginalSquareRootSubdivisionAndMirrorOrder() {
        val distribution = LinearGradientFill.colorLineIndices(13, listOf("red", "blue"), listOf(0, 12))
        assertEquals(listOf(1, 2, 3, 5, 9, 7), distribution.getValue("red"))
        assertEquals(listOf(4, 6, 12, 11, 10, 8), distribution.getValue("blue"))
        assertEquals((1..12).toList(), distribution.values.flatten().sorted())
    }

    @Test fun transparencyLeavesGapsAndRepeatedColorsShareOneSection() {
        val colors = LinearGradientFill.colorLineIndices(40, listOf("red", "none", "red"), listOf(0, 20, 39))
        assertEquals(setOf("red"), colors.keys)
        assertTrue(colors.getValue("red").size in 1..38)
        assertTrue(colors.values.flatten().all { it in 0 until 40 })
        val outside = LinearGradientFill.colorLineIndices(13, listOf("red", "green", "blue"), listOf(-30, -20, 12))
        assertFalse("red" in outside)
    }

    @Test fun fixedGradientClipsHolesAndReturnsColorsInFirstStopOrder() {
        val p = LinearGradientFill.Parameters(row_spacing_mm = 1.0, max_stitch_length_mm = 2.0)
        val sections = LinearGradientFill.generate(shape, p, gradient)
        assertEquals(listOf("red", "blue"), sections.map { it.color })
        assertEquals(sections, LinearGradientFill.generate(shape, p, gradient))
        assertTrue(sections.all { it.stitches.isNotEmpty() })
        assertTrue(sections.flatMap { it.stitches }.all {
            shape.buffer(0.01).covers(shape.factory.createPoint(Coordinate(it.x, it.y)))
        })
    }

    @Test fun noGradientUsesOriginalSingleColorToTransparentSpacing() {
        val sections = LinearGradientFill.generate(shape, LinearGradientFill.Parameters(row_spacing_mm = 1.0), color = "#123456")
        assertEquals(listOf("#123456"), sections.map { it.color })
        assertTrue(sections.single().stitches.isNotEmpty())
    }

    @Test fun diagonalGradientUsesTransformedUserSpaceEndpoints() {
        val diagonal = gradient.copy(end = FillPoint(80.0, 60.0))
        val sections = LinearGradientFill.generate(shape, LinearGradientFill.Parameters(row_spacing_mm = 1.0), diagonal)
        assertEquals(listOf("red", "blue"), sections.map { it.color })
        assertTrue(sections.all { it.stitches.isNotEmpty() })
    }

    @Test fun travelTrimmingPreservesUpstreamColorSectionConditions() {
        val travelStart = FillPoint(0.0, 0.0, setOf("travel"))
        val a = FillPoint(1.0, 0.0)
        val b = FillPoint(2.0, 0.0)
        val travelEnd = FillPoint(3.0, 0.0, setOf("travel"))
        val path = listOf(travelStart, a, b, travelEnd)
        assertEquals(listOf(a, b), LinearGradientFill.removeStartEndTravel(path, 2, 1, false, false))
        // The original leaves remove_after=len-1 when stop_at_ending_point is enabled.
        assertEquals(listOf(travelStart, a, b), LinearGradientFill.removeStartEndTravel(path, 2, 0, true, true))
    }

    @Test fun validationAndEmptyGeometryArePredictable() {
        assertTrue(LinearGradientFill.generate(GeometryFactory().createPolygon()).isEmpty())
        assertFailsWith<IllegalArgumentException> { LinearGradientFill.Parameters(enable_random_stitch_length = true) }
        assertFailsWith<IllegalArgumentException> { LinearGradientFill.Parameters(staggers = -1.0) }
        assertFailsWith<IllegalArgumentException> { LinearGradientFill.Stop(2.0, "red") }
        assertFailsWith<IllegalArgumentException> { LinearGradientFill.Gradient(FillPoint(0.0, 0.0), FillPoint(0.0, 0.0), gradient.stops) }
    }
}
