// SPDX-License-Identifier: MIT-CMU
// Ported from Pillow src/PIL/ImageOps.py@89f1f4626a2aaf5f3d5ca6437f41def2998fbe09 (MIT-CMU).
// Copyright (c) 2001-2004 Secret Labs AB; Copyright (c) 2001-2004 Fredrik Lundh.
// Kotlin adaptation: myembro contributors, 2026. See licenses/pillow/LICENSE.
package io.github.linker2k.myembro.engine.inkstitch.svg.image

/** Pillow 11.3.0 ImageOps.autocontrast default path:
 * cutoff=0, ignore=None, mask=None, preserve_tone=False. Input is uint8 L or interleaved RGB.
 * Other Pillow modes/options and alpha are intentionally not accepted by this scoped API.
 */
object Autocontrast {
    fun apply(image: ByteArray, channels: Int = 1): ByteArray {
        require(channels == 1 || channels == 3) { "Autocontrast supports L or RGB only" }
        require(image.size % channels == 0) { "Incomplete interleaved pixel" }
        val histograms = Array(channels) { IntArray(256) }
        for (i in image.indices) histograms[i % channels][image[i].toInt() and 255]++
        val luts = Array(channels) { channel ->
            val histogram = histograms[channel]
            var low = 0
            while (low < 255 && histogram[low] == 0) low++
            var high = 255
            while (high > 0 && histogram[high] == 0) high--
            if (high <= low) IntArray(256) { it } else {
                val scale = 255.0 / (high - low)
                val offset = -low * scale
                IntArray(256) { value ->
                    // Python int truncates toward zero. Preserve the two floating operations;
                    // integer min/max normalization has different results at rounding boundaries.
                    (value * scale + offset).toInt().coerceIn(0, 255)
                }
            }
        }
        return ByteArray(image.size) { luts[it % channels][image[it].toInt() and 255].toByte() }
    }
}
