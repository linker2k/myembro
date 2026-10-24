// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/utils/geometry.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.stitches

import kotlin.math.*

/** Algorithm-local SVG pixel coordinate with stitch provenance; adapted to shared Stitch at the element boundary. */
data class FillPoint(val x: Double, val y: Double, val tags: Set<String> = emptySet()) {
    init { require(x.isFinite() && y.isFinite()) { "Non-finite fill coordinate" } }
    operator fun plus(other: FillPoint) = FillPoint(x + other.x, y + other.y)
    operator fun minus(other: FillPoint) = FillPoint(x - other.x, y - other.y)
    operator fun times(scale: Double) = FillPoint(x * scale, y * scale)
    operator fun div(scale: Double) = FillPoint(x / scale, y / scale)
    fun dot(other: FillPoint) = x * other.x + y * other.y
    fun length() = hypot(x, y)
    fun distance(other: FillPoint) = hypot(x - other.x, y - other.y)
    fun unit(): FillPoint = length().let { if (it == 0.0) FillPoint(0.0, 0.0) else this / it }
    fun interpolate(other: FillPoint, t: Double) = this + (other - this) * t
    fun rotate(angle: Double) = FillPoint(x * cos(angle) - y * sin(angle), x * sin(angle) + y * cos(angle))
    fun samePosition(other: FillPoint) = x == other.x && y == other.y
    fun tagged(vararg more: String) = copy(tags = tags + more)
}

internal fun positiveModulo(value: Double, modulus: Double): Double = value - floor(value / modulus) * modulus
