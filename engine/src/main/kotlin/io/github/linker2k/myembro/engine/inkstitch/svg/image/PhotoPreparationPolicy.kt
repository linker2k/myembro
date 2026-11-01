// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import io.github.linker2k.myembro.engine.edit.HoopSpec

/** Shared, unchanged PhotoCrop/PhotoViewModel policy; no new numeric limits. */
object PhotoPreparationPolicy {
    fun photoMaxWidthMm(hoop: HoopSpec): Double =
        kotlin.math.floor(minOf(hoop.width - 1.0, (hoop.height - 1.0) * hoop.width / hoop.height)) / 10.0

    fun photoRasterSize(detail: Int, widthMm: Double = 100.0, heightMm: Double = 100.0): Int =
        photoRasterEdge(intArrayOf(128, 192, 256)[detail.coerceIn(0, 2)], widthMm, heightMm)

    /** [edge] limited by the existing 0.2mm-per-pixel working cap (docs/qa/photo-silhouette-performance.md). */
    fun photoRasterEdge(edge: Int, widthMm: Double = 100.0, heightMm: Double = 100.0): Int =
        minOf(edge, kotlin.math.ceil(maxOf(widthMm,heightMm) / MONO_MM_PER_PIXEL).toInt()).coerceAtLeast(1)

    fun photoMinimumArea(detail: Int) = doubleArrayOf(1.0, 0.5, 0.25)[detail.coerceIn(0,2)]

    const val MONO_MM_PER_PIXEL = 0.2

    /** Picture resolution ladder, ×√2 per step, up to the existing 1024px colour limit (user decision
     * 2026-11-01). Mode defaults that fall between steps are inserted, so defaults are unchanged. */
    val RESOLUTION_STEPS = listOf(128, 181, 256, 362, 512, 724, 1024)
    /** Former detail 1 (128/192/256 ladder) for silhouette, outline and colour with B&W. */
    const val MONO_DEFAULT_EDGE = 192
    /** Sample-pack colour regions: the pack's 1024px source size ([SampleColorRegions.MAX_EDGE]). */
    const val COLOR_DEFAULT_EDGE = SampleColorRegions.MAX_EDGE

    /** Small-piece area ladder in mm², ×2 per step (diameter ×√2); 0 = off. User decision 2026-11-01. */
    val PIECE_AREA_STEPS_MM2 = listOf(0.0, 0.25, 0.5, 1.0, 2.0, 4.0, 8.0, 16.0)
    /** Former detail 1 Potrace area (silhouette/outline). */
    const val MONO_DEFAULT_PIECE_AREA_MM2 = 0.5
    /** The pack converter's 2mm-diameter repaint area, π mm², kept exactly. */
    val COLOR_DEFAULT_PIECE_AREA_MM2 = SampleColorRegions.DEFAULT_PIECE_AREA_MM2

    /** Steps up to the decoded picture's long side [sourceEdge] (user decision 2026-11-01: no step above the
     * input); the mode [default] always stays so the default result is unchanged. */
    fun resolutionSteps(default: Int, sourceEdge: Int = Int.MAX_VALUE): List<Int> =
        (RESOLUTION_STEPS.filter { it <= sourceEdge } + default).distinct().sorted()
    /** [allowOff] false drops 0: colour drawing without piece repaint failed on complex line art (koi, 2026-11-01:
     * 208s then a density block at 0; user decision to start colour at 0.25). */
    fun pieceAreaSteps(default: Double, allowOff: Boolean = true): List<Double> =
        (PIECE_AREA_STEPS_MM2.filter { allowOff || it > 0 } + default).distinct().sorted()

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
