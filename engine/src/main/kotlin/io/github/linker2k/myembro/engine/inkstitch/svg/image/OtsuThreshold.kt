// SPDX-License-Identifier: BSD-2-Clause
// Ported from scikit-image skimage/filters/thresholding.py@e8a42ba85aaf5fd9322ef9ca51bc21063b22fcae (BSD-2-Clause).
// Copyright (c) 2009-2015 Board of Regents of the University of Wisconsin-Madison,
// Broad Institute of MIT and Harvard, and Max Planck Institute of Molecular Cell Biology and Genetics.
// Copyright (c) 2009 Zachary Pincus; 2009 Almar Klein.
// Kotlin adaptation: myembro contributors, 2026. See licenses/scikit-image/LICENSE.txt.
package io.github.linker2k.myembro.engine.inkstitch.svg.image

/** scikit-image 0.25.2 threshold_otsu's uint8 image path, with default nbins=256/hist=None.
 * Integer images use every integer bin between the observed minimum and maximum (nbins is ignored).
 * No RGB conversion, denoising, or histogram normalization is implicitly applied.
 */
object OtsuThreshold {
    fun thresholdOtsu(image: ByteArray): Int {
        require(image.isNotEmpty()) { "Otsu requires a nonempty uint8 image" }
        val histogram = IntArray(256)
        var low = 255
        var high = 0
        for (byte in image) {
            val value = byte.toInt() and 255
            histogram[value]++
            if (value < low) low = value
            if (value > high) high = value
        }
        if (low == high) return low // upstream constant-image short circuit
        val size = high - low + 1
        val weight1 = FloatArray(size)
        val weight2 = FloatArray(size)
        val mean1 = DoubleArray(size)
        val mean2 = DoubleArray(size)
        // _validate_image_histogram casts counts to float32. np.cumsum retains that dtype.
        // Integer bin centers are int64, so counts * bin_centers promotes to float64.
        var count = 0f
        var moment = 0.0
        for (i in 0 until size) {
            val bin = low + i
            val frequency = histogram[bin].toFloat()
            count += frequency
            moment += frequency.toDouble() * bin.toDouble()
            weight1[i] = count
            mean1[i] = moment / count.toDouble()
        }
        count = 0f
        moment = 0.0
        for (i in size - 1 downTo 0) {
            val bin = low + i
            val frequency = histogram[bin].toFloat()
            count += frequency
            moment += frequency.toDouble() * bin.toDouble()
            weight2[i] = count
            mean2[i] = moment / count.toDouble()
        }
        var best = 0
        var maximum = Double.NEGATIVE_INFINITY
        for (i in 0 until size - 1) {
            val difference = mean1[i] - mean2[i + 1]
            // Preserve the float32 product before multiplication by the float64 squared difference.
            val variance = (weight1[i] * weight2[i + 1]).toDouble() * (difference * difference)
            if (variance > maximum) { // np.argmax selects the first maximum
                maximum = variance
                best = i
            }
        }
        return low + best
    }

    /** Dark-ink adapter: upstream example image <= threshold; ink=0 and paper=255. */
    fun darkMask(image: ByteArray): ByteArray {
        val threshold = thresholdOtsu(image)
        return ByteArray(image.size) { if ((image[it].toInt() and 255) <= threshold) 0 else 255.toByte() }
    }
}
