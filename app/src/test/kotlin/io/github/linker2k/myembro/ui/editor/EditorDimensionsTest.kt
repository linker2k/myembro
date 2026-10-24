// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.model.*
import io.github.linker2k.myembro.ui.format.Dimensions
import kotlin.test.*

class EditorDimensionsTest {
    private fun obj(id: String="a", transform: Transform=Transform()) = StitchObject(id,id,EmbPattern(
        listOf(-177 to -96,-77 to -96,23 to -96,123 to -96,178 to -96,178 to 0,
            178 to 96,78 to 96,-22 to 96,-122 to 96,-177 to 96,-177 to 0,-177 to -96)
            .map { (x,y) -> Stitch(x,y,Command.STITCH) },listOf(EmbThread(0xff000000.toInt()))),transform=transform)
    private fun header(bytes: ByteArray): Pair<Int,Int> {
        fun u(i: Int)=bytes[i].toInt() and 255
        val pec=(0..3).fold(0) { n,i -> n or (u(8+i) shl (8*i)) }
        fun u16(i: Int)=u(i) or (u(i+1) shl 8)
        return u16(pec+520) to u16(pec+522)
    }
    private fun matchesHeader(p: Project, ids: Set<String>) {
        val displayed=assertNotNull(p.selectionOutputBounds(ids))
        val selected=p.copy(objects=p.objects.filter {it.visible && it.id in ids})
        for (version in PesVersion.entries) {
            val actual=header(PesWriter.write(selected.flatten(),version))
            assertEquals(actual,displayed.width to displayed.height,"$version ${p.objects.map {it.transform}}")
            assertEquals(Dimensions.length(actual.first/10.0),Dimensions.length(displayed.width/10.0))
            assertEquals(Dimensions.length(actual.second/10.0),Dimensions.length(displayed.height/10.0))
        }
    }
    @Test fun `fractional move reproduces conservative box error but labels match header`() {
        val a=obj(transform=Transform(x=0.4,y=0.4));val p=Project(objects=listOf(a))
        assertEquals(356,a.previewBounds()!!.width);assertEquals(193,a.previewBounds()!!.height)
        val dimensions=assertNotNull(p.selectionOutputBounds(setOf("a")))
        assertEquals(355,dimensions.width);assertEquals(192,dimensions.height)
        matchesHeader(p,setOf("a"))
    }
    @Test fun `positive and negative half-unit translation boundaries match both PES headers`() {
        val movesMm=listOf(-0.06,-0.05,-0.04,0.0,0.04,0.05,0.06)
        for(x in movesMm) for(y in movesMm)
            matchesHeader(Project(objects=listOf(obj(transform=Transform(x=x*10,y=y*10)))),setOf("a"))
    }
    @Test fun `rotated mirrored scaled selections use rounded stitch destinations`() {
        for(t in listOf(Transform(rotationDeg=37.0),Transform(rotationDeg=90.0,scaleX=-1.0),
            Transform(rotationDeg=-23.0,scaleX=1.13,scaleY=0.87)))
            for(mm in listOf(-0.06,-0.05,-0.04,0.04,0.05,0.06))
                matchesHeader(Project(objects=listOf(obj(transform=t.copy(x=mm*10,y=-mm*10)))),setOf("a"))
    }
    @Test fun `multiple selections exclude hidden and unselected objects`() {
        val p=Project(objects=listOf(obj(transform=Transform(x=-80.4,y=30.5)),
            obj("b",Transform(x=80.6,y=-30.5)),obj("hidden",Transform(x=700.0)).copy(visible=false),
            obj("other",Transform(y=700.0))))
        matchesHeader(p,setOf("a","b","hidden"))
    }
    @Test fun `header includes retained needle up destinations rather than only sewn bounds`() {
        val base=obj();val source=EmbPattern(base.source.stitches+Stitch(200,120,Command.JUMP),base.source.threads)
        val p=Project(objects=listOf(base.copy(source=source)))
        matchesHeader(p,setOf("a"))
    }
    @Test fun `empty hidden or missing selections have no label`() {
        val p=Project(objects=listOf(obj().copy(visible=false)))
        for(ids in listOf(emptySet(),setOf("a"),setOf("missing"))) assertNull(p.selectionOutputBounds(ids))
    }
}
