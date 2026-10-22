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
        override fun readSectors(lba: Long, count: Int): ByteArray = RandomAccessFile(file, "r").use {
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

}
