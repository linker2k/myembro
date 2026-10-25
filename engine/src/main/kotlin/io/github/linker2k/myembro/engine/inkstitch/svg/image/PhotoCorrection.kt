// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg.image

/** Raster adapters only; algorithms remain in their separately attributed ports. */
enum class PhotoCorrection { ORIGINAL, CLEAN, CONTRAST, OTSU }

object PhotoCorrections {
    data class Raster(val pixels: IntArray, val mask: ByteArray? = null, val alphaBypassed: Boolean = false)

    fun apply(width: Int, height: Int, argb: IntArray, monochrome: Boolean,
        correction: PhotoCorrection = PhotoCorrection.ORIGINAL): Raster {
        require(width > 0 && height > 0 && width.toLong() * height == argb.size.toLong())
        if (!monochrome && argb.any { it ushr 24 != 255 }) return Raster(argb.copyOf(), alphaBypassed = true)
        if (!monochrome && (correction == PhotoCorrection.ORIGINAL || correction == PhotoCorrection.OTSU))
            return Raster(argb.copyOf())
        val rgb = IntArray(argb.size) { argb[it] and 0xffffff }
        // Monochrome already used one median in v0.2.9. Clean merely names that existing state.
        val base = if (monochrome || correction == PhotoCorrection.CLEAN)
            PhotoRasterFilters.median3(width, height, rgb) else rgb
        if (correction == PhotoCorrection.OTSU) {
            val gray = ByteArray(base.size) { Bt709Grayscale.argb(base[it]).toByte() }
            val mask = OtsuThreshold.darkMask(gray)
            return Raster(IntArray(base.size) { (mask[it].toInt() and 255) * 0x010101 }, mask)
        }
        val filtered = if (correction == PhotoCorrection.CONTRAST) {
            val bytes = ByteArray(base.size * 3) { i -> (base[i / 3] ushr (16 - i % 3 * 8)).toByte() }
            val adjusted = Autocontrast.apply(bytes, 3)
            IntArray(base.size) { i -> ((adjusted[i*3].toInt() and 255) shl 16) or
                ((adjusted[i*3+1].toInt() and 255) shl 8) or (adjusted[i*3+2].toInt() and 255) }
        } else base
        return Raster(if (monochrome) filtered else IntArray(filtered.size) { filtered[it] or 0xff000000.toInt() })
    }
}
