// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.fat12

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.*

class Fat12Test {
    private class Disk(val file: File) : BlockDevice {
        override val sectorSize = 512
        override val sectorCount get() = file.length() / sectorSize
        var writes = 0
        val reads = mutableListOf<Long>()
        override fun readSectors(lba: Long, count: Int): ByteArray = RandomAccessFile(file, "r").use {
            reads += lba
            it.seek(lba * sectorSize); ByteArray(count * sectorSize).also(it::readFully)
        }
        override fun writeSectors(lba: Long, data: ByteArray) {
            require(data.size % sectorSize == 0 && lba >= 0 && lba + data.size / sectorSize <= sectorCount)
            writes++
            RandomAccessFile(file, "rw").use { it.seek(lba * sectorSize); it.write(data) }
        }
    }
    private fun command(vararg args: String): String {
        val p = ProcessBuilder(*args).redirectErrorStream(true).start()
        assertTrue(p.waitFor(15, TimeUnit.SECONDS), args.joinToString())
        val result = p.inputStream.bufferedReader().readText()
        assertEquals(0, p.exitValue(), "${args.joinToString()}\n$result")
        return result
    }
    private fun image(roots: Int = 224, test: (Disk) -> Unit) {
        val dir = Files.createTempDirectory("myembro-fat12-").toFile()
        try {
            val file = File(dir, "volume.img")
            command("/usr/sbin/mkfs.fat", "-F", "12", "-C", "-s", "1", "-f", "2", "-r", "$roots", file.path, "480")
            assertEquals(480L * 1024, file.length())
            test(Disk(file))
        } finally { dir.deleteRecursively() }
    }
    private fun clean(disk: Disk) {
        val output = command("/usr/sbin/fsck.fat", "-n", disk.file.path)
        assertFalse(output.contains("Reclaiming"), output)
    }

    @Test fun `mkfs superfloppy writes PES reads back deletes and passes fsck`() = image { disk ->
        var fs = Fat12Volume.mount(disk)
        assertEquals(0L, fs.geometry.startSector)
        assertEquals(2, fs.geometry.fatCount)
        val free = fs.freeBytes
        val pes = File("src/test/resources/fixtures/flower.v1.pes").readBytes()
        val entry = fs.writeFile("봄날 embroidery.pes", pes)
        assertTrue(entry.name.matches(Regex("[A-Z0-9_-]{1,8}\\.PES")))
        fs = Fat12Volume.mount(disk) // new mount, no cached file data
        assertContentEquals(pes, fs.readFile(entry.name))
        assertTrue(fs.freeBytes < free)
        clean(disk)
        fs.deleteFile(entry.name)
        assertEquals(free, Fat12Volume.mount(disk).freeBytes)
        assertTrue(fs.listRoot().isEmpty())
        clean(disk)
    }

    @Test fun `first MBR partition is used and partition table stays untouched`() = image { partition ->
        val payload = partition.file.readBytes()
        val start = 63
        val mbr = ByteArray(start * 512)
        mbr[446 + 4] = 1
        fun put32(offset: Int, value: Int) { for (i in 0..3) mbr[offset + i] = (value ushr (i * 8)).toByte() }
        put32(446 + 8, start); put32(446 + 12, payload.size / 512)
        mbr[510] = 0x55; mbr[511] = 0xAA.toByte()
        val whole = File(partition.file.parentFile, "mbr.img").apply { writeBytes(mbr + payload) }
        val disk = Disk(whole)
        val fs = Fat12Volume.mount(disk)
        assertEquals(start.toLong(), fs.geometry.startSector)
        val bytes = ByteArray(1307) { (it * 13).toByte() }
        val entry = fs.writeFile("design.pes", bytes)
        assertContentEquals(bytes, Fat12Volume.mount(disk).readFile(entry.name))
        assertContentEquals(mbr, disk.readSectors(0, start))
        // fsck expects a volume image, not the containing partitioned disk.
        partition.file.writeBytes(disk.readSectors(start.toLong(), payload.size / 512))
        clean(partition)
    }

    @Test fun `packed odd even and sector crossing FAT entries survive fragmentation`() = image { disk ->
        val fs = Fat12Volume.mount(disk)
        val small = fs.writeFile("first.pes", ByteArray(7 * fs.geometry.clusterBytes) { 17 })
        val largeBytes = ByteArray(350 * fs.geometry.clusterBytes + 19) { (it * 7).toByte() }
        val large = fs.writeFile("second.pes", largeBytes)
        val last = fs.writeFile("third.pes", ByteArray(1025) { 32 })
        fs.deleteFile(small.name)
        val mixed = ByteArray(20 * fs.geometry.clusterBytes + 11) { (it * 3).toByte() }
        val replacement = fs.writeFile("first.pes", mixed)
        val fresh = Fat12Volume.mount(disk)
        assertContentEquals(largeBytes, fresh.readFile(large.name))
        assertContentEquals(mixed, fresh.readFile(replacement.name))
        assertContentEquals(ByteArray(1025) { 32 }, fresh.readFile(last.name))
        val g = fs.geometry
        assertContentEquals(disk.readSectors(g.fatSector, g.sectorsPerFat), disk.readSectors(g.fatSector + g.sectorsPerFat, g.sectorsPerFat))
        clean(disk)
    }

    @Test fun `disk full and batch preflight do not change existing bytes`() = image { disk ->
        val fs = Fat12Volume.mount(disk)
        fs.writeFile("full.pes", ByteArray(fs.freeBytes.toInt()) { 91 })
        val before = disk.file.readBytes()
        val writes = disk.writes
        assertFailsWith<Fat12Volume.NoSpace> { fs.writeFile("extra.pes", byteArrayOf(1)) }
        assertFailsWith<Fat12Volume.NoSpace> { fs.checkSpace(listOf(1, 2)) }
        assertEquals(writes, disk.writes)
        assertContentEquals(before, disk.file.readBytes())
        fs.deleteFile("FULL.PES")
        assertTrue(fs.freeBytes > 0)
        clean(disk)
    }

    @Test fun `fixed root full rejects without allocation and deleted slots can be reused`() = image(16) { disk ->
        val fs = Fat12Volume.mount(disk)
        repeat(fs.freeRootEntries) { fs.writeFile("FILE$it.PES", byteArrayOf()) }
        assertEquals(0, fs.freeRootEntries)
        val before = disk.file.readBytes()
        assertFailsWith<Fat12Volume.NoSpace> { fs.writeFile("EXTRA.PES", byteArrayOf(1)) }
        assertContentEquals(before, disk.file.readBytes())
        fs.deleteFile("FILE0.PES")
        fs.writeFile("NEW.PES", byteArrayOf(1,2,3))
        assertContentEquals(byteArrayOf(1,2,3), Fat12Volume.mount(disk).readFile("NEW.PES"))
        clean(disk)
    }

    @Test fun `short name aliases are uppercase unique and never overwrite`() = image { disk ->
        val fs = Fat12Volume.mount(disk)
        val a = fs.writeFile("longfilename.pes", byteArrayOf(1))
        val b = fs.writeFile("longfilename.PES", byteArrayOf(2))
        assertEquals("LONGFILE.PES", a.name)
        assertEquals("LONGFI_1.PES", b.name)
        assertContentEquals(byteArrayOf(1), fs.readFile(a.name))
        assertContentEquals(byteArrayOf(2), fs.readFile(b.name))
        assertEquals("DESIGN.PES", ShortNames.unique("한글.pes", emptyList()))
        assertEquals("_CON.PES", ShortNames.unique("con.pes", emptyList()))
        val root = disk.readSectors(fs.geometry.rootSector, fs.geometry.rootSectors)
        assertEquals(0x20, root[11].toInt())
        assertEquals(0x20, root[32+11].toInt())
        clean(disk)
    }

    @Test fun `invalid BPB truncated devices and conflicting FAT copies never write`() = image { disk ->
        val valid = disk.file.readBytes()
        val g = Fat12Volume.mount(disk).geometry
        val damaged = valid.copyOf(); damaged[(g.fatSector * 512).toInt() + 3] = 15
        disk.file.writeBytes(damaged)
        assertFailsWith<IOException> { Fat12Volume.mount(disk) }
        disk.file.writeBytes(valid.copyOf().also { it[13] = 3 })
        assertFailsWith<IOException> { Fat12Volume.mount(disk) }
        disk.file.writeBytes(valid.copyOf(512))
        assertFailsWith<IOException> { Fat12Volume.mount(disk) }
        assertEquals(0, disk.writes)
    }

    @Test fun `external metadata change refuses writes and readback failure poisons session`() = image { disk ->
        val fs = Fat12Volume.mount(disk)
        val original = disk.file.readBytes()
        RandomAccessFile(disk.file, "rw").use { it.seek(fs.geometry.rootSector * 512); it.write(0xE5) }
        assertFailsWith<IOException> { fs.writeFile("A.PES", byteArrayOf(1)) }
        assertEquals(0, disk.writes)
        disk.file.writeBytes(original)
        val faulty = object : BlockDevice by disk {
            override fun writeSectors(lba: Long, data: ByteArray) { /* pretend a USB short write */ }
        }
        val bad = Fat12Volume.mount(faulty)
        assertFailsWith<IOException> { bad.writeFile("A.PES", byteArrayOf(7)) }
        assertFailsWith<IOException> { bad.writeFile("B.PES", byteArrayOf(8)) }
        assertContentEquals(original, disk.file.readBytes())
        clean(disk)
    }
    @Test fun `deleting a Windows LFN also removes only its checksum owned slots`() = image { disk ->
        val fs = Fat12Volume.mount(disk)
        val entry = fs.writeFile("longname.pes", byteArrayOf(1, 2, 3))
        val g = fs.geometry
        val root = disk.readSectors(g.rootSector, g.rootSectors)
        root.copyInto(root, 32, 0, 32)
        root.fill(0, 0, 32)
        root[0] = 0x41; root[11] = 0x0F
        var checksum = 0
        for (j in 32..42) checksum = (((checksum and 1) shl 7) + (checksum shr 1) + (root[j].toInt() and 255)) and 255
        root[13] = checksum.toByte()
        val name = "Long Name.pes"
        val offsets = listOf(1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30)
        offsets.forEachIndexed { j, offset ->
            val char = if (j < name.length) name[j].code else if (j == name.length) 0 else 0xFFFF
            root[offset] = char.toByte(); root[offset + 1] = (char ushr 8).toByte()
        }
        disk.writeSectors(g.rootSector, root)
        val fresh = Fat12Volume.mount(disk)
        assertEquals(listOf(0), fresh.listRoot().single().longNameSlots)
        assertContentEquals(byteArrayOf(1, 2, 3), fresh.readFile(entry.name))
        clean(disk)
        fresh.deleteFile(entry.name)
        assertEquals(0xE5, disk.readSectors(g.rootSector, 1)[0].toInt() and 255)
        assertTrue(Fat12Volume.mount(disk).listRoot().isEmpty())
        clean(disk)
    }

    @Test fun `failure on the second FAT copy stops the session and is never silently repaired`() = image { disk ->
        val g = Fat12Volume.mount(disk).geometry
        val failing = object : BlockDevice by disk {
            override fun writeSectors(lba: Long, data: ByteArray) {
                if (lba == g.fatSector + g.sectorsPerFat) throw IOException("USB detached during second FAT write")
                disk.writeSectors(lba, data)
            }
        }
        val fs = Fat12Volume.mount(failing)
        assertFailsWith<IOException> { fs.writeFile("A.PES", byteArrayOf(1)) }
        val writes = disk.writes
        assertFailsWith<IOException> { fs.writeFile("B.PES", byteArrayOf(2)) }
        assertEquals(writes, disk.writes)
        assertFailsWith<IOException> { Fat12Volume.mount(disk) }
    }

    @Test fun `nonstandard firmware jump and OEM mount with warning and boot bytes never change`() = image { disk ->
        val bytes = disk.file.readBytes().also { it[0] = 0; it.fill(0, 3, 11) }
        disk.file.writeBytes(bytes)
        val fs = Fat12Volume.mount(disk)
        assertTrue(fs.warnings.any { it.contains("boot jump") })
        val name = fs.writeFile("firmware.pes", byteArrayOf(1, 2, 3)).name
        assertContentEquals(byteArrayOf(1, 2, 3), Fat12Volume.mount(disk).readFile(name))
        assertContentEquals(bytes.copyOfRange(0, 512), disk.readSectors(0, 1))
    }

    @Test fun `nonstandard media and BPB FAT media mismatch are warnings without media repair`() = image { disk ->
        val bytes = disk.file.readBytes().also { it[21] = 0 }
        disk.file.writeBytes(bytes)
        val fs = Fat12Volume.mount(disk)
        assertTrue(fs.warnings.any { it.contains("Nonstandard BPB media") })
        assertTrue(fs.warnings.any { it.contains("differs from FAT[0]") })
        fs.writeFile("media.pes", byteArrayOf(5, 6))
        assertEquals(0, disk.readSectors(0, 1)[21].toInt())
        val g = fs.geometry
        assertContentEquals(bytes.copyOfRange(g.fatSector.toInt() * 512, g.fatSector.toInt() * 512 + 3),
            disk.readSectors(g.fatSector, 1).copyOfRange(0, 3))
        // Even a standard media byte may disagree with firmware's FAT media value.
        val standard = disk.file.readBytes().also { it[21] = 0xF0.toByte() }
        disk.file.writeBytes(standard)
        assertTrue(Fat12Volume.mount(disk).warnings.any { it.contains("differs from FAT[0]") })
    }

    @Test fun `BPB capacity above READ CAPACITY clamps and can fill only actual device sectors`() = image { disk ->
        val original = disk.file.readBytes()
        disk.file.writeBytes(original.copyOf(952 * 512)) // SE400-like 476 KiB device, 480 KiB BPB.
        val fs = Fat12Volume.mount(disk)
        assertEquals(952L, fs.geometry.totalSectors)
        assertEquals(952 * 512L, fs.capacityBytes)
        assertTrue(fs.warnings.any { it.contains("clamped") })
        val data = ByteArray(fs.freeBytes.toInt()) { (it * 11).toByte() }
        val name = fs.writeFile("full.pes", data).name
        assertContentEquals(data, Fat12Volume.mount(disk).readFile(name))
        assertEquals(0L, fs.freeBytes)
        assertTrue(951L in disk.reads)
        assertTrue(disk.reads.all { it in 0..951 })
        assertContentEquals(original.copyOfRange(0, 512), disk.readSectors(0, 1))
        val before = disk.file.readBytes()
        assertFailsWith<Fat12Volume.NoSpace> { fs.writeFile("extra.pes", byteArrayOf(1)) }
        assertContentEquals(before, disk.file.readBytes())
    }

    @Test fun `32 bit BPB capacity also clamps without changing boot sector`() = image { disk ->
        val bytes = disk.file.readBytes().also {
            it[19] = 0; it[20] = 0
            val total = 968
            repeat(4) { n -> it[32 + n] = (total ushr (n * 8)).toByte() }
        }
        disk.file.writeBytes(bytes)
        val fs = Fat12Volume.mount(disk)
        assertEquals(960L, fs.geometry.totalSectors)
        assertTrue(fs.warnings.any { it.contains("968 clamped") })
        fs.writeFile("small.pes", byteArrayOf(1))
        assertContentEquals(bytes.copyOfRange(0, 512), disk.readSectors(0, 1))
    }

    @Test fun `partition BPB is clamped to partition length and never writes the following disk area`() = image { partition ->
        val payload = partition.file.readBytes().also { it[19] = 0xC8.toByte(); it[20] = 3 } // 968 BPB sectors.
        val start = 63
        val mbr = ByteArray(start * 512).also {
            it[450] = 1; it[510] = 0x55; it[511] = 0xAA.toByte()
            repeat(4) { n -> it[454 + n] = (start ushr (n * 8)).toByte(); it[458 + n] = (960 ushr (n * 8)).toByte() }
        }
        val tail = ByteArray(128 * 512) { 0x7E }
        val whole = File(partition.file.parentFile, "bounded-mbr.img").apply { writeBytes(mbr + payload + tail) }
        val disk = Disk(whole)
        val fs = Fat12Volume.mount(disk)
        assertEquals(960L, fs.geometry.totalSectors)
        assertTrue(fs.warnings.any { it.contains("968 clamped") })
        val data = ByteArray(fs.freeBytes.toInt()) { 0x31 }
        val name = fs.writeFile("full.pes", data).name
        assertContentEquals(data, Fat12Volume.mount(disk).readFile(name))
        assertContentEquals(tail, disk.readSectors(start + 960L, 128))
        assertContentEquals(mbr, disk.readSectors(0, start))
        assertContentEquals(payload.copyOfRange(0, 512), disk.readSectors(start.toLong(), 1))
    }

    @Test fun `FAT boot code is never mistaken for MBR when plausible BPB fails bounds`() = image { disk ->
        val bytes = disk.file.readBytes().also {
            it[19] = 1; it[20] = 0 // Plausible BPB fields, invalid total below metadata.
            it[446] = 0; it[450] = 1
            repeat(4) { n -> it[454 + n] = (1 ushr (n * 8)).toByte(); it[458 + n] = (1 ushr (n * 8)).toByte() }
        }
        disk.file.writeBytes(bytes)
        val failure = assertFailsWith<Fat12Volume.MountFailure> { Fat12Volume.mount(disk) }
        assertTrue(failure.message!!.contains("Superfloppy failed:"))
        assertTrue(failure.superfloppyReason.contains("metadata"))
        assertTrue(failure.mbrReason.contains("Not an MBR"))
        assertEquals(listOf(0L), disk.reads)
        assertEquals(0, disk.writes)
    }

    @Test fun `invalid MBR flags and non FAT12 types are rejected before partition bounds`() = image { disk ->
        for ((flag, type) in listOf(0x7F to 1, 0 to 6, 0x80 to 0x0B)) {
            val bytes = ByteArray(512).also {
                it[446] = flag.toByte(); it[450] = type.toByte(); it[458] = 1
                it[510] = 0x55; it[511] = 0xAA.toByte()
            }
            disk.file.writeBytes(bytes)
            val failure = assertFailsWith<Fat12Volume.MountFailure> { Fat12Volume.mount(disk) }
            assertTrue(failure.mbrReason.contains("Not an MBR"))
            assertTrue(failure.superfloppyReason.contains("geometry"))
        }
        assertEquals(0, disk.writes)
    }

    @Test fun `clamping still rejects FAT16 declared clusters undersized FAT and missing signature`() = image { disk ->
        val original = disk.file.readBytes()
        for (changed in listOf(
            original.copyOf().also { it[19] = 0x88.toByte(); it[20] = 0x13 }, // 5000 sectors -> FAT16 cluster range.
            original.copyOf().also { it[22] = 1; it[23] = 0 }, // FAT is too short for declared cluster count.
            original.copyOf().also { it[510] = 0 },
        )) {
            disk.file.writeBytes(changed)
            assertFailsWith<IOException> { Fat12Volume.mount(disk) }
        }
        assertEquals(0, disk.writes)
    }

    @Test fun `diagnostics contain all 512 boot bytes and numeric BPB fields`() = image { disk ->
        val boot = disk.readSectors(0, 1).also { "OEM%TEST".toByteArray().copyInto(it, 3) }
        val report = Fat12Diagnostics.bootSector(boot)
        assertTrue(report.contains("BytesPerSector=512"))
        assertTrue(report.contains("TotalSectors16=960"))
        assertTrue(report.contains("NumberOfFATs=2"))
        assertTrue(report.contains("OEM=OEM%TEST"))
        assertTrue(report.contains("01F0:"))
        val rows = report.lines().filter { it.matches(Regex("[0-9A-F]{4}: .*")) }
        assertEquals(32, rows.size)
        val restored = rows.flatMap { it.substringAfter(": ").split(' ') }.map { it.toInt(16).toByte() }.toByteArray()
        assertContentEquals(boot, restored)
        assertTrue(Fat12Diagnostics.bootSector(byteArrayOf(1)).contains("sector0 bytes=1"))
    }

}
