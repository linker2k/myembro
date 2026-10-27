// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.texture

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail

/** Synthetic repo-owned inputs; expected text is produced by the pinned original Java algorithm.
 * Normal engine tests NEVER fetch, compile or execute upstream. See drawingbot-oracle/README.md.
 */
class SketchOracleTest {
    private data class Fixture(val width: Int, val height: Int, val color: (Int, Int) -> Int)
    private fun gray(n: Int) = 0xff000000.toInt() or (n shl 16) or (n shl 8) or n
    private fun fixtures(): Map<String, Fixture> = linkedMapOf(
        "white" to Fixture(8, 8) { _, _ -> -1 },
        "black" to Fixture(12, 12) { _, _ -> gray(0) },
        "ramp" to Fixture(17, 11) { x, _ -> gray(x * 255 / 16) },
        "dot" to Fixture(5, 5) { x, y -> if (x == 3 && y == 2) gray(0) else -1 },
        "horizontal" to Fixture(13, 9) { _, y -> if (y == 4) gray(0) else -1 },
        "vertical" to Fixture(9, 13) { x, _ -> if (x == 4) gray(0) else -1 },
        "diagonal" to Fixture(13, 13) { x, y -> if (x == y) gray(0) else -1 },
        "equal-tiles" to Fixture(20, 10) { _, _ -> gray(64) },
        "small" to Fixture(7, 6) { x, y -> gray(if ((x + y) % 2 == 0) 32 else 128) },
        "remainder" to Fixture(23, 13) { x, y -> 0xff000000.toInt() or ((x * 9) shl 16) or ((y * 17) shl 8) or ((x + y) * 7) },
    )
    private fun check(name: String) {
        val f = fixtures().getValue(name)
        val pixels = IntArray(f.width * f.height) { f.color(it % f.width, it / f.width) }
        val rasterText = "${f.width} ${f.height}\n" + pixels.joinToString(" ") + "\n"
        val hash = MessageDigest.getInstance("SHA-256").digest(rasterText.toByteArray()).joinToString("") { "%02x".format(it) }
        val actual = mutableListOf("INPUT ${f.width} ${f.height} $hash")
        SketchLines.render(f.width, f.height, pixels, trace = actual::add)
        val directory = File("build/texture-oracle-port").apply { mkdirs() }
        File(directory, "$name.raster").writeText(rasterText)
        File(directory, "$name.trace").writeText(actual.joinToString("\n", postfix = "\n"))
        val stream = assertNotNull(javaClass.getResourceAsStream("/drawingbot-oracle/$name.trace"))
        val expected = stream.bufferedReader().use { it.readLines() }
        val first = (0 until minOf(actual.size, expected.size)).firstOrNull { actual[it] != expected[it] }
        if (first != null) fail("$name: first difference event=$first expected='${expected[first]}' actual='${actual[first]}'")
        assertEquals(expected.size, actual.size, "$name event count; first missing/extra event ${minOf(expected.size, actual.size)}")
        println("DBV3 $name: ${actual.size} events, ${actual.count { it.startsWith("LINE ") }} lines, exact match")
    }
    @Test fun white() = check("white")
    @Test fun black() = check("black")
    @Test fun ramp() = check("ramp")
    @Test fun dot() = check("dot")
    @Test fun horizontal() = check("horizontal")
    @Test fun vertical() = check("vertical")
    @Test fun diagonal() = check("diagonal")
    @Test fun equalTiles() = check("equal-tiles")
    @Test fun small() = check("small")
    @Test fun remainder() = check("remainder")
}
