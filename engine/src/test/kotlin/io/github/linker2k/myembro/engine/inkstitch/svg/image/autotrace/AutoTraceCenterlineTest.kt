// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg.image.autotrace

import kotlin.test.*

class AutoTraceCenterlineTest {
    @Test fun barGray() = compare("bar",1)
    @Test fun barRgb() = compare("bar",3)
    @Test fun teeGray() = compare("tee",1)
    @Test fun teeRgb() = compare("tee",3)
    @Test fun crossGray() = compare("cross",1)
    @Test fun crossRgb() = compare("cross",3)
    @Test fun loopGray() = compare("loop",1)
    @Test fun loopRgb() = compare("loop",3)
    @Test fun hangulGray() = compare("hangul-han",1)
    @Test fun hangulRgb() = compare("hangul-han",3)

    @Test fun invalidInputsRejected() {
        assertFailsWith<IllegalArgumentException> { AutoTraceCenterline.trace(0,1,byteArrayOf()) }
        assertFailsWith<IllegalArgumentException> { AutoTraceCenterline.trace(1,1,byteArrayOf(),1) }
        assertFailsWith<IllegalArgumentException> { AutoTraceCenterline.trace(1,1,ByteArray(2),2) }
        assertFailsWith<IllegalArgumentException> { AutoTraceCenterline.trace(4096,4096,byteArrayOf()) }
        assertFailsWith<IllegalArgumentException> { AutoTraceCenterline.trace(1,1,byteArrayOf(0),backgroundRgb=-1) }
    }

    private fun compare(name: String, planes: Int) {
        val pgm = requireNotNull(javaClass.getResource("/autotrace/thinning/$name.pgm"))
            .readText().trim().split(Regex("\\s+"))
        val w = pgm[1].toInt(); val h = pgm[2].toInt()
        val gray = pgm.drop(4).map { it.toInt().toByte() }
        val bitmap = ByteArray(w*h*planes) { gray[it/planes] }
        val thinned = AutoTraceThinning.thin(w,h,bitmap,planes)
        val original = thinned.copyOf()
        val tokens = requireNotNull(javaClass.getResource("/autotrace/centerline/$name.p$planes.paths"))
            .readText().trim().split(Regex("\\s+")).map { it.toInt() }.iterator()
        val expected = List(tokens.next()) {
            val open = tokens.next()!=0; val clockwise = tokens.next()!=0
            val color = (tokens.next() shl 16) or (tokens.next() shl 8) or tokens.next()
            val points = List(tokens.next()) { AutoTraceCenterline.Point(tokens.next(),tokens.next()) }
            AutoTraceCenterline.Path(points,color,open,clockwise)
        }
        assertFalse(tokens.hasNext())
        val actual = AutoTraceCenterline.trace(w,h,thinned,planes)
        assertEquals(expected,actual,"Original ordered paths, coordinates, colors and open/clockwise flags")
        assertContentEquals(original,thinned,"Input preservation")
        assertEquals(actual,AutoTraceCenterline.trace(w,h,thinned,planes),"Independent repeat")
        println("AUTOTRACE_CENTERLINE_EXACT $name/$planes paths=${actual.size} points=${actual.sumOf { it.points.size }}")
    }
}
