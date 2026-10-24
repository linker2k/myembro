// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg.image.autotrace

import kotlin.test.*

class AutoTraceThinningTest {
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

    @Test fun originalGrayAndRgbEdgeRulesRemainDistinct() {
        // Original C thin_image on a fully black 3x3 P2 bitmap, same pinned oracle.
        // thin1 deletes horizontal border pixels differently from thin3's directional guards.
        val grayExpected=listOf(255,255,255, 0,0,0, 255,255,255).map { it.toByte() }.toByteArray()
        val rgbGrayExpected=listOf(255,255,255, 255,255,255, 0,0,255)
        val rgbExpected=ByteArray(27) { rgbGrayExpected[it/3].toByte() }
        assertContentEquals(grayExpected,AutoTraceThinning.thin(3,3,ByteArray(9),1))
        assertContentEquals(rgbExpected,AutoTraceThinning.thin(3,3,ByteArray(27),3))
        println("AUTOTRACE_EDGE_RULES_EXACT gray=000/111/000 rgb=000/000/110 (1=foreground)")
    }

    @Test fun malformedOrUnboundedInputsAreRejectedBeforeIndexing() {
        assertFailsWith<IllegalArgumentException> { AutoTraceThinning.thin(0,1,byteArrayOf()) }
        assertFailsWith<IllegalArgumentException> { AutoTraceThinning.thin(2,2,ByteArray(4),2) }
        assertFailsWith<IllegalArgumentException> { AutoTraceThinning.thin(2,2,ByteArray(3)) }
        assertFailsWith<IllegalArgumentException> { AutoTraceThinning.thin(4096,4096,byteArrayOf()) }
        assertFailsWith<IllegalArgumentException> { AutoTraceThinning.thin(1,1,byteArrayOf(0),backgroundRgb=0x1000000) }
    }

    private fun compare(name: String, planes: Int) {
        fun tokens(suffix: String) = requireNotNull(javaClass.getResource("/autotrace/thinning/$name.$suffix"))
            .readText().trim().split(Regex("\\s+"))
        val input=tokens("pgm")
        assertEquals("P2",input[0]);assertEquals("255",input[3])
        val width=input[1].toInt();val height=input[2].toInt()
        val gray=input.drop(4).map { it.toInt().toByte() }.toByteArray()
        val pixels=ByteArray(width*height*planes) { gray[it/planes] }
        val untouched=pixels.copyOf()
        val golden=tokens("p$planes.pbm")
        assertEquals(listOf("P1",width.toString(),height.toString()),golden.take(3))
        val expectedGray=golden.drop(3).map { if(it=="1") 0.toByte() else 255.toByte() }
        val expected=ByteArray(width*height*planes) { expectedGray[it/planes] }
        val actual=AutoTraceThinning.thin(width,height,pixels,planes)
        assertContentEquals(expected,actual,"$name/$planes original C mask")
        assertContentEquals(untouched,pixels,"Caller input must not mutate")
        assertNotSame(pixels,actual)
        assertContentEquals(actual,AutoTraceThinning.thin(width,height,pixels,planes),"Deterministic independent calls")
        assertContentEquals(actual,AutoTraceThinning.thin(width,height,actual,planes),"Original idempotence")
        val inverted=ByteArray(pixels.size) { (255-(pixels[it].toInt() and 255)).toByte() }
        val invertedExpected=ByteArray(expected.size) { (255-(expected[it].toInt() and 255)).toByte() }
        assertContentEquals(invertedExpected,AutoTraceThinning.thin(width,height,inverted,planes,0),"Explicit black background")
        assertContentEquals(expected,AutoTraceThinning.thin(width,height,pixels,planes),"Previous background must not leak")
        println("AUTOTRACE_THINNING_EXACT $name planes=$planes pixels=${width*height}")
    }
}
