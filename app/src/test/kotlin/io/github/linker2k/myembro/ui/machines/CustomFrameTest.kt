// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.machines

import io.github.linker2k.myembro.ui.settings.EmbroiderySettings
import io.github.linker2k.myembro.ui.Hoop
import kotlin.test.*

class CustomFrameTest {
    @Test fun `inch and mm input preserve tenth millimeter dimensions`() {
        val frame = CustomFrame.fromInput("Small", "1", "2.5", DimensionUnit.IN, "one")
        assertEquals(254, frame.spec.width)
        assertEquals(635, frame.spec.height)
        assertEquals(frame.copy(id = "two"), CustomFrame.fromInput("Small", "25.4", "63,5", DimensionUnit.MM, "two"))
        assertEquals(11, CustomFrame.fromInput("Round", "1.05", "1", DimensionUnit.MM).width)
    }
    @Test fun `invalid user dimensions are rejected`() {
        for (value in listOf("0", "-1", "NaN", "Infinity", "10001", "0.001", "999999999999999999999999")) {
            assertFails { CustomFrame.fromInput("Frame", value, "100", DimensionUnit.MM) }
        }
        assertFails { CustomFrame.fromInput(" ", "1", "1", DimensionUnit.MM) }
    }
    @Test fun `custom frame persistence roundtrips and isolates invalid records`() {
        val a = CustomFrame("a", "한글 \"후프\"", 254, 635)
        val b = CustomFrame("b", "Other", 731, 997)
        assertEquals(listOf(a,b), CustomFrameCodec.decode(CustomFrameCodec.encode(listOf(a,b))))
        val partlyCorrupt = CustomFrameCodec.encode(listOf(a)).dropLast(1) + ",{}, {\"id\":\"bad\",\"name\":\"bad\",\"width\":-1,\"height\":1}]"
        assertEquals(listOf(a), CustomFrameCodec.decode(partlyCorrupt))
        assertTrue(CustomFrameCodec.decode("invalid").isEmpty())
        assertTrue(CustomFrameCodec.decode(null).isEmpty())
    }
    @Test fun `editing selected frame updates size and deleting falls back without mutating projects`() {
        val a = CustomFrame("selected", "Frame", 731, 997)
        val settings = EmbroiderySettings(customFrames = listOf(a), defaultFrameId = a.id)
        assertEquals(a.spec, settings.defaultFrame.spec)
        assertEquals(500, settings.copy(customFrames = listOf(a.copy(width = 500))).defaultFrame.spec.width)
        assertEquals(Hoop.H100, settings.copy(customFrames = emptyList()).defaultFrame.preset)
    }
    @Test fun `free machines constrain by real chosen frame and known machines keep continuous maximum`() {
        val small = CustomFrame("small", "Small", 254, 635)
        val free = EmbroiderySettings(MachineProfiles.FREE.displayName, customFrames = listOf(small), defaultFrameId = small.id)
        assertEquals(MachineProfiles.FREE, free.machineProfile)
        assertEquals(254, free.exportProfile().maxWidth)
        assertEquals(635, free.exportProfile().maxHeight)
        assertNull(free.exportProfile().maxStitches)
        val large = small.copy(width = 1600, height = 2600)
        val se400 = EmbroiderySettings(customFrames = listOf(large), defaultFrameId = large.id)
        assertEquals(1000, se400.exportProfile().maxWidth)
        assertEquals(1000, se400.exportProfile().maxHeight)
        assertEquals(30_000, se400.exportProfile().maxStitches)
        assertEquals(1600, se400.defaultFrame.spec.width)
    }
}
