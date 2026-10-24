// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.model.*
import io.github.linker2k.myembro.engine.io.*
import kotlin.test.*
class HangulSatinTest {
    private val data=CenterlineSatinData(listOf(listOf(0.0 to 0.0,12.0 to 0.0),listOf(0.0 to 10.0,12.0 to 10.0),listOf(30.0 to 0.0,42.0 to 0.0)),listOf(0,0,1),listOf(0,1,0),mapOf(0 to 1.13,1 to 1.13))
    private val obj=TextObject("test",text="한글",fontKind=FontKind.OUTLINE,stitchStyle=StitchStyle.SATIN,contourSourceHeight=15.0,heightMm=15.0,centerlineSatin=data)
    @Test fun offlineSaveLoadAndHeightChangeRegenerateTheSameBackend() {
        val restored=ProjectJson.decode(ProjectJson.encode(Project(objects=listOf(obj)))).objects.single() as TextObject
        assertEquals(data,restored.centerlineSatin)
        assertEquals(obj.localPattern().stitches,restored.localPattern().stitches)
        assertEquals(HangulSatin.BACKEND,restored.localPattern().metadata["myembro.lettering.backend"])
        assertTrue(restored.copy(heightMm=20.0).localPattern().bounds!!.width>restored.localPattern().bounds!!.width)
        assertTrue(PesReader.read(PesWriter.write(restored.localPattern())).stitchCount>0)
    }
    @Test fun hangulOnlyPolicyAndMalformedData() {
        assertTrue(HangulSatin.applies("한 글\n자"));assertFalse(HangulSatin.applies("한 A"));assertFalse(HangulSatin.applies(" "))
        assertFailsWith<IllegalArgumentException>{data.copy(components=emptyList())}
        assertFailsWith<IllegalArgumentException>{data.copy(widths=emptyMap())}
    }
    @Test fun measurementReusesInteriorMedianNotConstantWidth() {
        fun box(width:Double)=listOf(listOf(0.0 to 0.0,20.0 to 0.0,20.0 to width,0.0 to width,0.0 to 0.0))
        assertTrue(HangulSatin.measuredWidthSource(box(2.0),15.0)>HangulSatin.measuredWidthSource(box(1.0),15.0))
    }
    @Test fun explicitLegacyFillStillUsesExistingBackend() {
        val legacy=obj.copy(stitchStyle=StitchStyle.FILL,contours=listOf(listOf(0.0 to 0.0,10.0 to 0.0,10.0 to 8.0,0.0 to 8.0,0.0 to 0.0)),contourCharacters=listOf(0))
        assertTrue(legacy.localPattern().stitchCount>0)
        assertNotEquals(HangulSatin.BACKEND,legacy.localPattern().metadata["myembro.lettering.backend"])
    }
}
