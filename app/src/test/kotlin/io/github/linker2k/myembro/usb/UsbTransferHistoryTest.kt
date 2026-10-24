// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.usb

import java.nio.file.Files
import kotlin.test.*

class UsbTransferHistoryTest {
    @Test fun `keeps latest twenty and restores pending result until explicitly dismissed`() {
        val dir = Files.createTempDirectory("usb-history").toFile()
        try {
            val file = dir.resolve("history.json")
            val store = UsbTransferHistory(file)
            repeat(25) { i -> store.add(UsbTransferRecord(id = "$i", time = i.toLong(), drive = "SE400",
                files = listOf(UsbTransferredFile("FILE$i.PES", 2048L + i)), remainingBytes = 380_000)) }
            val restored = UsbTransferHistory(file).read()
            assertEquals((24 downTo 5).map(Int::toString), restored.records.map { it.id })
            assertEquals("24", restored.pending?.id)
            // Closing an older history entry cannot dismiss the current transfer result.
            store.dismiss("23")
            assertEquals("24", store.read().pending?.id)
            store.dismiss("24")
            assertNull(UsbTransferHistory(file).read().pending)
            assertEquals(20, store.read().records.size)
            assertFalse(dir.resolve("history.json.tmp").exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun `round trips exact bytes diagnostics Unicode and unknown remaining space`() {
        val dir = Files.createTempDirectory("usb-history").toFile()
        try {
            val store = UsbTransferHistory(dir.resolve("history.json"))
            val record = UsbTransferRecord(id = "partial", time = 1_792_723_400_000, drive = "SE400 직접 연결",
                files = listOf(UsbTransferredFile("LEMON~1.PES", 12_345, 16384, 4096, "Embroidery Lemon.pes", manualJumpThreadCount = 3, automaticJumpTrim = false, machineName = "Brother SE400")),
                failures = listOf("안 된 도안.pes" to "FAT12 file read-back differs: BROKEN.PES"),
                notices = setOf(UsbTransferNotice.NAME_COLLISION), technical = "FAT1: 00 00 00\nMBR length clamped\n\"raw\"")
            store.add(record)
            val restored = store.read().pending!!
            assertEquals(record, restored)
            assertEquals(false, restored.files.single().automaticJumpTrim)
            assertEquals("Brother SE400", restored.files.single().machineName)
            assertEquals(3, restored.manualJumpThreadCount)
            assertEquals(16384L, restored.allocatedBytes)
            assertEquals(4096, restored.allocationUnitBytes)
            assertContains(restored.diagnostics(), "allocated=16384 bytes; cluster=4096 bytes; source=Embroidery Lemon.pes")
            assertEquals(UsbTransferOutcome.PARTIAL, restored.outcome)
            assertEquals(UsbTransferProblem.VERIFY, restored.problem)
            assertNull(restored.remainingBytes)
            assertContains(restored.diagnostics(), "Sent: LEMON~1.PES; 12345 bytes")
            assertContains(restored.diagnostics(), "Remaining bytes: unknown")
            assertContains(restored.diagnostics(), "안 된 도안.pes")
            assertContains(restored.diagnostics(), record.technical)
        } finally { dir.deleteRecursively() }
    }

    @Test fun `legacy logs without allocation fields remain readable and unknown instead of assuming 4KB`() {
        val dir = Files.createTempDirectory("usb-history-legacy").toFile()
        try {
            val file = dir.resolve("history.json")
            file.writeText("""{"pendingId":"old","records":[{"id":"old","time":1,"drive":"USB","files":[{"name":"EMBROIDE.PES","size":10120}],"failures":[],"remainingBytes":389120,"notices":[],"technical":"old log"}]}""")
            val record = UsbTransferHistory(file).read().pending!!
            assertEquals(10120L, record.files.single().size)
            assertNull(record.allocatedBytes)
            assertNull(record.allocationUnitBytes)
            assertNull(record.files.single().sourceName)
            assertNull(record.manualJumpThreadCount)
            assertNull(record.files.single().automaticJumpTrim)
            assertNull(record.files.single().machineName)
        } finally { dir.deleteRecursively() }
    }

    @Test fun `classifies success partial failure and actionable failure categories`() {
        fun failed(reason: String) = UsbTransferRecord(drive = "USB", failures = listOf("A.PES" to reason))
        assertEquals(UsbTransferOutcome.FAILURE, failed("permission denied").outcome)
        assertEquals(UsbTransferProblem.PERMISSION, failed("USB access denied").problem)
        assertEquals(UsbTransferProblem.SPACE, failed("FAT12 space/root entries insufficient").problem)
        assertEquals(UsbTransferProblem.DISCONNECTED, failed("USB device disconnected").problem)
        assertEquals(UsbTransferProblem.VERIFY, failed("FAT12 file read-back differs").problem)
        assertEquals(UsbTransferProblem.PERMISSION, UsbTransferRecord(drive = "USB",
            failures = listOf("도안.pes" to "USB 접근이 허용되지 않았습니다"), problemHint = UsbTransferProblem.PERMISSION).problem)
        assertEquals(UsbTransferOutcome.SUCCESS, UsbTransferRecord(drive = "USB",
            files = listOf(UsbTransferredFile("A.PES", 0)), remainingBytes = 0).outcome)
    }
}
