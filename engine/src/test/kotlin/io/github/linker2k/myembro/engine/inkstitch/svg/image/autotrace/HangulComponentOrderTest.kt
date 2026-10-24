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
    private fun component(id:Int,x:Int,top:Int,bottom:Int,character:Int=0)=
        HangulComponentOrder.Component(id,character,x,top,x+5,bottom,10)
    @Test fun overlappingUpperComponentsGoLeftFirstBeforeLowerComponent() {
        val right=component(0,30,0,70);val left=component(1,0,10,65);val lower=component(2,10,80,120)
        assertEquals(listOf(1,0,2),HangulComponentOrder.order(listOf(right,left,lower)).map { it.id })
    }
    @Test fun halfOverlapIsInclusiveAndBelowHalfIsVertical() {
        val right=component(0,30,0,20);val exactlyHalf=component(1,0,10,30);val belowHalf=component(2,0,11,31)
        assertTrue(HangulComponentOrder.sameRow(right,exactlyHalf))
        assertFalse(HangulComponentOrder.sameRow(right,belowHalf))
        assertEquals(listOf(1,0),HangulComponentOrder.order(listOf(right,exactlyHalf)).map { it.id })
        assertEquals(listOf(0,2),HangulComponentOrder.order(listOf(belowHalf,right)).map { it.id })
    }
    @Test fun characterOrderPrecedesRowOverlapAndIsInputOrderIndependent() {
        val a=component(0,0,10,60);val b=component(1,30,0,70);val c=component(2,0,0,100,1)
        assertEquals(listOf(0,1,2),HangulComponentOrder.order(listOf(c,b,a)).map { it.id })
        assertEquals(HangulComponentOrder.order(listOf(a,b,c)),HangulComponentOrder.order(listOf(c,b,a)))
    }
    @Test fun conflictingPairwiseRowsFailInsteadOfUnstableSort() {
        val a=component(0,20,0,10);val b=component(1,10,5,15);val c=component(2,0,10,20)
        assertFailsWith<IllegalArgumentException> { HangulComponentOrder.order(listOf(a,b,c)) }
    }
}
