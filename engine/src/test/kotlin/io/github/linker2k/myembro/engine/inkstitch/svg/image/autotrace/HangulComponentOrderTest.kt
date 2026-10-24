// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg.image.autotrace
import kotlin.test.*

class HangulComponentOrderTest {
    @Test fun diagonalInkIsConnectedAndCharacterCompletesFirst() {
        val pixels=ByteArray(100) { 255.toByte() }
        for((x,y) in listOf(1 to 1,2 to 2,1 to 7,7 to 0,7 to 8))pixels[y*10+x]=0
        val labels=HangulComponentOrder.label(10,10,pixels,listOf(
            HangulComponentOrder.GlyphBounds(0,0,0,5,10),HangulComponentOrder.GlyphBounds(1,5,0,10,10)))
        assertEquals(listOf(0,0,1,1),labels.ordered.map { it.characterIndex })
        assertEquals(listOf(1,7,0,8),labels.ordered.map { it.top })
        val path=AutoTraceCenterline.Path(listOf(AutoTraceCenterline.Point(1,8),AutoTraceCenterline.Point(2,7)),0,true)
        assertEquals(2,labels.component(path).pixelCount)
    }
    @Test fun sameTopSortsBoundingBoxLeftNotPathStart() {
        val pixels=ByteArray(30) { 255.toByte() };pixels[2]=0;pixels[7]=0
        val labels=HangulComponentOrder.label(10,3,pixels,listOf(HangulComponentOrder.GlyphBounds(0,0,0,10,3)))
        assertEquals(listOf(2,7),labels.ordered.map { it.left })
    }
    @Test fun missingOrAmbiguousGlyphOwnershipRejected() {
        val pixels=byteArrayOf(0,255.toByte(),255.toByte())
        val box=HangulComponentOrder.GlyphBounds(0,0,0,3,1)
        assertFailsWith<IllegalArgumentException>{HangulComponentOrder.label(3,1,pixels,listOf(box.copy(left=1)))}
        assertFailsWith<IllegalArgumentException>{HangulComponentOrder.label(3,1,pixels,listOf(box,box.copy(characterIndex=1)))}
    }
}
