// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.geometry

import io.github.linker2k.myembro.engine.edit.HoopSpec

/** Existing PhotoCrop/PhotoSizeSweep minimum (8 drawings × 2 styles × 3 details, 5mm steps).
 * Moved unchanged from the app; this is an input policy, not a sewing guarantee. */
const val PICTURE_MIN_WIDTH_MM = 20.0

/** Exact former EmbroiderySizeSlider.pictureWidthRange arithmetic, including P2's 1-unit margin. */
fun pictureWidthRange(hoop: HoopSpec, aspect: Double): ClosedFloatingPointRange<Double> {
    val max = kotlin.math.floor(minOf(hoop.width - 1.0, (hoop.height - 1.0) / aspect)) / 10.0
    return minOf(PICTURE_MIN_WIDTH_MM, max)..max
}
