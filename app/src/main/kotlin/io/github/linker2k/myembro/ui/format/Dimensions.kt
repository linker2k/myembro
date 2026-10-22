// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.format

import io.github.linker2k.myembro.ui.Hoop
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Actual dimensions: 25.4 mm/in, HALF_UP to one decimal, trailing zero omitted.
 * Exact quarter inches use ¼/½/¾. Known hoops use Brother's nominal inch sizes instead;
 * e.g. the manufacturer's 20 mm / 1 in labels are nominal, not an exact conversion.
 */
object Dimensions {
    private fun number(value: Double): String {
        require(value.isFinite()) { "Dimension must be finite" }
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }

    fun inches(mm: Double): String {
        require(mm.isFinite()) { "Dimension must be finite" }
        val inches = BigDecimal.valueOf(mm).divide(BigDecimal("25.4"), 12, RoundingMode.HALF_UP)
        val quarters = inches.toDouble() * 4
        val nearest = quarters.roundToLong()
        if (abs(quarters - nearest) < 1e-8) {
            val magnitude = abs(nearest)
            val whole = magnitude / 4
            val fraction = when (magnitude % 4) { 1L -> "¼"; 2L -> "½"; 3L -> "¾"; else -> "" }
            return (if (nearest < 0) "-" else "") + (if (whole != 0L || fraction.isEmpty()) whole.toString() else "") + fraction
        }
        return inches.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }

    fun length(mm: Double): String = "${number(mm)}mm (${inches(mm)}in)"

    fun size(widthMm: Double, heightMm: Double): String {
        require(widthMm >= 0 && heightMm >= 0) { "Size must not be negative" }
        return "${number(widthMm)}×${number(heightMm)}mm (${inches(widthMm)}×${inches(heightMm)}in)"
    }

    /** Brother SA432, SA431 and SA434 nominal sizes; order remains width × height. */
    fun hoopSize(widthMm: Int, heightMm: Int): String {
        val nominal = when (widthMm to heightMm) {
            100 to 100 -> "4×4"
            60 to 20 -> "2½×1"
            20 to 60 -> "1×2½"
            100 to 170 -> "4×6¾"
            170 to 100 -> "6¾×4"
            else -> return size(widthMm.toDouble(), heightMm.toDouble())
        }
        return "$widthMm×${heightMm}mm (${nominal}in)"
    }
}

fun Hoop.dimensions(): String = Dimensions.hoopSize(widthMm, heightMm)
