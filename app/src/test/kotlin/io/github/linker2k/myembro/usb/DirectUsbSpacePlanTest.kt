// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.usb

import io.github.linker2k.myembro.engine.fat12.Fat12Volume
import kotlin.test.*

class DirectUsbSpacePlanTest {
    private val small = Fat12Volume.Entry("SMALL.PES", 10, 32, 2, 0)
    private val large = Fat12Volume.Entry("LARGE.PES", 1200, 32, 3, 1)
    private val payload = DirectUsbExporter.Payload("new.pes", "new.pes", ByteArray(1000), false)
    private fun plan(free: Long = 0, slots: Int = 0) = DirectUsbExporter.SpacePlan("snapshot", listOf(small, large),
        listOf(payload), emptyList(), free, 1024, slots, 512)

    @Test fun `nothing is preselected and only explicitly selected existing PES can enable deletion`() {
        assertFalse(plan().canProceed(emptySet()))
        assertFalse(plan().canProceed(setOf("SMALL.PES")))
        assertFalse(plan().canProceed(setOf("OTHER.PES")))
        assertTrue(plan().canProceed(setOf("LARGE.PES")))
    }
    @Test fun `both rounded allocation bytes and root entries must suffice`() {
        assertFalse(plan(free = 512).canProceed(emptySet())) // root full
        assertTrue(plan(free = 512).canProceed(setOf("SMALL.PES")))
        assertFalse(plan(slots = 2).canProceed(setOf("SMALL.PES"))) // enough slots, still not enough data
        assertTrue(plan(slots = 2).canProceed(setOf("SMALL.PES", "LARGE.PES")))
    }
}
