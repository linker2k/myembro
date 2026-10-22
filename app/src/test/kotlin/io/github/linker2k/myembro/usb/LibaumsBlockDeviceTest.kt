// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.usb

import io.github.linker2k.myembro.engine.fat12.Fat12Volume
import me.jahnen.libaums.core.driver.BlockDeviceDriver
import java.nio.ByteBuffer
import java.nio.file.Files
import kotlin.test.*

class LibaumsBlockDeviceTest {
    private class ScsiFake(val data: ByteArray) : BlockDeviceDriver {
        override val blockSize = 512
        override val blocks get() = data.size / blockSize - 1L // pinned ScsiBlockDevice reports LAST LBA
        val offsets = mutableListOf<Long>()
        var writes = 0
        override fun init() {}
        override fun read(deviceOffset: Long, buffer: ByteBuffer) {
            offsets += deviceOffset
            buffer.put(data, (deviceOffset * blockSize).toInt(), buffer.remaining())
        }
        override fun write(deviceOffset: Long, buffer: ByteBuffer) {
            writes++; offsets += deviceOffset
            buffer.get(data, (deviceOffset * blockSize).toInt(), buffer.remaining())
        }
    }
    @Test fun `last SCSI sector is included and offsets stay in sector units`() {
        val scsi = ScsiFake(ByteArray(480 * 1024))
        val block = LibaumsBlockDevice(scsi)
        assertEquals(960L, block.sectorCount)
        val last = ByteArray(512) { 71 }
        block.writeSectors(959, last)
        assertContentEquals(last, block.readSectors(959, 1))
        assertEquals(listOf(959L, 959L), scsi.offsets)
        assertFails { block.readSectors(960, 1) }
        assertFails { block.writeSectors(0, byteArrayOf(1)) }
    }
    @Test fun `large transfers are chunked and partial driver reads are errors`() {
        val scsi = ScsiFake(ByteArray(480 * 1024))
        val block = LibaumsBlockDevice(scsi)
        val bytes = ByteArray(65 * 512) { (it * 17).toByte() }
        block.writeSectors(3, bytes)
        assertEquals(listOf(3L, 35L, 67L), scsi.offsets)
        assertContentEquals(bytes, block.readSectors(3, 65))
        val short = object : BlockDeviceDriver by scsi {
            override fun read(deviceOffset: Long, buffer: ByteBuffer) { buffer.put(0) }
        }
        assertFailsWith<java.io.IOException> { LibaumsBlockDevice(short).readSectors(0, 1) }
    }
    @Test fun `mkfs volume works through SCSI adapter without libaums filesystem factory`() {
        val dir = Files.createTempDirectory("myembro-scsi-").toFile()
        try {
            val file = dir.resolve("volume.img")
            val process = ProcessBuilder("/usr/sbin/mkfs.fat", "-F", "12", "-C", "-s", "1", "-f", "2", file.path, "480").redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor(), output)
            val scsi = ScsiFake(file.readBytes())
            val block = LibaumsBlockDevice(scsi)
            val fs = Fat12Volume.mount(block)
            val data = ByteArray(3911) { it.toByte() }
            val name = fs.writeFile("flower.pes", data).name
            assertEquals("FLOWER.PES", name)
            assertContentEquals(data, Fat12Volume.mount(block).readFile(name))
            file.writeBytes(scsi.data)
            val check = ProcessBuilder("/usr/sbin/fsck.fat", "-n", file.path).redirectErrorStream(true).start()
            val report = check.inputStream.bufferedReader().readText()
            assertEquals(0, check.waitFor(), report)
        } finally { dir.deleteRecursively() }
    }
}
