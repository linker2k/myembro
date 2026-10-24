// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg.image.autotrace

import kotlinx.serialization.json.*
import kotlin.test.*

class AutoTraceFitTest {
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

    @Test fun emptyAndInvalid() {
        assertEquals(emptyList(),AutoTraceFit.fit(emptyList()))
        assertFailsWith<IllegalArgumentException> { AutoTraceFit.fit(listOf(AutoTraceCenterline.Path(emptyList(),0,true))) }
        assertFailsWith<IllegalArgumentException> { AutoTraceFit.fit(listOf(AutoTraceCenterline.Path(listOf(AutoTraceCenterline.Point(-1,0)),0,true))) }
    }

    private fun compare(name: String,planes: Int) {
        val tokens=requireNotNull(javaClass.getResource("/autotrace/thinning/$name.pgm")).readText().trim().split(Regex("\\s+"))
        val w=tokens[1].toInt(); val h=tokens[2].toInt(); val gray=tokens.drop(4).map { it.toInt().toByte() }
        val bitmap=ByteArray(w*h*planes) { gray[it/planes] }
        val paths=AutoTraceCenterline.trace(w,h,AutoTraceThinning.thin(w,h,bitmap,planes),planes)
        val expected=Json.parseToJsonElement(requireNotNull(javaClass.getResource("/autotrace/fit/$name.p$planes.json")).readText()).jsonObject["paths"]!!.jsonArray
        val actual=AutoTraceFit.fit(paths)
        assertEquals(expected.size,actual.size,"path count")
        var controls=0; var cubics=0
        expected.forEachIndexed { i, value ->
            val path=value.jsonObject; val result=actual[i]
            assertEquals(path["open"]!!.jsonPrimitive.boolean,result.open,"path $i open")
            assertEquals(path["clockwise"]!!.jsonPrimitive.boolean,result.clockwise,"path $i orientation")
            assertEquals(paths[i].colorRgb,result.colorRgb,"color preservation")
            val segments=path["segments"]!!.jsonArray
            assertEquals(segments.size,result.segments.size,"path $i segment count")
            segments.forEachIndexed { j,s ->
                val segment=s.jsonObject; val output=result.segments[j]
                assertEquals(segment["degree"]!!.jsonPrimitive.int,output.degree,"path $i segment $j degree")
                if(output.degree==3) cubics++
                val points=segment["v"]!!.jsonArray
                assertEquals(4,output.v.size)
                points.forEachIndexed { k, coords ->
                    val p=coords.jsonArray.map { it.jsonPrimitive.float }; val a=output.v[k]
                    assertEquals(p[0].toBits(),a.x.toBits(),"path $i segment $j control $k x expected=${p[0]} actual=${a.x}")
                    assertEquals(p[1].toBits(),a.y.toBits(),"path $i segment $j control $k y expected=${p[1]} actual=${a.y}")
                    assertEquals(p[2].toBits(),a.z.toBits(),"path $i segment $j control $k z")
                    controls++
                }
            }
        }
        assertEquals(actual,AutoTraceFit.fit(paths),"repeat and input preservation")
        println("AUTOTRACE_FIT_EXACT $name/$planes paths=${actual.size} segments=${actual.sumOf { it.segments.size }} cubics=$cubics controls=$controls floatBits=exact")
    }
}
