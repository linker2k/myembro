// Ported from Ink/Stitch lib/stitches/tartan_fill.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
// Ported from Ink/Stitch lib/tartan/utils.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
// Ported from Ink/Stitch tests/test_threads_color.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.stitches

import io.github.linker2k.myembro.engine.inkstitch.svg.PIXELS_PER_MM
import io.github.linker2k.myembro.engine.geometry.Affine2D
import io.github.linker2k.myembro.engine.geometry.FillRule
import io.github.linker2k.myembro.engine.geometry.SvgPath
import io.github.linker2k.myembro.engine.inkstitch.elements.FillParameters
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.EmbPatternAdapter
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.engine.model.Command
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.io.WKTReader
import kotlin.test.*

class TartanFillTest {
    private val shape = WKTReader().read("POLYGON ((0 0,80 0,80 60,0 60,0 0),(25 20,25 40,45 40,45 20,25 20))")



    @Test fun upstreamTartanColorsRetainYellowThenCyanOnTwentyPixelRectangle() {
        // test_tartan_colors: (#ffff00)/5.0 (#00ffff)/5.0; equal warp/weft defaults to the same list.
        val rectangle = WKTReader().read("POLYGON ((0 0,20 0,20 20,0 20,0 0))")
        val settings = TartanFill.Settings(warp = listOf(TartanFill.Stripe("#ffff00", 5.0),
            TartanFill.Stripe("#00ffff", 5.0)), symmetry = true, rotate = 0.0, scale = 100.0,
            offset_x = 0.0, offset_y = 0.0, min_stripe_width = 1.0)
        val sections = TartanFill.generate(rectangle, TartanFill.Parameters(fill_underlay = false), settings)
        assertEquals(listOf("#ffff00", "#00ffff"), sections.map { it.color })
        assertTrue(sections.all { it.stitches.isNotEmpty() })
    }

    @Test fun upstreamTartanInvalidSecondColorLeavesOnlyYellowAfterCallerDisablesStripe() {
        // The upstream palette parser maps bad_color to black with render=0. This API takes that
        // resolved stripe directly; it does not parse/validate CSS colors or palette strings itself.
        val rectangle = WKTReader().read("POLYGON ((0 0,20 0,20 20,0 20,0 0))")
        val settings = TartanFill.Settings(warp = listOf(TartanFill.Stripe("#ffff00", 5.0),
            TartanFill.Stripe("#000000", 5.0, render = 0)), symmetry = true, rotate = 0.0, scale = 100.0,
            offset_x = 0.0, offset_y = 0.0, min_stripe_width = 1.0)
        val sections = TartanFill.generate(rectangle, TartanFill.Parameters(fill_underlay = false), settings)
        assertEquals(1, sections.size)
        assertEquals("#ffff00", sections.single().color)
        assertTrue(sections.single().stitches.isNotEmpty())
    }

    @Test fun reflectiveSettExcludesBothPivotStripes() {
        val sett = TartanFill.stripesToSett(listOf(TartanFill.Stripe("red", 4.0),
            TartanFill.Stripe("green", 3.0), TartanFill.Stripe("blue", 2.0)), true, 100.0, 1.0)
        assertEquals(listOf("red", "green", "blue", "green"), sett.map { it.color })
        assertEquals(12.0 * PIXELS_PER_MM, sett.sumOf { it.width }, 1e-9)
        assertTrue(sett.none { it.is_stroke })
    }

    @Test fun narrowStripeBecomesCenteredStrokeAndInheritsNeighboringFill() {
        val sett = TartanFill.stripesToSett(listOf(TartanFill.Stripe("red", 4.0),
            TartanFill.Stripe("white", 0.4), TartanFill.Stripe("blue", 4.0)), false, 100.0, 1.0)
        assertEquals(listOf("red", "red", "white", "blue", "blue"), sett.map { it.color })
        val expected = listOf(4.0, 0.2, 0.0, 4.2, 0.2)
        expected.zip(sett).forEach { (width, stripe) -> assertEquals(width * PIXELS_PER_MM, stripe.width, 1e-9) }
        assertEquals(listOf(false, false, true, false, false), sett.map { it.is_stroke })
    }

    @Test fun renderModesPreserveInvisibleGapsAndInheritedFill() {
        val sett = TartanFill.stripesToSett(listOf(TartanFill.Stripe("red", 4.0),
            TartanFill.Stripe("green", 2.0, 2), TartanFill.Stripe("blue", 5.0, 0)), false, 100.0, 1.0)
        assertEquals(listOf("red", "red", null), sett.map { it.color })
        assertEquals(listOf(true, true, false), sett.map { it.render })
    }

    @Test fun alternatingWarpWeftBlocksUseLexicographicSortAndReverseEverySecondLine() {
        val factory = GeometryFactory()
        val lines = (0..7).map { y -> factory.createLineString(arrayOf(Coordinate(0.0, y.toDouble()), Coordinate(10.0, y.toDouble()))) }
        val (warp, weft) = TartanFill.splitWarpWeft(lines, 2)
        assertEquals(listOf(0.0, 1.0, 4.0, 5.0), warp.map { it.getCoordinateN(0).y })
        assertEquals(listOf(2.0, 3.0, 6.0, 7.0), weft.map { it.getCoordinateN(0).y })
        assertEquals(listOf(0.0, 10.0, 0.0, 10.0), warp.map { it.getCoordinateN(0).x })
    }

    @Test fun regularTartanHasDeterministicColorSegmentsAndDoesNotEnterHoles() {
        val settings = TartanFill.Settings(warp = listOf(TartanFill.Stripe("red", 4.0), TartanFill.Stripe("blue", 3.0)))
        val p = TartanFill.Parameters(row_spacing_mm = 1.0, max_stitch_length_mm = 2.0)
        val result = TartanFill.generate(shape, p, settings)
        assertTrue(result.isNotEmpty())
        assertEquals(setOf("red", "blue"), result.map { it.color }.toSet())
        assertEquals(result, TartanFill.generate(shape, p, settings))
        assertTrue(result.flatMap { it.stitches }.all {
            shape.buffer(0.01).covers(shape.factory.createPoint(Coordinate(it.x, it.y)))
        })
    }

    @Test fun thinStripesAreSewnAfterFillAndBeanStitchedOnlyOnTheirRuns() {
        val settings = TartanFill.Settings(warp = listOf(TartanFill.Stripe("red", 5.0),
            TartanFill.Stripe("white", 0.5), TartanFill.Stripe("blue", 5.0)))
        val p = TartanFill.Parameters(row_spacing_mm = 1.0)
        val normal = TartanFill.generate(shape, p, settings)
        val bean = TartanFill.generate(shape, p.copy(bean_stitch_repeats = listOf(1)), settings)
        assertTrue(normal.any { it.running })
        val firstRun = normal.indexOfFirst { it.running }
        assertTrue(normal.drop(firstRun).all { it.running })
        assertEquals(normal.filter { !it.running }, bean.filter { !it.running })
        assertTrue(bean.filter { it.running }.sumOf { it.stitches.size } > normal.filter { it.running }.sumOf { it.stitches.size })
    }

    @Test fun originalOffsetUsesAbsoluteValuesAndRotationRemainsInsideOutline() {
        val p = TartanFill.Parameters(row_spacing_mm = 1.0)
        val settings = TartanFill.Settings(rotate = 30.0, offset_x = 1.0, offset_y = 2.0)
        val result = TartanFill.generate(shape, p, settings)
        assertEquals(result, TartanFill.generate(shape, p, settings.copy(offset_x = -1.0, offset_y = -2.0)))
        assertTrue(result.isNotEmpty())
        assertTrue(result.flatMap { it.stitches }.all {
            shape.buffer(0.01).covers(shape.factory.createPoint(Coordinate(it.x, it.y)))
        })
    }

    @Test fun emptyZeroWidthAndHiddenPalettesDoNotLoop() {
        assertTrue(TartanFill.generate(GeometryFactory().createPolygon()).isEmpty())
        assertTrue(TartanFill.generate(shape, settings = TartanFill.Settings(warp = listOf(TartanFill.Stripe("red", 0.0)))).isEmpty())
        assertTrue(TartanFill.generate(shape, settings = TartanFill.Settings(warp = listOf(TartanFill.Stripe("red", 2.0, 0)))).isEmpty())
    }

    @Test fun herringboneUsesOffsetConnectorsAndClipsTheShiftedWeftToOutline() {
        val settings = TartanFill.Settings(warp = listOf(TartanFill.Stripe("red", 4.0), TartanFill.Stripe("blue", 3.0)))
        val p = TartanFill.Parameters(row_spacing_mm = 1.0, max_stitch_length_mm = 2.0, herringbone_width_mm = 3.0)
        val result = TartanFill.generate(shape, p, settings)
        assertTrue(result.isNotEmpty())
        assertEquals(setOf("red", "blue"), result.map { it.color }.toSet())
        assertEquals(result, TartanFill.generate(shape, p, settings))
        assertTrue(result.flatMap { it.stitches }.all {
            shape.buffer(0.01).covers(shape.factory.createPoint(Coordinate(it.x, it.y)))
        })
    }

    @Test fun unsupportedRandomAndInvalidParametersFailExplicitly() {
        assertFailsWith<IllegalArgumentException> { TartanFill.Parameters(enable_random_stitch_length = true) }
        assertFailsWith<IllegalArgumentException> { TartanFill.Stripe("red", -1.0) }
        assertFailsWith<IllegalArgumentException> { TartanFill.Parameters(rows_per_thread = 0) }
    }
}
