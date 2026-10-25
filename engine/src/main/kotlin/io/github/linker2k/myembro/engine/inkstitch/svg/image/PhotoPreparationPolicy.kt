// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import io.github.linker2k.myembro.engine.edit.HoopSpec

/** Shared, unchanged PhotoCrop/PhotoViewModel policy; no new numeric limits. */
object PhotoPreparationPolicy {
    fun photoMaxWidthMm(hoop: HoopSpec): Double =
        kotlin.math.floor(minOf(hoop.width - 1.0, (hoop.height - 1.0) * hoop.width / hoop.height)) / 10.0

    fun photoRasterSize(detail: Int, widthMm: Double = 100.0, heightMm: Double = 100.0): Int =
        minOf(intArrayOf(128, 192, 256)[detail.coerceIn(0, 2)],
            kotlin.math.ceil(maxOf(widthMm,heightMm) / 0.2).toInt()).coerceAtLeast(1)

    fun photoMinimumArea(detail: Int) = doubleArrayOf(1.0, 0.5, 0.25)[detail.coerceIn(0,2)]

    fun photoContrastStretch(rgb: IntArray): IntArray {
        val low=IntArray(3) { 255 }; val high=IntArray(3)
        for (pixel in rgb) for (c in 0..2) {
            val value=(pixel ushr (16-c*8)) and 255
            low[c]=minOf(low[c],value); high[c]=maxOf(high[c],value)
        }
        return IntArray(rgb.size) { i ->
            var result=0
            for (c in 0..2) {
                val value=(rgb[i] ushr (16-c*8)) and 255
                val stretched=if(high[c]==low[c]) value else (value-low[c])*255/(high[c]-low[c])
                result=result or (stretched shl (16-c*8))
            }
            result
        }
    }

}
