// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine

import io.github.linker2k.myembro.engine.io.PecThreads
import io.github.linker2k.myembro.engine.io.PesReader
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.engine.model.Stitch
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Thread colors of PES headers as PE-Design writes them (docs/colors.md). Brother's own designs
 * cannot be redistributed, so the PE-Design layouts are rebuilt here byte for byte: UTF-16
 * CArchive strings, a chart listed in reverse, CEmbOne/CSewSeg objects with their color log.
 */
class PesColorTest {
    private fun thread(rgb: Int, catalog: String, name: String, brand: String = "EMBROIDERY") =
        EmbThread((0xFF shl 24) or rgb, name, catalog, brand)

    // From the "Summer lemon" design that showed purple/blue/red/green instead of these.
    private val lemon = thread(0xFFD911, "206", "Harvest Gold")
    private val silver = thread(0xA8A8A8, "005", "Silver")
    private val white = thread(0xF0F0F0, "001", "White")
    private val rose = thread(0xF64A8A, "086", "Deep Rose")
    // Madeira threads (from a Brother design) a little off the PEC palette, so a PEC fallback shows.
    private val vanilla = thread(0xF8DF99, "1526", "French Vanilla", "Madeira PolyNeon 40")
    private val greenTea = thread(0x66934D, "1968", "Green Tea", "Madeira PolyNeon 40")
    private val christmas = thread(0x006C45, "1750", "Christmas Green", "Madeira PolyNeon 40")
    // Two Madeira reds that both map to PEC "Red".
    private val salsa = thread(0xD5392C, "1588", "Salsa", "Madeira PolyNeon 40")
    private val cinnamon = thread(0xAC211C, "1637", "Cinnamon Candy", "Madeira PolyNeon 40")

    /** One short block per thread; the PEC part comes from our writer, which picks PEC indices by color. */
    private fun pecBlock(blocks: List<EmbThread>): ByteArray {
        val stitches = mutableListOf<Stitch>()
        blocks.forEachIndexed { i, _ ->
            if (i > 0) stitches += Stitch(i * 20, 0, Command.COLOR_CHANGE)
            stitches += Stitch(i * 20, 0, Command.STITCH)
            stitches += Stitch(i * 20 + 10, 10, Command.STITCH)
        }
        stitches += Stitch(0, 0, Command.END)
        val pes = PesWriter.writeV6(EmbPattern(stitches, blocks))
        val offset = (0..3).sumOf { (pes[8 + it].toInt() and 0xFF) shl (8 * it) }
        return pes.copyOfRange(offset, pes.size)
    }

    private class Bytes : ByteArrayOutputStream() {
        fun u16(v: Int) { write(v); write(v ushr 8) }
        fun u32(v: Int) = repeat(4) { write(v ushr (8 * it)) }
        fun ascii(s: String) = write(s.toByteArray(Charsets.US_ASCII))
        /** PE-Design's UTF-16 CArchive string, including the empty one (FF FE FF 00). */
        fun cstring(s: String) { write(0xFF); u16(0xFFFE); write(s.length); write(s.toByteArray(Charsets.UTF_16LE)) }
        fun tag(name: String) { u16(0xFFFF); u16(0); u16(name.length); ascii(name) }
    }

    /** A v8 header like PE-Design 11's: metadata, design page, thread chart, then sewing objects. */
    private fun pes(
        blocks: List<EmbThread>,
        chart: List<EmbThread>,
        colorLog: List<Int>? = null,
        version: String = "0080",
        definitionsBeforeChart: ByteArray? = null,
        appliqueChart: List<String> = emptyList(),
    ): ByteArray {
        val h = Bytes()
        h.u16(1); h.ascii("01")
        h.cstring("레몬"); repeat(4) { h.write(0) } // name, then empty category/author/keywords/comments
        h.write(ByteArray(38)); h.write(0); h.write(ByteArray(26)) // page settings, no image file
        if (definitionsBeforeChart == null) repeat(3) { h.u16(0) }
        else { h.u16(1); h.u16(0); h.u16(0); h.write(definitionsBeforeChart) }
        h.u16(appliqueChart.size + chart.size)
        for (catalog in appliqueChart) {
            h.cstring(catalog); h.write(ByteArray(3)); h.write(0); h.u32(1)
            h.cstring(""); h.cstring(""); h.cstring("")
        }
        for (t in chart) {
            h.cstring(t.catalogNumber!!)
            h.write(t.red); h.write(t.green); h.write(t.blue)
            h.write(0); h.u32(1)
            h.cstring(t.description!!); h.cstring(t.brand!!); h.cstring("")
        }
        if (colorLog == null) h.u16(0)
        else {
            h.u16(1); h.tag("CEmbOne")
            h.write(ByteArray(58))
            h.u16(colorLog.size); h.tag("CSewSeg")
            colorLog.forEachIndexed { i, color ->
                if (i > 0) h.u16(0x8003)
                h.u16(0); h.u16(color); h.u16(1); h.u32(0)
            }
            h.u16(colorLog.size)
            colorLog.forEachIndexed { i, color -> h.u16(i); h.u16(color) }
            h.u16(0); h.u16(0)
        }
        val head = Bytes()
        head.ascii("#PES$version"); head.u32(12 + h.size())
        head.write(h.toByteArray()); head.write(pecBlock(blocks))
        return head.toByteArray()
    }

    private fun colors(p: EmbPattern) = p.threads.map { "%06x".format(it.color and 0xFFFFFF) }
    private fun colors(vararg t: EmbThread) = t.map { "%06x".format(it.color and 0xFFFFFF) }

    @Test
    fun unicodeHeaderStringsDoNotShiftTheThreadList() {
        val p = PesReader.read(pes(listOf(lemon, silver, white, rose), listOf(lemon, silver, white, rose)))
        assertEquals(colors(lemon, silver, white, rose), colors(p))
        assertEquals(listOf("206", "005", "001", "086"), p.threads.map { it.catalogNumber })
        assertEquals("Deep Rose", p.threads[3].description)
        assertEquals("EMBROIDERY", p.threads[0].brand)
        assertEquals("레몬", p.metadata["name"])
        assertTrue(p.warnings.isEmpty(), "${p.warnings}")
    }

    @Test
    fun reversedChartFollowsTheSewingSegmentsColorLog() {
        val blocks = listOf(salsa, vanilla, greenTea, christmas)
        val p = PesReader.read(pes(blocks, blocks.reversed(), colorLog = listOf(3, 2, 1, 0)))
        assertEquals(colors(salsa, vanilla, greenTea, christmas), colors(p))
    }

    @Test
    fun reversedChartWithoutSegmentsIsPairedByPecColor() {
        val blocks = listOf(salsa, vanilla, greenTea, christmas)
        val p = PesReader.read(pes(blocks, blocks.reversed()))
        assertEquals(colors(salsa, vanilla, greenTea, christmas), colors(p))
    }

    @Test
    fun threadsSharingOnePecColorKeepTheirOwnColorsAndBlocks() {
        val blocks = listOf(salsa, cinnamon, white)
        val p = PesReader.read(pes(blocks, blocks, colorLog = listOf(0, 1, 2)))
        assertEquals(colors(salsa, cinnamon, white), colors(p))
        assertEquals(2, p.stitches.count { it.command == Command.COLOR_CHANGE })
        // Without the log, chart order still wins over collapsing both reds into one.
        val q = PesReader.read(pes(blocks, blocks))
        assertEquals(colors(salsa, cinnamon, white), colors(q))
    }

    @Test
    fun appliqueStepsShowBrotherAppliqueColors() {
        val material = PecThreads.forIndex(62)
        val position = PecThreads.forIndex(63)
        val blocks = listOf(material, position, rose)
        val p = PesReader.read(pes(blocks, listOf(rose), appliqueChart = listOf("100", "101")))
        assertEquals(colors(material, position, rose), colors(p))
        assertEquals("Appliqué material", p.threads[0].description)
    }

    @Test
    fun threadListAfterFillDefinitionsIsFoundBySearch() {
        val definition = byteArrayOf(0x10, 0, 0, 0, 0x2A, 0, 0, 0) + ByteArray(20) { it.toByte() }
        val p = PesReader.read(pes(listOf(lemon, rose), listOf(lemon, rose), definitionsBeforeChart = definition))
        assertEquals(colors(lemon, rose), colors(p))
        assertTrue(p.warnings.isEmpty(), "${p.warnings}")
    }

    @Test
    fun searchedChartFarFromThePecColorsIsDropped() {
        // An unknown layout whose only plausible "chart" disagrees with every block.
        val blue = thread(0x0A55A3, "405", "Blue")
        val green = thread(0x00673E, "507", "Emerald Green")
        val p = PesReader.read(pes(listOf(lemon, rose), listOf(blue, green), version = "0110"))
        assertEquals(listOf(PecThreads.forIndex(pecIndexOf(lemon)), PecThreads.forIndex(pecIndexOf(rose))).map { "%06x".format(it.color and 0xFFFFFF) }, colors(p))
        assertTrue(p.warnings.any { "PEC colors" in it }, "${p.warnings}")
    }

    private fun pecIndexOf(t: EmbThread): Int =
        (1 until PecThreads.SET.size).minBy { PecThreads.distance(t, PecThreads.forIndex(it)) }

    @Test
    fun unknownLayoutsStillFindTheirChart() {
        val p = PesReader.read(pes(listOf(lemon, rose), listOf(lemon, rose), version = "0110"))
        assertEquals(colors(lemon, rose), colors(p))
        assertEquals("레몬", p.metadata["name"])
    }

    @Test
    fun realFilesMatchPyembroidery() {
        val expected = Json.parse(String(resource("threads.json"))) as Map<*, *>
        for ((file, threads) in expected) {
            val p = PesReader.read(resource(file as String))
            assertEquals(threads, colors(p), file)
            assertTrue(p.warnings.isEmpty(), "$file: ${p.warnings}")
        }
    }

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResource("/fixtures/embroidepy/$name")) { "missing fixture $name" }.readBytes()
}
