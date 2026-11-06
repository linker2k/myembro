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

    @Test fun `long name keeps the original design file name and only replaces forbidden characters`() {
        assertEquals("01 빗 4종!@#\$^&-+(123).pes", UsbDesignNames.longName("01 빗 4종!@#\$^&-+(123).pes", emptyList()))
        assertEquals("봄 꽃.pes", UsbDesignNames.longName("folder/봄 꽃.pes", emptyList()))
        assertEquals("a_b.pes", UsbDesignNames.longName("a|b.pes", emptyList()))
        assertEquals("봄 꽃 (1).pes", UsbDesignNames.longName("봄 꽃.pes", listOf("봄 꽃.PES")))
    }

    @Test fun `batch names are planned in write order without own alias collisions`() {
        fun payload(name: String) = DirectUsbExporter.Payload(name, name, ByteArray(1), false)
        val names = DirectUsbExporter.names(emptyList(), listOf(payload("LEMON.PES"), payload("lemon.pes"), payload("봄.pes"), payload("봄.pes")))
        assertEquals(listOf("LEMON.PES" to null, "LEMON~1.PES" to "lemon (1).pes", "DESIGN.PES" to "봄.pes",
            "DESIGN~1.PES" to "봄 (1).pes"), names)
    }
}
