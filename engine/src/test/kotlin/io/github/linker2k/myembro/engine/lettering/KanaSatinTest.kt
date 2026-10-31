// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.inkstitch.svg.image.autotrace.HangulComponentOrder.Component
import io.github.linker2k.myembro.engine.inkstitch.elements.Stroke
import io.github.linker2k.myembro.engine.inkstitch.elements.StrokeParameters
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor
import io.github.linker2k.myembro.engine.inkstitch.svg.PIXELS_PER_MM
import io.github.linker2k.myembro.engine.inkstitch.utils.Point
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.*
import kotlin.test.*

class KanaSatinTest {
    @Test fun kanaBranchNeverClaimsHangulOrOtherScripts() {
        assertTrue(KanaSatin.applies("が ぱ\nガパ"))
        for(text in listOf("정민","が정","山","abc"," ","゛")) assertFalse(KanaSatin.applies(text),text)
        assertTrue(HangulSatin.applies("정민"))
        assertFalse(HangulSatin.applies("が"))
        for(code in 0xAC00..0xD7A3)assertFalse(KanaSatin.isKana(code))
    }
    @Test fun voicingMarksAreSelectedOnlyForDecomposableVoicedKana() {
        val body=Component(0,0,0,20,100,120,3000)
        val first=Component(1,0,90,0,100,15,100)
        val second=Component(2,0,105,0,115,15,100)
        assertEquals(listOf(first,second),KanaCenterlineGlyph.markComponents('が'.code,listOf(first,body,second)))
        assertEquals(emptyList(),KanaCenterlineGlyph.markComponents('か'.code,listOf(first,body,second)))
        assertEquals(listOf(first),KanaCenterlineGlyph.markComponents('ぱ'.code,listOf(body,first)))
        assertFailsWith<IllegalArgumentException> { KanaCenterlineGlyph.markComponents('が'.code,listOf(body,first)) }
    }
    @Test fun measuredGoGeometryNoLongerCreatesARowCycle() {
        // Noto Sans JP 400, 128px raster, U+3054. Bbox measurements only, no font program fixture.
        val components=listOf(Component(0,0,94,8,108,27,81),Component(1,0,80,13,95,32,85),
            Component(2,0,15,28,80,33,253),Component(3,0,8,76,89,115,385))
        assertFailsWith<IllegalArgumentException> {
            io.github.linker2k.myembro.engine.inkstitch.svg.image.autotrace.HangulComponentOrder.order(components)
        }
        assertEquals(listOf(2,3,1,0),KanaCenterlineGlyph.orderComponents('ご'.code,components).map { it.id })
    }
    @Test fun aCounterAloneCannotBeMistakenForMarkInk() {
        fun box(a:Double,b:Double)=listOf(a to a,b to a,b to b,a to b,a to a)
        val raster=BinaryGlyphRaster(100,100,ByteArray(10000){255.toByte()},RasterToSource(0,0,0,0,100,100))
        val contours=listOf(box(8.0,24.0),box(12.0,16.0))
        // Only the hole fits this envelope; the entire original polygon must be rejected.
        assertFailsWith<IllegalArgumentException> {
            KanaCenterlineGlyph.measureMark(raster,Component(0,0,11,11,19,19,20),contours,15.0)
        }
        assertTrue(KanaCenterlineGlyph.measureMark(raster,Component(0,0,8,8,24,24,100),contours,15.0)>0)
    }
    private val thread=EmbThread(0xff000000.toInt(),"Black 40wt")
    private val path=listOf(0.0 to 0.0,10.0 to 0.0)
    private fun data(width:Double)=KanaSatinData(CenterlineSatinData(listOf(path),listOf(0),listOf(0),mapOf(0 to 1.2)),mapOf(0 to width))
    @Test fun belowOneMillimetreUsesExactlyTheExistingTripleBeanPort() {
        val actual=KanaSatin.generate(data(0.999),15.0,15.0,thread)
        val stroke=Stroke(listOf(path.map { (x,y)->Point(x*PIXELS_PER_MM,y*PIXELS_PER_MM) }),thread,
            StrokeParameters(bean_stitch_repeats=listOf(1),force_lock_stitches=true))
        val expected=GeneratedPatternProcessor.fromGroups(stroke.toStitchGroups(null),pecJumpTrims=true,
            lockAtThreadBoundaries=true,omitZeroLengthMotions=true).centeredGenerated()
        assertEquals(expected.stitches,actual.stitches)
        assertContentEquals(PesWriter.write(expected),PesWriter.write(actual))
        assertEquals("1",actual.metadata["myembro.kana.bean_marks"])
        val satin=KanaSatin.generate(data(1.0),15.0,15.0,thread)
        assertEquals("1",satin.metadata["myembro.kana.satin_marks"])
        assertEquals("0",satin.metadata["myembro.kana.bean_marks"])
    }
    @Test fun markPolicyReevaluatesAtHeightAndSurvivesProjectRoundtrip() {
        val source=data(0.75)
        val obj=TextObject("kana",text="ぱ",fontKind=FontKind.OUTLINE,stitchStyle=StitchStyle.SATIN,
            heightMm=15.0,contourSourceHeight=15.0,kanaSatin=source,thread=thread)
        val restored=ProjectJson.decode(ProjectJson.encode(Project(objects=listOf(obj)))).objects.single() as TextObject
        assertEquals(source,restored.kanaSatin)
        assertContentEquals(PesWriter.write(obj.localPattern()),PesWriter.write(restored.localPattern()))
        assertEquals("1",obj.localPattern().metadata["myembro.kana.bean_marks"])
        assertEquals("1",obj.copy(heightMm=20.0).localPattern().metadata["myembro.kana.satin_marks"])
    }
}
