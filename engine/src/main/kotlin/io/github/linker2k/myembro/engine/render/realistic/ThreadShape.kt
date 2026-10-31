// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/svg/rendering.py@b0edd96311ece82ee48816dc93466274b12c2a9c
// (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.render.realistic

import kotlin.math.*

/** Display geometry only. Coordinates use CSS SVG pixels (96 dpi), never PES coordinates. */
data class RenderPoint(val x: Double, val y: Double) {
    init { require(x.isFinite() && y.isFinite()) }
}

/** Already display-corrected RGB, e.g. upstream visible_on_white.darker. No catalog mutation. */
data class RenderThreadSegment(val start: RenderPoint, val end: RenderPoint, val displayRgb: Int)

sealed interface ThreadPathCommand {
    data class Move(val p: RenderPoint) : ThreadPathCommand
    data class Line(val p: RenderPoint) : ThreadPathCommand
    data class Cubic(val a: RenderPoint, val b: RenderPoint, val end: RenderPoint) : ThreadPathCommand
    data object Close : ThreadPathCommand
}

object ThreadShape {
    // rendering.py:20–23,105–126 and svg/units.py:12. Keep the actual 1.398 value,
    // despite the older 0.32mm comment. Template endcap height is independently 1.4.
    const val SVG_PIXELS_PER_MM = 96.0 / 25.4
    const val HEIGHT = 1.398
    const val LENGTH_DEDUCTION_MM = 0.2

    /** Exact upstream capsule + zero-area filter-padding whiskers; no new path flattening. */
    fun path(segment: RenderThreadSegment, pixelScale: Double): List<ThreadPathCommand> {
        require(pixelScale > 0 && pixelScale.isFinite())
        val dx = segment.end.x - segment.start.x
        val dy = segment.end.y - segment.start.y
        val length = max(0.0, hypot(dx, dy) - LENGTH_DEDUCTION_MM * SVG_PIXELS_PER_MM)
        val angle = atan2(dy, dx)
        val cosine = cos(angle); val sine = sin(angle)
        val cx = (segment.start.x + segment.end.x) / 2
        val cy = (segment.start.y + segment.end.y) / 2
        fun p(x: Double, y: Double): RenderPoint {
            val xx = x + length / 2; val yy = y - HEIGHT / 2
            return RenderPoint((cx + xx * cosine - yy * sine) * pixelScale,
                (cy + xx * sine + yy * cosine) * pixelScale)
        }
        // Every numeric coordinate below is from rendering.py:44–53 stitch_path.
        return listOf(
            ThreadPathCommand.Move(p(0.0, 0.0)),
            ThreadPathCommand.Line(p(0.55, -0.1)), ThreadPathCommand.Line(p(0.0, 0.0)),
            ThreadPathCommand.Cubic(p(0.613, 0.0), p(0.613, 1.4), p(0.0, 1.4)),
            ThreadPathCommand.Line(p(0.55, 1.5)), ThreadPathCommand.Line(p(0.0, 1.4)),
            ThreadPathCommand.Line(p(-length, 1.4)),
            ThreadPathCommand.Line(p(-length - 0.55, 1.5)), ThreadPathCommand.Line(p(-length, 1.4)),
            ThreadPathCommand.Cubic(p(-length - 0.613, 1.4), p(-length - 0.613, 0.0), p(-length, 0.0)),
            ThreadPathCommand.Line(p(-length - 0.55, -0.1)), ThreadPathCommand.Line(p(-length, 0.0)),
            ThreadPathCommand.Close,
        )
    }
}
