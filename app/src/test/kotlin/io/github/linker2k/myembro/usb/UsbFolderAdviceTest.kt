// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.usb

import kotlin.test.*

class UsbFolderAdviceTest {
    @Test fun `drive root is recognised from the tree document id`() {
        assertTrue(UsbFolderAdvice.isRoot("1A2B-3C4D:"))
        assertTrue(UsbFolderAdvice.isRoot("1A2B-3C4D:/"))
        assertFalse(UsbFolderAdvice.isRoot("1A2B-3C4D:Embroidery"))
        assertFalse(UsbFolderAdvice.isRoot("1A2B-3C4D:Embroidery/Flowers"))
    }

    @Test fun `root entry warning starts at the documented 200 limit`() {
        assertEquals(emptySet(), UsbFolderAdvice.notices("1A2B-3C4D:", 199))
        assertEquals(setOf(UsbTransferNotice.MANY_ENTRIES), UsbFolderAdvice.notices("1A2B-3C4D:", 200))
        assertEquals(setOf(UsbTransferNotice.MANY_ENTRIES), UsbFolderAdvice.notices("1A2B-3C4D:", 201))
    }

    @Test fun `sub-folder gets the root folder advice only`() {
        assertEquals(setOf(UsbTransferNotice.NOT_ROOT_FOLDER), UsbFolderAdvice.notices("1A2B-3C4D:Designs", 1))
        // The root cannot be listed without its own grant, so a sub-folder count is never reported as the root's.
        assertEquals(setOf(UsbTransferNotice.NOT_ROOT_FOLDER), UsbFolderAdvice.notices("1A2B-3C4D:Designs", 250))
    }
}
