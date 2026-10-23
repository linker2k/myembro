// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.usb

import io.github.linker2k.myembro.engine.fat12.ShortNames
import java.util.Locale
import kotlin.test.*

class UsbDesignNamesTest {
    @Test fun `original semantic name survives generic prefix and duplicate suffix stays inside eight characters`() {
        assertEquals("LEMON.PES", UsbDesignNames.unique("Embroidery-Lemon.pes", emptyList()))
        assertEquals("LEMON.PES", UsbDesignNames.unique("EMBROIDERYLEMON.PES", emptyList()))
        assertEquals("LEMON.PES", UsbDesignNames.unique("EmbroideryDesignLemon.pes", emptyList()))
        assertEquals("LEMON.PES", UsbDesignNames.unique("sample_pattern_lemon.pes", emptyList()))
        assertEquals("LEMON.PES", UsbDesignNames.unique("lemon.myembro", emptyList()))
        assertEquals("LEMON~1.PES", UsbDesignNames.unique("lemon.pes", listOf("lemon.pes")))
        assertEquals("LEMON~2.PES", UsbDesignNames.unique("lemon.pes", listOf("LEMON.PES", "LEMON~1.PES")))
        assertEquals("LEMON~1.PES", UsbDesignNames.unique("Embroidery Lemon Flower.pes", listOf("LEMON_FL.PES")))
    }
    @Test fun `generic only filename can use real design label and never a thread brand as default`() {
        assertTrue(UsbDesignNames.needsDesignLabel("EMBROIDERY.PES"))
        assertFalse(UsbDesignNames.needsDesignLabel("Embroidery Lemon.pes"))
        assertEquals("LEMON.PES", UsbDesignNames.unique("embroidery.pes", emptyList(), "Lemon"))
        assertEquals("LEMON.PES", UsbDesignNames.unique("EMBROIDE.PES", emptyList(), "Lemon"))
        assertEquals("DESIGN.PES", UsbDesignNames.unique("embroidery.pes", emptyList(), "EMBROIDERY"))
        assertEquals("LEMON.PES", UsbDesignNames.unique("lemon.pes", emptyList(), "wrong metadata"))
        assertEquals("EMBROI~1.PES", ShortNames.unique("EMBROI~1.PES", emptyList()))
    }
    @Test fun `machine KB rounds binary units but preserves exact allocation including empty files`() {
        assertEquals(12288L, usbAllocatedBytes(10120, 4096))
        assertEquals(0L, usbAllocatedBytes(0, 4096))
        assertEquals(4096L, usbAllocatedBytes(4096, 4096))
        assertEquals(8192L, usbAllocatedBytes(4097, 4096))
        assertEquals("10KB", formatUsbBytes(10120, Locale.US))
        assertEquals("12KB", formatUsbBytes(12288, Locale.US))
        assertFailsWith<IllegalArgumentException> { usbAllocatedBytes(-1, 4096) }
        assertFailsWith<IllegalArgumentException> { usbAllocatedBytes(1, 0) }
    }
}
