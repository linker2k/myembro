// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/utils/geometry.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.utils

import kotlin.math.hypot

/** SVG px, +y downward. Kept distinct from engine.geometry.Point (0.1mm). */
data class Point(val x: Double, val y: Double) {
    init { require(x.isFinite() && y.isFinite()) { "Non-finite Ink/Stitch coordinate" } }
    operator fun plus(other: Point) = Point(x + other.x, y + other.y)
    operator fun minus(other: Point) = Point(x - other.x, y - other.y)
    operator fun times(scale: Double) = Point(x * scale, y * scale)
    operator fun div(scale: Double) = Point(x / scale, y / scale)
    fun length(): Double = hypot(x, y)
    fun distance(other: Point): Double = (this - other).length()
    fun unit(): Point = length().let { if (it == 0.0) Point(0.0, 0.0) else this / it }
    fun dot(other: Point): Double = x * other.x + y * other.y
}
