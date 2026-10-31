// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.photo

import android.graphics.Bitmap
import android.graphics.Canvas
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class PhotoCropDrawingTest {
    @Test fun containMarginsMatchCornerAndBothEndsRemainVisible() {
        for ((w, h) in listOf(200 to 100, 100 to 200)) {
            val source = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            source.eraseColor(0xff337755.toInt())
            val out = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
            drawPhotoCrop(Canvas(out), source, photoCrop(w, h, 1.0, 0.5f, 0f, 0f), 200f, 200f, false)
            assertEquals(0xff337755.toInt(), out.getPixel(0, 0))
            assertEquals(0xff337755.toInt(), out.getPixel(199, 199))
        }
    }
    @Test fun transparentCornerRemainsTransparentForColorAndWhiteForRedwork() {
        val source = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        for (preserve in listOf(true, false)) {
            val out = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
            drawPhotoCrop(Canvas(out), source, photoCrop(200, 100, 1.0, 0.5f, 0f, 0f), 200f, 200f, preserve)
            assertEquals(if (preserve) 0 else -1, out.getPixel(0, 0))
        }
    }
}
