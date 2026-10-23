// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.usb

import io.github.linker2k.myembro.engine.fat12.BlockDevice
import io.github.linker2k.myembro.engine.fat12.Fat12Volume
import java.io.Closeable
import java.io.IOException
import kotlin.test.*

class UsbTransferReportTest {
    /** Exact observed SE400 geometry, synthesized empty FAT/root; no hardware or disk-image fixture. */
    private class Disk : BlockDevice {
        override val sectorSize = 512
        override val sectorCount = 988L
        val bytes = ByteArray(988 * 512)
        var failAt: Long? = null
        init {
            fun put16(offset: Int, value: Int) { bytes[offset] = value.toByte(); bytes[offset + 1] = (value ushr 8).toByte() }
            fun put32(offset: Int, value: Int) { repeat(4) { bytes[offset + it] = (value ushr (8 * it)).toByte() } }
            bytes[446] = 0x80.toByte(); bytes[450] = 1; put32(454, 32); put32(458, 987); put16(510, 0xAA55)
            val b = 32 * 512
            bytes[b] = 0xEB.toByte(); bytes[b + 1] = 0x3C; bytes[b + 2] = 0x90.toByte()
            put16(b + 11, 512); bytes[b + 13] = 8; put16(b + 14, 1); bytes[b + 16] = 2
            put16(b + 17, 512); put16(b + 19, 987); bytes[b + 21] = 0xF8.toByte(); put16(b + 22, 1)
            put32(b + 28, 32); put16(b + 510, 0xAA55)
        }
        override fun readSectors(lba: Long, count: Int): ByteArray = bytes.copyOfRange((lba * 512).toInt(), ((lba + count) * 512).toInt())
        override fun writeSectors(lba: Long, data: ByteArray) {
            if (lba == failAt) throw IOException("FAT12 write verification failed")
            assertTrue(lba >= 32 && lba + data.size / 512 <= 988)
            data.copyInto(bytes, (lba * 512).toInt())
        }
    }
    private fun session(disk: Disk): DirectUsbAccess.Session {
        val capture = UsbDiagnosticCapture()
        val fs = Fat12Volume.mount(disk, capture)
        capture.warnings = fs.warnings
        return DirectUsbAccess.Session(fs, Closeable {}, { capture.report("SE400") }) {
            DirectUsbFailure(it, capture.report("SE400", it))
        }
    }
    private fun payload(name: String, size: Int = 100) = DirectUsbExporter.Payload(name, name, ByteArray(size) { 42 }, false)

    @Test fun `success stores actual short names byte sizes free space and folded diagnostics without firmware warnings`() {
        val disk = Disk()
        session(disk).use { s ->
            val complete = DirectUsbExporter.write(s, listOf(payload("lemon.pes")), emptyList()) as DirectUsbExporter.Result.Complete
            val report = complete.report
            assertEquals(listOf(UsbTransferredFile("LEMON.PES", 100, 4096, 4096, "lemon.pes")), report.files)
            assertEquals(114L * 4096, report.remainingBytes)
            assertTrue(report.warnings.any { "reserved entries" in it })
            assertTrue(report.notices.isEmpty(), report.warnings.joinToString("\n")) // Known SE400 quirks do not alarm the user.
            assertContains(report.diagnostics, "FAT1 (LBA 33)")
            assertTrue(report.warnings.all { expectedSe400Note(it, s.volume.geometry) })
            assertContentEquals(ByteArray(3), disk.bytes.copyOfRange(33 * 512, 33 * 512 + 3))
            val collision = DirectUsbExporter.write(s, listOf(payload("lemon.pes", 200)), emptyList()) as DirectUsbExporter.Result.Complete
            assertEquals(listOf(UsbTransferredFile("LEMON~1.PES", 200, 4096, 4096, "lemon.pes")), collision.report.files)
            assertEquals(setOf(UsbTransferNotice.NAME_COLLISION), collision.report.notices)
            assertEquals(113L * 4096, collision.report.remainingBytes)
        }
    }

    @Test fun `observed 10120 byte file occupies three 4KB clusters and free space uses 115 usable clusters`() {
        val disk = Disk()
        session(disk).use { s ->
            assertEquals(115L * 4096, s.volume.freeBytes)
            val result = DirectUsbExporter.write(s, listOf(payload("embroidery_lemon.pes", 10120)), emptyList()) as DirectUsbExporter.Result.Complete
            val file = result.report.files.single()
            assertEquals(UsbTransferredFile("LEMON.PES", 10120, 12288, 4096, "embroidery_lemon.pes"), file)
            assertEquals(458752L, result.report.remainingBytes)
            assertEquals("10KB", formatUsbBytes(file.size))
            assertEquals("12KB", formatUsbBytes(file.allocatedBytes!!))
            assertEquals("448KB", formatUsbBytes(result.report.remainingBytes!!))
            assertContains(result.report.diagnostics, "Usable data bytes: 115 x 4096 = 471040")
            // Allocation is rounded separately for each file, never for a combined byte count.
            val next = DirectUsbExporter.write(s, listOf(payload("other.pes", 1)), emptyList()) as DirectUsbExporter.Result.Complete
            assertEquals(4096L, next.report.files.single().allocatedBytes)
            assertEquals(111L * 4096, next.report.remainingBytes)
            s.volume.deleteFile("LEMON.PES")
            assertEquals(114L * 4096, s.volume.freeBytes)
        }
        session(disk).use { assertEquals(114L * 4096, it.volume.freeBytes) }
    }

    @Test fun `partial failure preserves successful metadata and never queries poisoned free space`() {
        val disk = Disk()
        disk.failAt = 75 // First cluster for second file, after first file has been verified.
        session(disk).use { s ->
            val result = DirectUsbExporter.write(s, listOf(payload("a.pes"), payload("b.pes"), payload("c.pes")), emptyList()) as DirectUsbExporter.Result.Complete
            assertEquals(listOf(UsbTransferredFile("A.PES", 100, 4096, 4096, "a.pes")), result.report.files)
            assertEquals(listOf("b.pes", "c.pes"), result.report.failures.map { it.first })
            assertNull(result.report.remainingBytes)
            assertNotNull(result.failure)
            assertContains(result.report.diagnostics, "write verification failed")
        }
    }

    @Test fun `unexpected FAT mismatch and other geometries are not silenced`() {
        val disk = Disk()
        disk.bytes[34 * 512] = 0xF8.toByte(); disk.bytes[34 * 512 + 1] = 0xFF.toByte(); disk.bytes[34 * 512 + 2] = 0xFF.toByte()
        session(disk).use { s ->
            val report = (DirectUsbExporter.write(s, listOf(payload("a.pes")), emptyList()) as DirectUsbExporter.Result.Complete).report
            assertContains(report.notices, UsbTransferNotice.FILESYSTEM)
            assertFalse(expectedSe400Note("Nonstandard FAT1 reserved entries: FAT[0]=0x123 FAT[1]=0x000 (unchanged)", s.volume.geometry))
            assertFalse(expectedSe400Note(s.volume.warnings.first(), s.volume.geometry.copy(clusterCount = 114)))
        }
    }
}
