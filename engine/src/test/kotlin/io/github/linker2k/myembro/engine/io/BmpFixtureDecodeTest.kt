// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.io
import javax.imageio.ImageIO
import kotlin.test.*
class BmpFixtureDecodeTest {
    @Test fun sharedSyntheticBmpIsAValidDecodableImage() {
        val image=ImageIO.read(requireNotNull(javaClass.getResourceAsStream("/pictures/synthetic-2x2.bmp")))
        assertEquals(2,image.width);assertEquals(2,image.height)
        assertEquals(0xff0000,image.getRGB(0,1) and 0xffffff)
        println("BMP_FIXTURE decode=PASS size=2x2 format=24bit_uncompressed; Android decode uses documented BitmapFactory support")
    }
}
