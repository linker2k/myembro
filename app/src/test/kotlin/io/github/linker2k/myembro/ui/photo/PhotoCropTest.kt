// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.photo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhotoCropTest {
    @Test fun contrastStretchHasExactEndpointsAndDoesNotInventUniformDetail() {
        kotlin.test.assertContentEquals(intArrayOf(0,0x7F7F7F,0xFFFFFF),
            photoContrastStretch(intArrayOf(0x646464,0x656565,0x666666)))
        kotlin.test.assertContentEquals(intArrayOf(0x646464,0x646464),photoContrastStretch(intArrayOf(0x646464,0x646464)))
    }
    @Test fun workingRasterIsBoundedByHoopAndDetail() {
        assertEquals(192,photoRasterSize(1))
        assertEquals(256,photoRasterSize(2,100.0,100.0))
        assertEquals(100,photoRasterSize(2,20.0,20.0))
        assertEquals(1.0,photoMinimumArea(0))
        assertEquals(0.25,photoMinimumArea(2))
    }
    @Test fun widePhotoCentersInSquareHoop() {
        assertEquals(PhotoCrop(200, 0, 800, 800), photoCrop(1200, 800, 1.0, 1f, 0f, 0f))
    }
    @Test fun zoomAndExtremePanStayInsideSource() {
        val crop = photoCrop(1200, 800, 1.0, 2f, 20f, 20f)
        assertEquals(1200, crop.left + crop.width)
        assertEquals(800, crop.top + crop.height)
        val opposite = photoCrop(1200, 800, 1.0, 2f, -20f, -20f)
        assertEquals(0, opposite.left)
        assertEquals(0, opposite.top)
    }
    @Test fun narrowHoopPreservesAspectWithoutBlankMargins() {
        val crop = photoCrop(1200, 800, 1.0 / 3, 1f, 0f, 0f)
        assertEquals(1.0 / 3, crop.width.toDouble() / crop.height, 0.002)
        assertTrue(crop.left >= 0 && crop.top >= 0)
        assertTrue(crop.left + crop.width <= 1200 && crop.top + crop.height <= 800)
    }
}
