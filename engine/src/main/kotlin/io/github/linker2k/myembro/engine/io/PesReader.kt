// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.io

import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.engine.model.PatternBuilder

/**
 * Brother PES/PEC reader, ported from pyembroidery (MIT) PesReader.py / PecReader.py.
 *
 * A PES file is a header (versions 1–10, carrying metadata and, from v5, the real thread
 * list) followed by a PEC block holding the actual stitches. We read only what the PES header
 * lets us read safely, then always decode stitches from the PEC block.
 *
 * Header strings are MFC CArchive strings: newer PE-Design writes UTF-16 ones (`FF FE FF` + length),
 * which pyembroidery reads as a 255-byte string, landing the thread list on unrelated bytes. A
 * thread list is only used when every record is plausible and the object table follows it; when
 * the fixed layout does not lead to one, the header is searched for it (see docs/colors.md).
 */
object PesReader {
    private const val JUMP_CODE = 0x10
    private const val TRIM_CODE = 0x20
    private const val FLAG_LONG = 0x80

    fun read(data: ByteArray): EmbPattern {
        val f = ByteReader(data)
        val out = PatternBuilder()
        val magic = f.string(8)
        if (magic == "#PEC0001") {
            readPec(f, out, null)
        } else {
            if (!magic.startsWith("#PES")) throw EmbFormatException("Not a PES file (magic '$magic')")
            val pecBlockPosition = f.s32le() ?: throw EmbFormatException("Truncated PES header")
            if (pecBlockPosition !in 12 until data.size) {
                throw EmbFormatException("PEC block offset $pecBlockPosition is outside the file")
            }
            val version = magic.substring(4)
            out.metadata["version"] = version
            // The optional header may not run into the PEC block, and is applied only if it
            // parses completely: a malformed header must not stop us from reading the stitches.
            val header = ByteReader(data, limit = pecBlockPosition).apply { seek(12) }
            val headerMeta = LinkedHashMap<String, String>()
            var chart: HeaderChart? = null
            try {
                chart = readHeader(version, header, headerMeta)
                out.metadata += headerMeta
            } catch (e: TruncatedException) {
                out.warnings += "PES header is incomplete; using PEC colors"
            }
            if (chart == null && (version.toIntOrNull() ?: 0) >= 50) {
                chart = searchThreadList(data, pecBlockPosition)?.let { HeaderChart(it, verified = false) }
                if (chart == null) out.warnings += "PES thread list not found; using PEC colors"
            }
            chart = chart?.let { HeaderChart(it.threads, it.verified, segmentColors(data, pecBlockPosition)) }
            f.seek(pecBlockPosition)
            readPec(f, out, chart)
        }
        out.interpolateDuplicateColorAsStop()
        return out.build()
    }

    private class TruncatedException : Exception()

    /**
     * Threads from the PES header; [verified] when found at the version's documented position.
     * [blocks] is the chart index of each color block, from the objects' sewing segments.
     */
    private class HeaderChart(val threads: List<EmbThread>, val verified: Boolean, val blocks: List<Int>? = null)

    private fun <T> need(v: T?): T = v ?: throw TruncatedException()

    /** Returns the thread list at the documented position, or null to let the caller search for it. */
    private fun readHeader(version: String, f: ByteReader, out: MutableMap<String, String>): HeaderChart? {
        val threads = when (version) {
            "0100" -> headerV9V10(f, out, afterHoop = 38)
            "0090" -> headerV9V10(f, out, afterHoop = 30)
            "0080" -> headerV5toV8(f, out, beforeImage = 38, afterImage = 26)
            "0070", "0060" -> headerV5toV8(f, out, beforeImage = 36, afterImage = 24)
            "0050", "0055", "0056" -> headerV5toV8(f, out, beforeImage = 24, afterImage = 24)
            "0040" -> { f.skip(4); readMetadata(f, out); return null }
            else -> {
                // Newer layouts (e.g. 0091, 0110) start the same way; the caller searches for the chart.
                if ((version.toIntOrNull() ?: 0) < 50) return null
                f.skip(4); readMetadata(f, out); null
            }
        }
        return threads?.let { HeaderChart(it, verified = true) }
    }

    /**
     * MFC CArchive string: a byte length; 0xFF means a u16 length follows, whose value 0xFFFE
     * marks UTF-16 text (then the length starts over) and 0xFFFF a u32 length. Null when empty.
     */
    private fun pesString(f: ByteReader): String? {
        var unicode = false
        var length = need(f.u8()).toLong()
        if (length == 0xFFL) {
            length = need(f.u16le()).toLong()
            if (length == 0xFFFEL) {
                unicode = true
                length = need(f.u8()).toLong()
                if (length == 0xFFL) length = need(f.u16le()).toLong()
            }
            if (length == 0xFFFFL) length = need(f.s32le()).toLong() and 0xFFFFFFFFL
        }
        if (length == 0L) return null
        val bytes = length * (if (unicode) 2 else 1)
        if (f.remaining < bytes) throw TruncatedException()
        val raw = f.bytes(bytes.toInt())
        return String(raw, if (unicode) Charsets.UTF_16LE else Charsets.UTF_8)
    }

    private fun putIfPresent(out: MutableMap<String, String>, key: String, value: String?) {
        if (!value.isNullOrEmpty()) out[key] = value
    }

    private fun readMetadata(f: ByteReader, out: MutableMap<String, String>) {
        for (key in listOf("name", "category", "author", "keywords", "comments")) {
            putIfPresent(out, key, pesString(f))
        }
    }

    private fun headerV5toV8(f: ByteReader, out: MutableMap<String, String>, beforeImage: Int, afterImage: Int): List<EmbThread>? {
        f.skip(4)
        readMetadata(f, out)
        f.skip(beforeImage)
        putIfPresent(out, "image_file", pesString(f))
        f.skip(afterImage)
        return objectCountsThenThreads(f)
    }

    private fun headerV9V10(f: ByteReader, out: MutableMap<String, String>, afterHoop: Int): List<EmbThread>? {
        f.skip(4)
        readMetadata(f, out)
        f.skip(14)
        putIfPresent(out, "hoop_name", pesString(f))
        f.skip(afterHoop)
        putIfPresent(out, "image_file", pesString(f))
        f.skip(34)
        return objectCountsThenThreads(f)
    }

    /**
     * Programmable fill, motif and feather definitions come before the threads. We do not parse
     * them: with any of them present, null sends the caller to search for the thread list.
     */
    private fun objectCountsThenThreads(f: ByteReader): List<EmbThread>? {
        repeat(3) { if (need(f.u16le()) != 0) return null }
        return threadListAt(f)
    }

    /** The longest plausible thread list followed by the object table, before the first object. */
    private fun searchThreadList(data: ByteArray, pecBlockPosition: Int): List<EmbThread>? {
        val end = minOf(pecBlockPosition, SEARCH_LIMIT, firstObjectTag(data, pecBlockPosition))
        var best: List<EmbThread>? = null
        for (start in 12 until end) {
            val f = ByteReader(data, limit = pecBlockPosition).apply { seek(start) }
            val threads = threadListAt(f) ?: continue
            if (best == null || threads.size > best.size) best = threads
        }
        return best
    }

    private const val SEARCH_LIMIT = 64 * 1024
    private val EMB_ONE = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0, 0, 7, 0) + "CEmbOne".toByteArray()
    private val SEW_SEG = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0, 0, 7, 0) + "CSewSeg".toByteArray()
    private const val EMB_ONE_BODY = 58
    private const val EMB_ONE_REF = 0x8001
    private const val SEW_SEG_REF = 0x8003

    /**
     * Chart index of each color block, from the sewing objects PE-Design stores in the header:
     * each CEmbOne holds CSewSeg sections and ends with a color log of (section, chart index).
     * Null unless every object has exactly this layout (other object kinds, other section formats).
     */
    private fun segmentColors(data: ByteArray, pecBlockPosition: Int): List<Int>? {
        val start = indexOf(data, EMB_ONE, pecBlockPosition)
        if (start < 0) return null
        val f = ByteReader(data, limit = pecBlockPosition).apply { seek(start + EMB_ONE.size) }
        val colors = mutableListOf<Int>()
        var first = true
        try {
            while (true) {
                f.skip(EMB_ONE_BODY)
                val sections = need(f.u16le())
                if (first) {
                    if (indexOf(data, SEW_SEG, f.position + SEW_SEG.size) != f.position) return null
                    f.skip(SEW_SEG.size)
                    first = false
                } else if (need(f.u16le()) != SEW_SEG_REF) return null
                repeat(sections) { section ->
                    if (section > 0 && need(f.u16le()) != SEW_SEG_REF) return null
                    need(f.u16le()); need(f.u16le()) // flag, color
                    val points = need(f.u16le())
                    if (f.remaining < 4 * points) return null
                    f.skip(4 * points)
                }
                repeat(need(f.u16le())) {
                    need(f.u16le())
                    val color = need(f.u16le())
                    if (colors.lastOrNull() != color) colors += color
                }
                if (f.remaining < 2 || need(f.u16le()) != EMB_ONE_REF) return colors
            }
        } catch (e: TruncatedException) {
            return null
        }
    }

    private fun indexOf(data: ByteArray, needle: ByteArray, limit: Int): Int {
        for (i in 0..minOf(limit, data.size) - needle.size) {
            if (needle.indices.all { data[i + it] == needle[it] }) return i
        }
        return -1
    }
    private val OBJECT_TAG = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0, 0)

    private fun firstObjectTag(data: ByteArray, limit: Int): Int {
        for (i in 12..limit - OBJECT_TAG.size) {
            if (OBJECT_TAG.indices.all { data[i + it] == OBJECT_TAG[it] }) return i + OBJECT_TAG.size
        }
        return limit
    }

    /**
     * u16 count, then per thread: catalog, RGB, 5 bytes, description, brand, chart. Accepted only
     * when every string is plausible text and the object table follows: u16 0, or an object count
     * and the first MFC class tag (FFFF, schema 0, u16 length, "CEmbOne"-like name).
     */
    private fun threadListAt(f: ByteReader): List<EmbThread>? {
        try {
            val count = need(f.u16le())
            if (count !in 1..MAX_THREADS) return null
            val threads = ArrayList<EmbThread>(count)
            repeat(count) { threads += readThread(f) ?: return null }
            return if (objectTableFollows(f)) threads else null
        } catch (e: TruncatedException) {
            return null
        }
    }

    private const val MAX_THREADS = 255

    private fun readThread(f: ByteReader): EmbThread? {
        val catalog = pesString(f)
        val rgb = need(f.u24be())
        need(f.u8()); need(f.s32le())
        val description = pesString(f)
        val brand = pesString(f)
        val chart = pesString(f)
        if (listOf(catalog, description, brand, chart).any { it != null && !plausible(it) }) return null
        // Appliqué steps: catalog 100-102 with no color; Brother shows them as PEC 62-64.
        val applique = catalog?.toIntOrNull()?.minus(100)
        if (rgb == 0 && brand == null && applique != null && applique in APPLIQUE.indices) {
            return PecThreads.forIndex(62 + applique).copy(description = APPLIQUE[applique], catalogNumber = catalog)
        }
        return EmbThread((0xFF shl 24) or rgb, description, catalog, brand, chart)
    }

    private val APPLIQUE = listOf("Appliqué material", "Appliqué position", "Appliqué")

    /** Decoding turns malformed bytes into U+FFFD, so stray binary data fails this test. */
    private fun plausible(text: String): Boolean =
        text.length <= 254 && text.none { Character.isISOControl(it) || it == '\uFFFD' }

    private fun objectTableFollows(f: ByteReader): Boolean {
        val objects = need(f.u16le())
        if (objects == 0) return true
        if (need(f.u16le()) != 0xFFFF || need(f.u16le()) != 0) return false
        val length = need(f.u16le())
        if (length !in 2..32 || f.remaining < length) return false
        val name = f.string(length)
        return name[0] == 'C' && name.all { it.isLetterOrDigit() && it.code < 128 }
    }

    private fun readPec(f: ByteReader, out: PatternBuilder, chart: HeaderChart?) {
        f.skip(3) // "LA:"
        val label = f.string(16).trim()
        if (label.isNotEmpty()) out.metadata.putIfAbsent("name", label)
        f.skip(0xF)
        f.skip(2) // graphic stride, graphic height
        f.skip(0xC)
        val colorCountPos = f.position
        val colorChanges = f.u8() ?: throw EmbFormatException("Truncated PEC header")
        // The count is "color changes", i.e. colors - 1; 0xFF encodes a design with no colors.
        val colorCount = if (colorChanges == 0xFF) 0 else colorChanges + 1
        val colorBytes = f.bytes(colorCount).map { it.toInt() and 0xFF }
        if (colorBytes.size < colorCount) throw EmbFormatException("Truncated PEC color table")
        mapPecColors(colorBytes, out, chart)
        f.seek(colorCountPos + 0x1D2)
        // Block length counts from 5 bytes before the end of this field.
        val blockLength = f.u24le() ?: throw EmbFormatException("Truncated PEC header")
        val blockEnd = f.position - 5 + blockLength
        f.skip(0x0B)
        // Without a trustworthy block end we could decode the thumbnails as stitches.
        if (blockEnd <= f.position || blockEnd > f.size) {
            throw EmbFormatException("PEC stitch block length $blockLength is invalid")
        }
        f.limit = blockEnd
        readPecStitches(f, out)
    }

    /**
     * Assigns a thread to each color block. PEC color bytes give each block's palette index;
     * the PES chart lists the real threads, but not always in block order (some PE-Design files
     * list them in reverse, or reuse one). In order of preference: the sewing segments' color log,
     * chart order, and pairing each palette index with the closest chart thread. A later one is
     * used only when it leaves fewer blocks far from their palette color: the log follows object
     * order, which can differ from sewing order, and similar threads can share one palette index.
     * A chart we had to search for is dropped when most blocks would come out far.
     */
    private fun mapPecColors(colorBytes: List<Int>, out: PatternBuilder, chart: HeaderChart?) {
        val palette = colorBytes.map { PecThreads.forIndex(it) }
        val threads = chart?.threads.orEmpty()
        if (threads.isEmpty()) { out.threads += palette; return }
        if (palette.isEmpty()) { out.threads += threads; return }
        val indices = colorBytes.map { it % PecThreads.SET.size }
        val log = chart!!.blocks?.takeIf { b -> b.size == indices.size && b.all { it in threads.indices } }?.map { threads[it] }
        fun far(mapped: List<EmbThread>) = mapped.indices.count { PecThreads.distance(mapped[it], palette[it]) > FAR }
        // minBy keeps the first of equals, so ties go to the earlier, more structural candidate.
        val best = listOfNotNull(log, byOrder(threads, indices), byPalette(threads, indices)).minBy(::far)
        val far = far(best)
        if (!chart.verified && far * 2 > best.size) {
            out.warnings += "PES thread list does not match the PEC colors; using PEC colors"
            out.threads += palette
        } else {
            out.threads += best
        }
    }

    /** Beyond this red-mean distance (that of a 0x40 step in every channel) a color is "far". */
    private val FAR = PecThreads.distance(0xFF000000.toInt(), 0xFF404040.toInt())

    /** One chart thread per block in order; with fewer threads, one per distinct PEC index. */
    private fun byOrder(threads: List<EmbThread>, indices: List<Int>): List<EmbThread> {
        if (threads.size >= indices.size) return threads.take(indices.size)
        val queue = ArrayDeque(threads)
        val map = HashMap<Int, EmbThread>()
        return indices.map { idx -> map.getOrPut(idx) { queue.removeFirstOrNull() ?: PecThreads.forIndex(idx) } }
    }

    /** Pairs each distinct PEC index with the closest unused chart thread, closest pairs first. */
    private fun byPalette(threads: List<EmbThread>, indices: List<Int>): List<EmbThread> {
        val distinct = indices.distinct()
        val pairs = distinct.flatMap { idx -> threads.indices.map { t -> Triple(PecThreads.distance(threads[t], PecThreads.forIndex(idx)), idx, t) } }
            .sortedWith(compareBy({ it.first }, { distinct.indexOf(it.second) }, { it.third }))
        val chosen = HashMap<Int, EmbThread>()
        val used = HashSet<Int>()
        for ((_, idx, t) in pairs) {
            if (idx in chosen || t in used) continue
            chosen[idx] = threads[t]
            used += t
        }
        return indices.map { chosen[it] ?: PecThreads.forIndex(it) }
    }

    private fun signed12(b: Int): Int {
        val v = b and 0xFFF
        return if (v > 0x7FF) v - 0x1000 else v
    }

    private fun signed7(b: Int): Int = if (b > 63) b - 128 else b

    private fun readPecStitches(f: ByteReader, out: PatternBuilder) {
        // 0xFF in the first byte is the end marker: no valid long-form coordinate sets bit 6.
        var ended = false
        while (true) {
            val val1 = f.u8() ?: break
            if (val1 == 0xFF) { ended = true; break }
            var val2 = f.u8() ?: break
            if (val1 == 0xFE && val2 == 0xB0) {
                f.u8() ?: break // color index, unused: colors come from the color table
                out.colorChange()
                continue
            }
            var jump = false
            var trim = false
            val x: Int
            if (val1 and FLAG_LONG != 0) {
                if (val1 and TRIM_CODE != 0) trim = true
                if (val1 and JUMP_CODE != 0) jump = true
                x = signed12((val1 shl 8) or val2)
                val2 = f.u8() ?: break
            } else {
                x = signed7(val1)
            }
            val y: Int
            if (val2 and FLAG_LONG != 0) {
                if (val2 and TRIM_CODE != 0) trim = true
                if (val2 and JUMP_CODE != 0) jump = true
                val val3 = f.u8() ?: break
                y = signed12((val2 shl 8) or val3)
            } else {
                y = signed7(val2)
            }
            when {
                jump -> out.move(x, y)
                trim -> { out.trim(); out.move(x, y) }
                else -> out.stitch(x, y)
            }
        }
        if (!ended) out.warnings += "Stitch data is truncated; showing the part that could be read"
        out.end()
    }
}

class EmbFormatException(message: String) : Exception(message)
