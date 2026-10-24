// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.elements

import io.github.linker2k.myembro.engine.geometry.*
import io.github.linker2k.myembro.engine.inkstitch.stitches.*
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.*
import io.github.linker2k.myembro.engine.inkstitch.svg.PIXELS_PER_MM
import io.github.linker2k.myembro.engine.model.*
import kotlin.test.*

class FillElementRegressionTest {
    @Test fun gradientUnderlayBoundaryDoesNotBecomeLongSewnMove() {
        val scale = 24.0 * (96.0 / 25.4) / 100
        val rectangle = SvgPath.polygons("M0 0 H120 V100 H0 Z", FillRule.EVEN_ODD, Affine2D.scale(scale), 0.05).geometry
        val vertical = LinearGradientFill.Gradient(FillPoint(0.0, 0.0), FillPoint(0.0, 100 * scale),
            listOf(LinearGradientFill.Stop(0.0, "#c82828"), LinearGradientFill.Stop(1.0, "#2848c8")))
        val groups = FillStitch(rectangle, EmbThread(0xffc82828.toInt(), "Red"),
            FillParameters(fill_method = "linear_gradient_fill"), method = FillMethod.Gradient(vertical)).toStitchGroups(null)
        val pattern = EmbPatternAdapter.toEmbPattern(StitchPlanBuilder.fromGroups(groups))
        for ((a, b) in pattern.stitches.zipWithNext()) if (b.command == Command.STITCH) {
            assertTrue(kotlin.math.hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()) <= 121,
                "Native sewn transition: $a -> $b")
        }
    }

    @Test fun repairedBowtieTartanDoesNotSewAnUnsampledTravelGap() {
        val scale = 12.0 * PIXELS_PER_MM / 100
        val bowtie = SvgPath.polygons("M0 0 L120 100 H0 L120 0 Z", FillRule.EVEN_ODD, Affine2D.scale(scale), 0.05).geometry
        val settings = TartanFill.Settings(warp = listOf(TartanFill.Stripe("#c82828", 4.0), TartanFill.Stripe("#2848c8", 3.0)))
        val groups = FillStitch(bowtie, EmbThread(0xffc82828.toInt(), "Red"),
            FillParameters(fill_method = "tartan_fill"), method = FillMethod.Tartan(settings)).toStitchGroups(null)
        val pattern = EmbPatternAdapter.toEmbPattern(StitchPlanBuilder.fromGroups(groups))
        for ((a, b) in pattern.stitches.zipWithNext()) if (b.command == Command.STITCH) {
            assertTrue(kotlin.math.hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()) <= 121,
                "Native sewn transition: $a -> $b")
        }
    }
}
