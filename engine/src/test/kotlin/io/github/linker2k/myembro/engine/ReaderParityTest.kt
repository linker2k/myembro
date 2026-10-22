// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine

import io.github.linker2k.myembro.engine.io.EmbFormatException
import io.github.linker2k.myembro.engine.io.PatternReader
import io.github.linker2k.myembro.engine.model.Command
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Every fixture under resources/fixtures has a sibling `.json` holding what pyembroidery
 * decoded from it (see tools/make_fixtures.py). Our readers must agree exactly.
 */
class ReaderParityTest {
    private val fixtures = listOf("flower", "long_moves").flatMap { name ->
        listOf("v1.pes", "v6.pes", "pec", "dst").map { "$name.$it" }
    }

    private fun resource(path: String): ByteArray =
        requireNotNull(javaClass.getResource("/fixtures/$path")) { "missing fixture $path" }.readBytes()

    @Test
    fun stitchesMatchPyembroidery() {
        for (file in fixtures) {
            val pattern = PatternReader.read(resource(file), file)
            val expected = Json.parse(String(resource("$file.json"))) as Map<*, *>
            val expectedStitches = (expected["stitches"] as List<*>).map { s ->
                s as List<*>
                Triple((s[0] as Number).toInt(), (s[1] as Number).toInt(), s[2] as String)
            }
            val actual = pattern.stitches.map { Triple(it.x, it.y, it.command.name) }
            assertEquals(expectedStitches.size, actual.size, "$file: stitch count")
            expectedStitches.zip(actual).forEachIndexed { i, (e, a) ->
                assertEquals(e, a, "$file: stitch #$i")
            }
            val expectedColors = (expected["threads"] as List<*>).map { (it as Number).toInt() }
            if (expectedColors.isNotEmpty()) {
                assertEquals(expectedColors, pattern.threads.map { it.color and 0xFFFFFF }, "$file: threads")
            }
        }
    }

    @Test
    fun statsAreConsistent() {
        val pattern = PatternReader.read(resource("flower.v6.pes"), "flower.v6.pes")
        assertEquals(3, pattern.colorBlocks.size)
        assertEquals(pattern.stitchCount, pattern.colorBlocks.sumOf { it.stitchCount(pattern.stitches) })
        assertEquals(Command.END, pattern.stitches.last().command)
        val b = requireNotNull(pattern.bounds)
        assertTrue(b.width in 500..600 && b.height in 500..800, "unexpected bounds $b")
        assertEquals("flower", pattern.metadata["name"])
    }

    @Test
    fun rejectsUnknownFormat() {
        assertFailsWith<EmbFormatException> { PatternReader.read(ByteArray(16), "x.jef") }
    }
}

/** Minimal JSON parser so the engine stays dependency-free. Supports what the fixtures use. */
private object Json {
    fun parse(text: String): Any? = Parser(text).value()

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> num()
            }
        }
        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++; ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws(); val k = str(); ws(); i++ // ':'
                m[k] = value(); ws()
                if (s[i++] == '}') return m
            }
        }
        fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++; ws()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l += value(); ws()
                if (s[i++] == ']') return l
            }
        }
        fun str(): String {
            val sb = StringBuilder()
            i++
            while (s[i] != '"') {
                if (s[i] == '\\') { i++; sb.append(s[i]) } else sb.append(s[i])
                i++
            }
            i++
            return sb.toString()
        }
        fun num(): Number {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "-+.eE")) i++
            return s.substring(start, i).toDouble()
        }
    }
}
