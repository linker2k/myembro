// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.fat12

/** Read-only interpretation for field reports; unknown/nonstandard OEM and label bytes are retained. */
object Fat12Diagnostics {
    fun bootSector(bytes: ByteArray): String = buildString {
        appendLine("sector0 bytes=${bytes.size}")
        if (bytes.size >= 512) {
            fun u8(offset: Int) = bytes[offset].toInt() and 255
            fun u16(offset: Int) = u8(offset) or (u8(offset + 1) shl 8)
            fun u32(offset: Int) = u16(offset).toLong() or (u16(offset + 2).toLong() shl 16)
            fun text(offset: Int, length: Int) = bytes.copyOfRange(offset, offset + length).joinToString("") {
                val n = it.toInt() and 255
                if (n in 32..126) n.toChar().toString() else "\\x%02X".format(n)
            }
            appendLine("BPB (sector0 interpreted as boot sector):")
            appendLine("Jump=${hex(bytes.copyOfRange(0, 3))}; OEM=${text(3, 8)}; signature=" + "0x%04X".format(u16(510)))
            appendLine("BytesPerSector=${u16(11)}; SectorsPerCluster=${u8(13)}; ReservedSectors=${u16(14)}")
            appendLine("NumberOfFATs=${u8(16)}; RootEntryCount=${u16(17)}; TotalSectors16=${u16(19)}")
            appendLine("Media=0x%02X; SectorsPerFAT16=${u16(22)}".format(u8(21)))
            appendLine("SectorsPerTrack=${u16(24)}; NumberOfHeads=${u16(26)}; HiddenSectors=${u32(28)}; TotalSectors32=${u32(32)}")
            appendLine("DriveNumber=0x%02X; ExtendedBootSignature=0x%02X".format(u8(36), u8(38)) +
                "; Label=${text(43, 11)}; FsType=${text(54, 8)}")
            val total = u16(19).toLong().takeIf { it != 0L } ?: u32(32)
            if (u16(11) > 0 && u8(13) > 0) {
                val rootSectors = (u16(17) * 32L + u16(11) - 1) / u16(11)
                val metadata = u16(14) + u8(16).toLong() * u16(22) + rootSectors
                appendLine("DeclaredTotal=$total; RootSectors=$rootSectors; MetadataSectors=$metadata; DeclaredClusters=${(total - metadata) / u8(13)}")
            }
            appendLine("MBR entry candidates (may be FAT boot code):")
            repeat(4) { n ->
                val o = 446 + n * 16
                appendLine("$n: boot=0x%02X type=0x%02X start=${u32(o + 8)} sectors=${u32(o + 12)}".format(u8(o), u8(o + 4)))
            }
        }
        appendLine("sector0 first 512 bytes (hex):")
        bytes.take(512).chunked(16).forEachIndexed { row, values ->
            appendLine("%04X: ".format(row * 16) + hex(values.toByteArray()))
        }
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02X".format(it.toInt() and 255) }
}
