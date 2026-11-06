// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.photo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhotoCropTest {
    @Test fun colorRegionRasterKeepsSourcePixelsUpTo1024() {
        assertEquals(1023 to 1023, photoRegionRaster(PhotoCrop(0, 0, 1023, 1023)))
        assertEquals(1024 to 1024, photoRegionRaster(PhotoCrop(0, 0, 1024, 1024)))
        assertEquals(1024 to 1024, photoRegionRaster(PhotoCrop(0, 0, 1025, 1025)))
        assertEquals(1024 to 768, photoRegionRaster(PhotoCrop(-10, 0, 1536, 1152)))
        assertEquals(683 to 1024, photoRegionRaster(PhotoCrop(0, 0, 1000, 1500)))
        assertEquals(1 to 1, photoRegionRaster(PhotoCrop(0, 0, 1, 1)))
    }

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
    @Test fun containMinimumAndZoomBoundariesAreSymmetric() {
        for ((width, height) in listOf(200 to 100, 100 to 200, 100 to 100)) {
            val minimum = photoMinimumZoom(width, height, 1.0)
            assertEquals(if (width == height) 1f else 0.5f, minimum)
            val contain = photoCrop(width, height, 1.0, minimum, 0f, 0f)
            assertTrue(contain.left <= 0 && contain.top <= 0)
            assertTrue(contain.left + contain.width >= width && contain.top + contain.height >= height)
            assertEquals(contain, photoCrop(width, height, 1.0, minimum - 0.01f, 0f, 0f))
            assertTrue(photoCrop(width, height, 1.0, minimum + 0.01f, 0f, 0f).width <= contain.width)
            assertEquals(photoCrop(width, height, 1.0, 4f, 0f, 0f),
                photoCrop(width, height, 1.0, 4.01f, 0f, 0f))
        }
    }
    @Test fun squareCoverBoundaryBelowAtAbove() {
        assertEquals(PhotoCrop(0, 0, 100, 100), photoCrop(100, 100, 1.0, 0.99f, 0f, 0f))
        assertEquals(PhotoCrop(0, 0, 100, 100), photoCrop(100, 100, 1.0, 1f, 0f, 0f))
        assertEquals(99, photoCrop(100, 100, 1.0, 1.01f, 0f, 0f).width)
    }
    @Test fun containPanKeepsEntireSourceWithinViewport() {
        for ((width, height) in listOf(200 to 100, 100 to 200, 100 to 100)) {
            for (pan in listOf(-2f, -1f, 0f, 1f, 2f)) {
                val c = photoCrop(width, height, 1.0, 0.1f, pan, pan)
                assertTrue(c.left <= 0 && c.top <= 0)
                assertTrue(c.left + c.width >= width && c.top + c.height >= height)
            }
        }
    }
    @Test fun rectangularHoopUsesItsOwnAspectForContain() {
        assertEquals(1f, photoMinimumZoom(200, 100, 2.0))
        assertEquals(0.25f, photoMinimumZoom(100, 200, 2.0))
        assertEquals(PhotoCrop(-150, 0, 400, 200), photoCrop(100, 200, 2.0, 0.25f, 0f, 0f))
    }
}
