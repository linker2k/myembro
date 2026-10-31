// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.photo

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.geometry.SvgPath
import io.github.linker2k.myembro.engine.inkstitch.svg.Units
import io.github.linker2k.myembro.engine.inkstitch.svg.image.RedworkOutline

internal data class PhotoCrop(val left: Int, val top: Int, val width: Int, val height: Int)

/** Cover is 1; contain is the ratio of source and hoop aspect ratios. */
internal fun photoMinimumZoom(width: Int, height: Int, aspect: Double): Float {
    require(width > 0 && height > 0 && aspect.isFinite() && aspect > 0)
    val sourceAspect = width.toDouble() / height
    return minOf(sourceAspect / aspect, aspect / sourceAspect).toFloat()
}

/** Virtual source-pixel viewport. Negative edges represent background outside the source. */
internal fun photoCrop(width: Int, height: Int, aspect: Double, zoom: Float, panX: Float, panY: Float): PhotoCrop {
    val z = zoom.coerceIn(photoMinimumZoom(width, height, aspect), 4f)
    val w = kotlin.math.round(minOf(width.toDouble(), height * aspect) / z).toInt().coerceAtLeast(1)
    val h = kotlin.math.round(minOf(height.toDouble(), width / aspect) / z).toInt().coerceAtLeast(1)
    // On either side of cover, panning stops before exposing more than the available blank space.
    fun edge(source: Int, viewport: Int, pan: Float) = kotlin.math.round(
        (source - viewport) / 2.0 + kotlin.math.abs(source - viewport) * pan.coerceIn(-1f, 1f) / 2.0).toInt()
    return PhotoCrop(edge(width, w, panX), edge(height, h, panY), w, h)
}

/**
 * Smallest picture width in mm: from 20mm up every width passed the photo size sweep (8 synthetic
 * drawings × 2 styles × 3 detail levels at 5mm steps, `PhotoSizeSweep`); below it thin lines and
 * dots vanish in tracing. Not a sewing guarantee for arbitrary photos.
 */
internal const val PHOTO_MIN_WIDTH_MM = io.github.linker2k.myembro.engine.geometry.PICTURE_MIN_WIDTH_MM

/**
 * Traced ink width ÷ picture (frame) width. Both scale together, so the ratio does not depend on
 * the chosen size: frame width = wanted embroidery width ÷ ratio, with no extra tracing.
 */
internal fun photoInkRatio(outline: RedworkOutline): Double? {
    val xs = outline.paths.flatMap { d -> SvgPath.flatten(d).flatMap { line -> line.points.map { it.x } } }
    if (xs.isEmpty()) return null
    return ((xs.max() - xs.min()) / Units.mmToPixels(outline.widthMm)).takeIf { it.isFinite() && it > 0 }?.coerceAtMost(1.0)
}

/** Embroidery (ink) width range for [ratio]: 20mm..99.9mm, the top lowered so the frame still fits the hoop. */
internal fun photoInkWidthRange(hoop: HoopSpec, ratio: Double): ClosedFloatingPointRange<Double> {
    val max = minOf(photoMaxWidthMm(hoop), kotlin.math.floor(photoMaxWidthMm(hoop) * ratio * 10) / 10)
    return minOf(PHOTO_MIN_WIDTH_MM, max)..max
}

/** Largest picture width in mm, aspect kept: width and height stay within 0..(units−1) (AGENTS.md P2). */
internal fun photoMaxWidthMm(hoop: HoopSpec): Double =
    io.github.linker2k.myembro.engine.inkstitch.svg.image.PhotoPreparationPolicy.photoMaxWidthMm(hoop)

/** Shared unchanged raster/detail policy, used by UI and headless preparation. */
internal fun photoRasterSize(detail: Int, widthMm: Double = 100.0, heightMm: Double = 100.0): Int =
    io.github.linker2k.myembro.engine.inkstitch.svg.image.PhotoPreparationPolicy.photoRasterSize(detail, widthMm, heightMm)

internal fun photoMinimumArea(detail: Int) =
    io.github.linker2k.myembro.engine.inkstitch.svg.image.PhotoPreparationPolicy.photoMinimumArea(detail)

internal fun photoContrastStretch(rgb: IntArray): IntArray =
    io.github.linker2k.myembro.engine.inkstitch.svg.image.PhotoPreparationPolicy.photoContrastStretch(rgb)
