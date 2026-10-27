// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.photo

import kotlin.test.*
class PictureInboxTest {
    @Test fun onlyLatestRequestSurvivesAndOldAsyncInspectionCannotPublish() {
        val box=LatestPictureInbox();val first=box.reserve()
        assertTrue(box.offer(first,bytes=byteArrayOf(1),title="old"))
        val second=box.reserve();assertNull(box.pending.value)
        assertFalse(box.offer(first,uri="content://old"))
        assertTrue(box.offer(second,uri="content://latest"))
        assertNull(box.take(first));assertEquals("content://latest",box.take(second)?.uri)
        assertNull(box.pending.value);assertNull(box.take(second))
    }
    @Test fun rasterBytesStayInMemoryAreCopiedAndAreDroppedOnReplacement() {
        val box=LatestPictureInbox();val bytes=byteArrayOf(1,2,3)
        val id=box.reserve();box.offer(id,bytes=bytes,colors=3);bytes[0]=9
        assertContentEquals(byteArrayOf(1,2,3),box.pending.value!!.bytes)
        val next=box.reserve();assertNull(box.take(id));assertNull(box.pending.value)
        box.offer(next,uri="content://new");assertNull(box.pending.value!!.bytes)
    }
    @Test fun syntheticBmpContentIsDetectedEvenWithWrongSuffix() {
        val bytes=java.io.File("../engine/src/test/resources/pictures/synthetic-2x2.bmp").readBytes()
        assertEquals(PictureIntake.Kind.BMP,PictureIntake.sniff(bytes,"wrong.pes"))
        assertTrue(PictureIntake.sniff(bytes).raster)
        assertEquals(PictureIntake.Kind.UNKNOWN,PictureIntake.sniff("BM".toByteArray()))
    }
}
