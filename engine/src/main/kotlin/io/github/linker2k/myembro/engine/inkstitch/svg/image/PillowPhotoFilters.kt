// SPDX-License-Identifier: MIT-CMU
// Ported from Pillow src/PIL/ImageEnhance.py, src/PIL/ImageFilter.py,
// src/libImaging/{RankFilter,Blend,Convert}.c@89f1f4626a2aaf5f3d5ca6437f41def2998fbe09.
// Copyright (c) Secret Labs AB; Copyright (c) Fredrik Lundh and Pillow contributors.
// Kotlin adaptation, 2026. Full license: licenses/pillow/LICENSE.
package io.github.linker2k.myembro.engine.inkstitch.svg.image

/** Pillow 11.3.0 RGB subset. Alpha is handled by the caller, not filtered. */
object PillowPhotoFilters {
    /** ImageFilter.py RankFilter.filter/MedianFilter: replicated padding, rank size²/2.
     * Size 0 is an app-only bypass; Pillow itself requires an odd positive size.
     */
    fun median(width: Int, height: Int, rgb: IntArray, size: Int): IntArray {
        require(width > 0 && height > 0 && width.toLong() * height == rgb.size.toLong())
        require(size in listOf(0, 3, 5, 7))
        if (size == 0) return rgb.copyOf()
        val window = IntArray(size * size)
        val radius = size / 2
        return IntArray(rgb.size) { index ->
            if (index % width == 0 && Thread.currentThread().isInterrupted) throw InterruptedException()
            var result = 0
            for (channel in 0..2) {
                var n = 0
                for (dy in -radius..radius) for (dx in -radius..radius) {
                    val x = (index % width + dx).coerceIn(0, width - 1)
                    val y = (index / width + dy).coerceIn(0, height - 1)
                    window[n++] = (rgb[y * width + x] ushr (channel * 8)) and 255
                }
                result = result or (rank(window, window.size / 2) shl (channel * 8))
            }
            result
        }
    }

    // RankFilter.c:29–60, Wirth selection; retain the original partition/update order.
    private fun rank(a: IntArray, k: Int): Int {
        var l = 0
        var m = a.lastIndex
        while (l < m) {
            val x = a[k]
            var i = l
            var j = m
            do {
                while (a[i] < x) i++
                while (x < a[j]) j--
                if (i <= j) {
                    val t = a[i]; a[i] = a[j]; a[j] = t
                    i++; j--
                }
            } while (i <= j)
            if (j < k) l = i
            if (k < i) m = j
        }
        return a[k]
    }

    /** ImageEnhance.py:63–80; Convert.c:44,230; Blend.c:21–78.
     * Pillow's blend factor is C float, not double; luminance is its RGB→L BT.601.
     */
    fun contrast(rgb: IntArray, factor: Float): IntArray {
        require(rgb.isNotEmpty() && factor.isFinite() && factor >= 0f)
        if (factor == 1f) return rgb.copyOf()
        var sum = 0L
        for (pixel in rgb) {
            sum += (((pixel ushr 16 and 255) * 19595 + (pixel ushr 8 and 255) * 38470 +
                (pixel and 255) * 7471 + 0x8000) ushr 16)
        }
        val mean = (sum.toDouble() / rgb.size + 0.5).toInt()
        return IntArray(rgb.size) { i ->
            var result = 0
            for (c in 0..2) {
                val value = mean + factor * ((rgb[i] ushr (8 * c) and 255) - mean)
                result = result or (value.toInt().coerceIn(0, 255) shl (8 * c))
            }
            result
        }
    }
}
