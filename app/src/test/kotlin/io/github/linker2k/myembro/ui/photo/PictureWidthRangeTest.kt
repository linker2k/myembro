// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.photo

import io.github.linker2k.myembro.engine.edit.HoopSpec
import kotlin.test.Test
import kotlin.test.assertEquals

class PictureWidthRangeTest {
    @Test fun wholePictureFitsTheHoopInBothDirections() {
        assertEquals(20.0..99.9, pictureWidthRange(HoopSpec.H100, 1.0))
        assertEquals(20.0..99.9, pictureWidthRange(HoopSpec.H100, 0.5))   // wide picture: width limits
        assertEquals(20.0..92.8, pictureWidthRange(HoopSpec.H100, 1.076)) // cactus 50×53.8: height limits
    }
}
