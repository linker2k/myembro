// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/svg/units.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.svg

const val PIXELS_PER_MM: Double = 96.0 / 25.4

/** Ink/Stitch algorithm coordinates are SVG px at 96dpi; public *_mm parameters remain mm. */
object Units {
    const val PIXELS_PER_MM = io.github.linker2k.myembro.engine.inkstitch.svg.PIXELS_PER_MM
    fun mmToPixels(mm: Double): Double = (mm * PIXELS_PER_MM).also { require(it.isFinite()) }
    fun pixelsToMm(px: Double): Double = (px / PIXELS_PER_MM).also { require(it.isFinite()) }
    fun nativeToPixels(tenthsMm: Int): Double = tenthsMm * PIXELS_PER_MM / 10.0
    fun pixelsToNative(px: Double): Int {
        val native = pixelsToMm(px) * 10.0
        val rounded = Math.round(native)
        require(rounded in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Stitch exceeds native coordinate range" }
        return rounded.toInt()
    }
}
