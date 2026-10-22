// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.fat12

/** LBA units are sectors, never bytes. Implementations must transfer entire buffers or throw.
 * No formatting, partition creation or OS/USB dependency belongs to this interface.
 */
interface BlockDevice {
    val sectorSize: Int
    val sectorCount: Long
    fun readSectors(lba: Long, count: Int): ByteArray
    fun writeSectors(lba: Long, data: ByteArray)
    fun flush() {}
}
