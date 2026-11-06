// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.usb

import io.github.linker2k.myembro.engine.fat12.Fat12Volume
import kotlin.test.*

class DirectUsbSpacePlanTest {
    private val small = Fat12Volume.Entry("SMALL.PES", 10, 32, 2, 0)
    private val large = Fat12Volume.Entry("LARGE.PES", 1200, 32, 3, 1)
    private val payload = DirectUsbExporter.Payload("new.pes", "new.pes", ByteArray(1000), false)
    // Root of 4 slots: SMALL at 0, LARGE at 1; [slots] free slots from index 2 on.
    private fun plan(free: Long = 0, slots: Int = 0, required: List<Int> = listOf(1)) = DirectUsbExporter.SpacePlan("snapshot",
        listOf(small, large), listOf(payload), emptyList(), free, 1024, (2 until 2 + slots).toSet(), 512, required, 4)

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
    @Test fun `long name slots must be contiguous after the selected deletions`() {
        val lfnSmall = small.copy(rootIndex = 1, longNameSlots = listOf(0), longName = "작은 꽃.pes")
        val gap = Fat12Volume.Entry("LARGE.PES", 1200, 32, 3, 3)
        fun p(required: List<Int>) = DirectUsbExporter.SpacePlan("snapshot", listOf(lfnSmall, gap), listOf(payload),
            emptyList(), 4096, 1024, setOf(2), 512, required, 4)
        assertTrue(p(listOf(3)).canProceed(setOf("SMALL.PES")))   // slots 0,1,2 contiguous: n
        assertFalse(p(listOf(4)).canProceed(setOf("SMALL.PES")))  // n+1
        assertTrue(p(listOf(4)).canProceed(setOf("SMALL.PES", "LARGE.PES")))
        assertTrue(p(listOf(2)).canProceed(setOf("LARGE.PES")))   // slots 2,3
        assertFalse(p(listOf(1, 2)).canProceed(setOf("LARGE.PES"))) // 3 entries, only 2 free
    }
}
