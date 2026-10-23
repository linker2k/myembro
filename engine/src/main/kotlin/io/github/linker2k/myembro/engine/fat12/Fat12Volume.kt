// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.fat12

import java.io.IOException
import java.security.MessageDigest
import java.time.LocalDateTime
import kotlin.math.min

/** Independent implementation of the Microsoft FAT on-disk format (BPB, FAT12 and 32-byte entries).
 * Format reference: Microsoft FAT Specification, sections 3, 4 and 6:
 * https://www.scs.stanford.edu/~zyedidia/docs/_other/fat.pdf (Microsoft-authored specification).
 * Root files only; no format/repair API. Writes are verified by physical reads, not cached copies.
 * Not journaled: disconnect/power loss can interrupt metadata updates. After any I/O failure this
 * instance is poisoned; remount and inspect rather than retrying writes against stale allocation.
 */
class Fat12Volume private constructor(private val device: BlockDevice, val geometry: Geometry,
    private val boot: ByteArray, private val mountWarnings: MutableList<String>) {
    /** Firmware-generated RAM disks can have nonstandard descriptive BPB fields. No repair is done. */
    val warnings: List<String> get() = mountWarnings.toList()
    data class Geometry(val startSector: Long, val totalSectors: Long, val bytesPerSector: Int,
        val sectorsPerCluster: Int, val reservedSectors: Int, val fatCount: Int, val sectorsPerFat: Int,
        val rootEntryCount: Int, val rootSectors: Int, val clusterCount: Int) {
        val fatSector: Long get() = startSector + reservedSectors
        val rootSector: Long get() = fatSector + fatCount.toLong() * sectorsPerFat
        val dataSector: Long get() = rootSector + rootSectors
        val clusterBytes: Int get() = bytesPerSector * sectorsPerCluster
        // 0xFF0..0xFF7 are reserved/bad values, never allocate them as data clusters.
        val lastCluster: Int get() = min(clusterCount + 1, 0xFEF)
    }

    data class Entry(val name: String, val size: Long, val attributes: Int, val firstCluster: Int,
        val rootIndex: Int, val longNameSlots: List<Int> = emptyList()) {
        val isDirectory: Boolean get() = attributes and 0x10 != 0
        val isReadOnly: Boolean get() = attributes and 1 != 0
    }
    class NoSpace(val requiredBytes: Long, val freeBytes: Long, val requiredEntries: Int, val freeEntries: Int) :
        IOException("FAT12 space/root entries insufficient: $requiredBytes/$freeBytes bytes, $requiredEntries/$freeEntries entries")
    class MountFailure(val superfloppyReason: String, val mbrReason: String, cause: Exception) :
        IOException("Superfloppy failed: $superfloppyReason; MBR failed: $mbrReason", cause)

    private var fat = read(geometry.fatSector, geometry.sectorsPerFat)
    private var root = read(geometry.rootSector, geometry.rootSectors)
    private var poisoned = false
    val capacityBytes: Long get() = geometry.totalSectors * geometry.bytesPerSector
    val label: String get() {
        for (i in 0 until geometry.rootEntryCount) {
            val offset = i * 32
            if (u8(root, offset) == 0) break
            if (u8(root, offset) != 0xE5 && u8(root, offset + 11) == 8)
                return String(root, offset, 11, Charsets.US_ASCII).trim()
        }
        return String(boot, 43, 11, Charsets.US_ASCII).trim()
    }

    init {
        for (copy in 1 until geometry.fatCount) {
            checkDisk(fat.contentEquals(read(geometry.fatSector + copy.toLong() * geometry.sectorsPerFat, geometry.sectorsPerFat)), "FAT copies disagree; repair is not supported")
        }
        checkDisk(fatEntry(0) and 0xF00 == 0xF00 && fatEntry(1) >= 0xFF8, "Invalid FAT reserved entries")
        if (fatEntry(0) and 255 != u8(boot, 21)) mountWarnings +=
            "BPB media byte differs from FAT[0]: BPB=0x%02X FAT=0x%02X (unchanged)".format(u8(boot, 21), fatEntry(0) and 255)
        validateChains()
    }

    @Synchronized fun listRoot(): List<Entry> {
        checkUsable()
        val entries = mutableListOf<Entry>()
        var lfn = mutableListOf<Int>()
        for (i in 0 until geometry.rootEntryCount) {
            val o = i * 32
            if (u8(root, o) == 0) break
            if (u8(root, o) == 0xE5) { lfn.clear(); continue }
            val attr = u8(root, o + 11)
            if (attr == 0x0F) { lfn += i; continue }
            if (attr and 8 == 0) {
                val base = String(root, o, 8, Charsets.ISO_8859_1).trimEnd()
                val extension = String(root, o + 8, 3, Charsets.ISO_8859_1).trimEnd()
                val name = base + if (extension.isEmpty()) "" else ".$extension"
                val slots = verifiedLongNames(lfn, o)
                entries += Entry(name, u32(root, o + 28), attr, u16(root, o + 26), i, slots)
                checkDisk(u16(root, o + 20) == 0, "Nonzero FAT12 high cluster word")
            }
            lfn = mutableListOf()
        }
        return entries
    }

    val freeBytes: Long @Synchronized get() { checkUsable(); return freeClusters().size.toLong() * geometry.clusterBytes }
    val freeRootEntries: Int @Synchronized get() {
        checkUsable()
        var ended = false
        return (0 until geometry.rootEntryCount).count {
            val first = u8(root, it * 32)
            if (first == 0) ended = true
            ended || first == 0xE5
        }
    }

    /** Useful for revalidating a deletion confirmation after closing/reopening the USB device. */
    @Synchronized fun fingerprint(): String {
        checkUsable()
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(boot); digest.update(fat); digest.update(root)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Synchronized fun checkSpace(sizes: List<Int>) {
        checkUsable()
        require(sizes.all { it >= 0 })
        val required = sizes.sumOf { clustersFor(it.toLong()).toLong() * geometry.clusterBytes }
        val free = freeBytes
        if (required > free || sizes.size > freeRootEntries) throw NoSpace(required, free, sizes.size, freeRootEntries)
    }

    @Synchronized fun readFile(name: String): ByteArray {
        val entry = find(name)
        checkDisk(!entry.isDirectory && entry.size <= Int.MAX_VALUE, "Not a readable root file")
        val chain = chain(entry.firstCluster)
        checkDisk(chain.size == clustersFor(entry.size), "File size/chain mismatch")
        val bytes = ByteArray(entry.size.toInt())
        var offset = 0
        for (cluster in chain) {
            val data = read(clusterSector(cluster), geometry.sectorsPerCluster)
            val count = min(data.size, bytes.size - offset)
            data.copyInto(bytes, offset, 0, count); offset += count
        }
        return bytes
    }

    /** New file only; an existing name is never replaced. Returns the actual unique 8.3 filename. */
    @Synchronized fun writeFile(originalName: String, bytes: ByteArray): Entry {
        unchanged()
        checkSpace(listOf(bytes.size))
        val name = ShortNames.unique(originalName, listRoot().map { it.name })
        val slot = (0 until geometry.rootEntryCount).first { u8(root, it * 32) in setOf(0, 0xE5) }
        val clusters = freeClusters().take(clustersFor(bytes.size.toLong()))
        val nextFat = fat.copyOf()
        clusters.forEachIndexed { i, cluster -> setFatEntry(nextFat, cluster, clusters.getOrNull(i + 1) ?: 0xFFF) }
        val nextRoot = root.copyOf()
        val o = slot * 32
        nextRoot.fill(0, o, o + 32)
        ShortNames.encode(name).copyInto(nextRoot, o)
        nextRoot[o + 11] = 0x20 // archive, no LFN
        val now = LocalDateTime.now()
        val date = ((now.year.coerceIn(1980, 2107) - 1980) shl 9) or (now.monthValue shl 5) or now.dayOfMonth
        val time = (now.hour shl 11) or (now.minute shl 5) or (now.second / 2)
        put16(nextRoot, o + 14, time); put16(nextRoot, o + 16, date); put16(nextRoot, o + 18, date)
        put16(nextRoot, o + 22, time); put16(nextRoot, o + 24, date)
        put16(nextRoot, o + 26, clusters.firstOrNull() ?: 0); put32(nextRoot, o + 28, bytes.size.toLong())
        if (u8(root, o) == 0 && slot + 1 < geometry.rootEntryCount) nextRoot[o + 32] = 0
        mutation {
            clusters.forEachIndexed { i, cluster ->
                val padded = ByteArray(geometry.clusterBytes)
                val from = i * geometry.clusterBytes
                bytes.copyInto(padded, 0, from, min(from + padded.size, bytes.size))
                writeVerified(clusterSector(cluster), padded)
            }
            writeFats(nextFat)
            writeVerified(geometry.rootSector, nextRoot)
            fat = nextFat; root = nextRoot
            checkDisk(readFile(name).contentEquals(bytes), "FAT12 file read-back differs: $name")
        }
        return find(name)
    }

    /** Deletes only the explicitly named root file; never directories or a whole volume. */
    @Synchronized fun deleteFile(name: String) {
        unchanged()
        val entry = find(name)
        checkDisk(!entry.isDirectory && !entry.isReadOnly, "Cannot delete directory/read-only file")
        val clusters = chain(entry.firstCluster)
        val nextRoot = root.copyOf()
        (entry.longNameSlots + entry.rootIndex).forEach { nextRoot[it * 32] = 0xE5.toByte() }
        val nextFat = fat.copyOf()
        clusters.forEach { setFatEntry(nextFat, it, 0) }
        mutation {
            // Remove the reference before freeing its chain, never the reverse.
            writeVerified(geometry.rootSector, nextRoot)
            writeFats(nextFat)
            fat = nextFat; root = nextRoot
        }
    }

    private fun find(name: String): Entry = listRoot().firstOrNull { it.name.equals(name, true) }
        ?: throw IOException("FAT12 root file not found: $name")
    private fun freeClusters(): List<Int> = (2..geometry.lastCluster).filter { fatEntry(it) == 0 }
    private fun clustersFor(size: Long): Int = ((size + geometry.clusterBytes - 1) / geometry.clusterBytes).toInt()
    private fun clusterSector(cluster: Int): Long = geometry.dataSector + (cluster - 2L) * geometry.sectorsPerCluster
    private fun checkUsable() { checkDisk(!poisoned, "FAT12 session failed; reconnect and remount before writing") }

    private fun chain(first: Int): List<Int> {
        if (first == 0) return emptyList()
        val seen = LinkedHashSet<Int>()
        var current = first
        while (true) {
            checkDisk(current in 2..geometry.lastCluster && seen.add(current), "Invalid/cyclic FAT12 cluster chain")
            val next = fatEntry(current)
            if (next >= 0xFF8) return seen.toList()
            checkDisk(next in 2..geometry.lastCluster, "Free/reserved/bad cluster in FAT12 file chain")
            current = next
        }
    }

    private fun validateChains() {
        val claimed = HashSet<Int>()
        for (entry in listRoot()) {
            val clusters = chain(entry.firstCluster)
            if (!entry.isDirectory) checkDisk(clusters.size == clustersFor(entry.size), "FAT12 file size/chain mismatch")
            checkDisk(clusters.all { claimed.add(it) }, "Cross-linked FAT12 root files")
        }
    }

    private fun verifiedLongNames(slots: List<Int>, shortOffset: Int): List<Int> {
        if (slots.isEmpty()) return emptyList()
        var checksum = 0
        for (i in 0..10) checksum = (((checksum and 1) shl 7) + (checksum shr 1) + u8(root, shortOffset + i)) and 255
        val count = slots.size
        checkDisk(count <= 20 && slots.withIndex().all { (index, slot) ->
            val o = slot * 32
            u8(root, o) == (count - index or if (index == 0) 0x40 else 0) && u8(root, o + 13) == checksum &&
                u8(root, o + 12) == 0 && u16(root, o + 26) == 0
        }, "Invalid LFN ownership; refusing to modify this directory")
        return slots.toList()
    }

    private fun fatEntry(cluster: Int): Int {
        val offset = cluster + cluster / 2
        val word = u16(fat, offset)
        return if (cluster and 1 == 0) word and 0xFFF else word ushr 4
    }
    private fun setFatEntry(table: ByteArray, cluster: Int, value: Int) {
        val o = cluster + cluster / 2
        val word = u16(table, o)
        put16(table, o, if (cluster and 1 == 0) (word and 0xF000) or value else (word and 0xF) or (value shl 4))
    }

    private fun unchanged() {
        checkUsable()
        checkDisk(boot.contentEquals(read(geometry.startSector, 1)) && root.contentEquals(read(geometry.rootSector, geometry.rootSectors)), "FAT12 volume changed; reopen before writing")
        for (copy in 0 until geometry.fatCount) checkDisk(fat.contentEquals(read(geometry.fatSector + copy.toLong() * geometry.sectorsPerFat, geometry.sectorsPerFat)), "FAT12 allocation changed; reopen before writing")
    }
    private fun writeFats(next: ByteArray) {
        for (copy in 0 until geometry.fatCount) writeVerified(geometry.fatSector + copy.toLong() * geometry.sectorsPerFat, next)
    }
    private inline fun mutation(action: () -> Unit) {
        try { action(); device.flush() } catch (e: Exception) { poisoned = true; throw e }
    }
    private fun writeVerified(lba: Long, bytes: ByteArray) {
        checkDisk(bytes.isNotEmpty() && bytes.size % device.sectorSize == 0 && lba >= geometry.startSector &&
            bytes.size.toLong() / device.sectorSize <= geometry.startSector + geometry.totalSectors - lba &&
            bytes.size.toLong() / device.sectorSize <= device.sectorCount - lba, "Block write out of bounds")
        device.writeSectors(lba, bytes)
        device.flush()
        checkDisk(read(lba, bytes.size / device.sectorSize).contentEquals(bytes), "FAT12 sector read-back differs at $lba")
    }
    private fun read(lba: Long, count: Int): ByteArray = checkedRead(device, lba, count)

    companion object {
        fun mount(device: BlockDevice): Fat12Volume {
            checkDisk(device.sectorSize in setOf(512, 1024, 2048, 4096) && device.sectorCount > 0, "Unsupported block geometry")
            val zero = checkedRead(device, 0, 1)
            val superFailure = try {
                val parsed = parse(zero, device, 0, device.sectorCount)
                return Fat12Volume(device, parsed.geometry, zero, parsed.warnings)
            } catch (e: IOException) { e }
            try {
                checkDisk(!(u8(zero, 0) in setOf(0xEB, 0xE9) && plausibleBpb(zero, device)),
                    "Not an MBR: FAT boot jump and plausible BPB; partition-table bytes are boot code")
                checkDisk(u16(zero, 510) == 0xAA55, "Not an MBR: missing 0xAA55 signature")
                val entry = (0..3).map { 446 + it * 16 }
                    .firstOrNull { u8(zero, it + 4) != 0 || u32(zero, it + 12) != 0L }
                    ?: throw IOException("Not an MBR: no partition entries")
                checkDisk(u8(zero, entry) in setOf(0, 0x80) && u8(zero, entry + 4) == 1,
                    "Not an MBR: first partition needs boot flag 0/0x80 and FAT12 type 0x01")
                val start = u32(zero, entry + 8); val count = u32(zero, entry + 12)
                checkDisk(start > 0 && start < device.sectorCount && count > 0 && count <= device.sectorCount - start,
                    "MBR partition out of device bounds: start=$start count=$count device=${device.sectorCount}")
                val boot = checkedRead(device, start, 1)
                val parsed = parse(boot, device, start, count)
                return Fat12Volume(device, parsed.geometry, boot, parsed.warnings)
            } catch (e: IOException) {
                throw MountFailure(superFailure.message ?: superFailure.javaClass.simpleName,
                    e.message ?: e.javaClass.simpleName, e).apply { addSuppressed(superFailure) }
            }
        }

        private data class Parsed(val geometry: Geometry, val warnings: MutableList<String>)

        private fun plausibleBpb(b: ByteArray, device: BlockDevice): Boolean {
            val spc = u8(b, 13)
            return u16(b, 11) == device.sectorSize && spc in 1..128 && spc and (spc - 1) == 0 &&
                u16(b, 14) > 0 && u8(b, 16) in 1..2 && u16(b, 17) > 0 && u16(b, 22) > 0
        }

        private fun parse(b: ByteArray, device: BlockDevice, start: Long, available: Long): Parsed {
            checkDisk(u16(b, 510) == 0xAA55, "Invalid FAT boot sector signature (expected 0xAA55)")
            val warnings = mutableListOf<String>()
            if (u8(b, 0) !in setOf(0xEB, 0xE9)) warnings += "Nonstandard FAT boot jump: 0x%02X (unchanged)".format(u8(b, 0))
            if (u8(b, 21) != 0xF0 && u8(b, 21) !in 0xF8..0xFF)
                warnings += "Nonstandard BPB media byte: 0x%02X (unchanged)".format(u8(b, 21))
            val size = u16(b, 11); val spc = u8(b, 13); val reserved = u16(b, 14); val copies = u8(b, 16)
            val roots = u16(b, 17); val total = u16(b, 19).toLong().takeIf { it != 0L } ?: u32(b, 32)
            val spf = u16(b, 22)
            checkDisk(size == device.sectorSize && spc in 1..128 && spc and (spc - 1) == 0 && reserved > 0 && copies in 1..2 && roots > 0 && spf > 0, "Invalid FAT12 BPB geometry")
            val rootSectors = (roots * 32 + size - 1) / size
            val metadata = reserved.toLong() + copies.toLong() * spf + rootSectors
            checkDisk(total > metadata, "FAT12 BPB total=$total does not cover metadata=$metadata")
            // Validate the declared layout too: clamping must never disguise a FAT16/32 volume.
            val declaredClusters = (total - metadata) / spc
            checkDisk(declaredClusters in 1..4084 && spf.toLong() * size >= ((declaredClusters + 2) * 3 + 1) / 2 &&
                spf.toLong() * size <= 1_048_576, "Volume is not a valid FAT12 layout (clusters=$declaredClusters FAT bytes=${spf.toLong() * size})")
            val boundedTotal = minOf(total, available, device.sectorCount - start)
            if (boundedTotal != total) warnings += "BPB total sectors=$total clamped to device/partition sectors=$boundedTotal (boot sector unchanged)"
            checkDisk(boundedTotal > metadata, "FAT12 device ends inside metadata: available=$boundedTotal metadata=$metadata")
            val clusters = ((boundedTotal - metadata) / spc).toInt()
            checkDisk(clusters in 1..4084, "No complete FAT12 data clusters inside device bounds")
            return Parsed(Geometry(start, boundedTotal, size, spc, reserved, copies, spf, roots, rootSectors, clusters), warnings)
        }
        private fun checkedRead(d: BlockDevice, lba: Long, count: Int): ByteArray {
            checkDisk(count > 0 && lba >= 0 && count.toLong() <= d.sectorCount - lba, "Block read out of bounds")
            val data = d.readSectors(lba, count)
            checkDisk(data.size.toLong() == count.toLong() * d.sectorSize, "Short block read")
            return data
        }
        private fun checkDisk(test: Boolean, message: String) { if (!test) throw IOException(message) }
        private fun u8(b: ByteArray, o: Int) = b[o].toInt() and 255
        private fun u16(b: ByteArray, o: Int) = u8(b, o) or (u8(b, o + 1) shl 8)
        private fun u32(b: ByteArray, o: Int) = u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16)
        private fun put16(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v ushr 8).toByte() }
        private fun put32(b: ByteArray, o: Int, v: Long) { put16(b, o, v.toInt()); put16(b, o + 2, (v ushr 16).toInt()) }
    }
}
