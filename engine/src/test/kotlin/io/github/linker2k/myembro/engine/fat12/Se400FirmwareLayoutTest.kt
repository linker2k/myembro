// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.fat12

import java.io.File
import kotlin.test.*

/** Reported MBR fields are literal; unspecified boot code/CHS bytes are zero-filled.
 * The partition BPB/FAT are synthetic: the real LBA 32 contents have not been supplied yet.
 */
class Se400FirmwareLayoutTest {
    private class Disk(val bytes: ByteArray) : BlockDevice {
        override val sectorSize = 512
        override val sectorCount = 988L // READ CAPACITY lastLBA=987.
        val reads = mutableListOf<Long>()
        val writes = mutableListOf<Pair<Long, Int>>()
        override fun readSectors(lba: Long, count: Int): ByteArray {
            require(lba >= 0 && count > 0 && lba + count <= sectorCount)
            reads += lba
            return bytes.copyOfRange(lba.toInt() * 512, (lba.toInt() + count) * 512)
        }
        override fun writeSectors(lba: Long, data: ByteArray) {
            require(data.isNotEmpty() && data.size % 512 == 0)
            val count = data.size / 512
            require(lba >= 0 && lba + count <= sectorCount)
            writes += lba to count
            data.copyInto(bytes, lba.toInt() * 512)
        }
    }
    private fun put16(bytes: ByteArray, offset: Int, value: Int) {
        repeat(2) { bytes[offset + it] = (value ushr (8 * it)).toByte() }
    }
    private fun put32(bytes: ByteArray, offset: Int, value: Long) {
        repeat(4) { bytes[offset + it] = (value ushr (8 * it)).toByte() }
    }
    private fun disk(total: Int = 987): Disk {
        val bytes = ByteArray(988 * 512)
        // Preserve recognizable bytes outside the partition to catch accidental prefix writes.
        bytes.fill(0x6D, 512, 32 * 512)
        val entry = byteArrayOf(0x80.toByte(), 0, 0, 0, 1, 0, 0, 0,
            0x20, 0, 0, 0, 0xDB.toByte(), 3, 0, 0)
        entry.copyInto(bytes, 446)
        bytes[510] = 0x55; bytes[511] = 0xAA.toByte()
        val boot = 32 * 512
        byteArrayOf(0xEB.toByte(), 0x3C, 0x90.toByte()).copyInto(bytes, boot)
        "MSDOS5.0".toByteArray().copyInto(bytes, boot + 3)
        put16(bytes, boot + 11, 512); bytes[boot + 13] = 1
        put16(bytes, boot + 14, 1); bytes[boot + 16] = 2
        put16(bytes, boot + 17, 224); put16(bytes, boot + 19, total)
        bytes[boot + 21] = 0xF8.toByte(); put16(bytes, boot + 22, 3)
        put16(bytes, boot + 24, 32); put16(bytes, boot + 26, 64)
        put32(bytes, boot + 28, 32)
        bytes[boot + 36] = 0x80.toByte(); bytes[boot + 38] = 0x29
        "NO NAME    ".toByteArray().copyInto(bytes, boot + 43)
        "FAT12   ".toByteArray().copyInto(bytes, boot + 54)
        bytes[boot + 510] = 0x55; bytes[boot + 511] = 0xAA.toByte()
        for (fat in listOf(33, 36)) byteArrayOf(0xF8.toByte(), 0xFF.toByte(), 0xFF.toByte()).copyInto(bytes, fat * 512)
        "NO NAME    ".toByteArray().copyInto(bytes, 39 * 512)
        bytes[39 * 512 + 11] = 8 // Root volume label must also stay untouched.
        return Disk(bytes)
    }

    @Test fun `reported 988 block SE400 MBR clamps to 956 and round trips through the last sector`() {
        val disk = disk()
        assertEquals(0x80, disk.bytes[446].toInt() and 255)
        assertEquals(1, disk.bytes[450].toInt())
        assertContentEquals(byteArrayOf(0x20, 0, 0, 0, 0xDB.toByte(), 3, 0, 0), disk.bytes.copyOfRange(454, 462))
        assertTrue(disk.bytes.copyOfRange(11, 36).all { it == 0.toByte() }) // Pure MBR, no sector0 BPB.
        val prefixAndBoot = disk.bytes.copyOfRange(0, 33 * 512)
        val rootLabel = disk.bytes.copyOfRange(39 * 512, 39 * 512 + 32)
        var fs = Fat12Volume.mount(disk)
        assertTrue(disk.writes.isEmpty())
        assertEquals(32L, fs.geometry.startSector)
        assertEquals(956L, fs.geometry.totalSectors)
        assertEquals(956L * 512, fs.capacityBytes)
        assertTrue(fs.warnings.any { it.contains("MBR partition sectors=987 clamped") && it.contains("956") })
        assertTrue(fs.warnings.any { it.contains("BPB total sectors=987 clamped") && it.contains("956") })
        val pes = File("src/test/resources/fixtures/flower.v1.pes").readBytes()
        val name = fs.writeFile("flower.pes", pes).name
        val fill = ByteArray(fs.freeBytes.toInt()) { (it * 7).toByte() }
        val filler = fs.writeFile("fill.pes", fill).name
        val writes = disk.writes.size
        fs = Fat12Volume.mount(disk)
        assertEquals(writes, disk.writes.size)
        assertContentEquals(pes, fs.readFile(name))
        assertContentEquals(fill, fs.readFile(filler))
        assertEquals(0L, fs.freeBytes)
        assertTrue(disk.writes.any { (lba, count) -> lba + count == 988L }) // Includes physical lastLBA=987.
        assertTrue(disk.writes.all { (lba, count) -> lba >= 32 && lba - 32 + count <= 956 })
        assertContentEquals(prefixAndBoot, disk.bytes.copyOfRange(0, 33 * 512))
        assertContentEquals(rootLabel, disk.bytes.copyOfRange(39 * 512, 39 * 512 + 32))
        assertContentEquals(disk.readSectors(33, 3), disk.readSectors(36, 3))
        val before = disk.bytes.copyOf()
        assertFailsWith<Fat12Volume.NoSpace> { fs.writeFile("extra.pes", byteArrayOf(1)) }
        assertContentEquals(before, disk.bytes)
    }

    @Test fun `already bounded BPB keeps 956 sectors while MBR alone emits a warning`() {
        val disk = disk(total = 956)
        val fs = Fat12Volume.mount(disk)
        assertEquals(956L, fs.geometry.totalSectors)
        assertTrue(fs.warnings.any { it.startsWith("MBR partition") })
        assertFalse(fs.warnings.any { it.startsWith("BPB total") })
    }

    @Test fun `invalid starts and zero count remain blocked before partition reads`() {
        for ((start, count) in listOf(0L to 987L, 988L to 987L, 989L to 987L, 0xFFFF_FFFFL to 987L, 32L to 0L)) {
            val disk = disk()
            put32(disk.bytes, 454, start); put32(disk.bytes, 458, count)
            assertFailsWith<Fat12Volume.MountFailure> { Fat12Volume.mount(disk) }
            assertEquals(listOf(0L), disk.reads)
            assertTrue(disk.writes.isEmpty())
        }
    }

    @Test fun `clamped partition blocks metadata or incomplete data clusters and exposes failed BPB`() {
        for ((reserved, spc) in listOf(950 to 1, 934 to 4)) {
            val disk = disk()
            put16(disk.bytes, 32 * 512 + 14, reserved)
            disk.bytes[32 * 512 + 13] = spc.toByte()
            val captured = linkedMapOf<Long, ByteArray>()
            val error = assertFailsWith<Fat12Volume.MountFailure> { Fat12Volume.mount(disk) { lba, bytes -> captured[lba] = bytes.copyOf() } }
            assertTrue(error.mbrReason.contains("MBR partition sectors=987 clamped"))
            assertTrue(error.mbrReason.contains(if (spc == 1) "metadata" else "No complete FAT12 data clusters"))
            assertEquals(setOf(0L, 32L), captured.keys)
            assertEquals(listOf(0L, 32L), disk.reads)
            assertContentEquals(disk.bytes.copyOfRange(32 * 512, 33 * 512), captured.getValue(32))
            assertTrue(disk.writes.isEmpty())
        }
    }

    @Test fun `existing chain outside clamped data area remains blocked`() {
        val disk = disk()
        val cluster = 937 // Effective data clusters are 2..936; original BPB had a larger range.
        for (start in listOf(33, 36)) {
            val offset = start * 512 + cluster + cluster / 2
            put16(disk.bytes, offset, 0xFFF0)
        }
        val entry = 39 * 512 + 32
        "OUTSIDE PES".toByteArray().copyInto(disk.bytes, entry)
        disk.bytes[entry + 11] = 0x20
        put16(disk.bytes, entry + 26, cluster); put32(disk.bytes, entry + 28, 512)
        val error = assertFailsWith<Fat12Volume.MountFailure> { Fat12Volume.mount(disk) }
        assertTrue(error.mbrReason.contains("cluster chain"))
        assertTrue(disk.writes.isEmpty())
    }
}
