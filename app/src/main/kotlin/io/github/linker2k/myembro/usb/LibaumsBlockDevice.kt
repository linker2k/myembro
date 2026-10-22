// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.usb

import io.github.linker2k.myembro.engine.fat12.BlockDevice
import me.jahnen.libaums.core.driver.BlockDeviceDriver
import java.io.IOException
import java.nio.ByteBuffer

/** Adapter for the pinned libaums 0.10.0 ScsiBlockDevice (not its filesystem layer).
 * That version's blocks getter returns READ CAPACITY's last LBA, despite its count documentation:
 * https://repo.maven.apache.org/maven2/me/jahnen/libaums/core/0.10.0/core-0.10.0-sources.jar
 * Add one, including the final sector, and keep all offsets in LBA units.
 */
internal class LibaumsBlockDevice(private val driver: BlockDeviceDriver) : BlockDevice {
    override val sectorSize = driver.blockSize
    override val sectorCount = (driver.blocks and 0xFFFF_FFFFL) + 1
    init { require(sectorSize in setOf(512, 1024, 2048, 4096)) }
    private fun validate(lba: Long, count: Int) {
        require(lba >= 0 && count > 0 && count.toLong() <= sectorCount - lba)
    }
    override fun readSectors(lba: Long, count: Int): ByteArray {
        validate(lba, count)
        val bytes = ByteArray(Math.multiplyExact(count, sectorSize))
        transfer(lba, bytes, write = false)
        return bytes
    }
    override fun writeSectors(lba: Long, data: ByteArray) {
        require(data.size % sectorSize == 0)
        validate(lba, data.size / sectorSize)
        transfer(lba, data, write = true)
    }
    private fun transfer(lba: Long, bytes: ByteArray, write: Boolean) {
        // Small transfers also work with pre-P USB bulkTransfer's 16KiB cap.
        val chunkBytes = (16 * 1024 / sectorSize) * sectorSize
        var offset = 0
        while (offset < bytes.size) {
            val count = minOf(chunkBytes, bytes.size - offset)
            val buffer = if (write) ByteBuffer.wrap(bytes.copyOfRange(offset, offset + count)) else ByteBuffer.allocate(count)
            if (write) driver.write(lba + offset / sectorSize, buffer) else driver.read(lba + offset / sectorSize, buffer)
            if (buffer.hasRemaining()) throw IOException("Short USB block transfer")
            if (!write) buffer.array().copyInto(bytes, offset)
            offset += count
        }
    }
}
