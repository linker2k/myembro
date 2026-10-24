// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg.image

/** Standard per-channel 3x3 median, replicated borders (OpenCV medianBlur convention).
 * https://docs.opencv.org/4.12.0/d4/d86/group__imgproc__filter.html
 * Input simplification only; no embroidery algorithm or stitch parameter is changed.
 */
object PhotoRasterFilters {
    fun median3(width: Int,height: Int,rgb: IntArray): IntArray {
        require(width>0 && height>0 && width.toLong()*height==rgb.size.toLong())
        val window=IntArray(9)
        return IntArray(rgb.size) { i ->
            var result=0
            for(c in 0..2) {
                var n=0
                for(dy in -1..1) for(dx in -1..1) {
                    val x=(i%width+dx).coerceIn(0,width-1);val y=(i/width+dy).coerceIn(0,height-1)
                    window[n++]=(rgb[y*width+x] ushr (c*8)) and 255
                }
                window.sort();result=result or (window[4] shl (c*8))
            }
            result
        }
    }
}
