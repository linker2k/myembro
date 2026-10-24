// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.stitches.utils

import io.github.linker2k.myembro.engine.inkstitch.utils.Point
import kotlin.test.*

class SatinStrokeOrderTest {
    private fun stroke(character:Int,index:Int,x:Double,y:Double)=SatinStrokeOrder.Stroke(character,index,listOf(Point(x,y),Point(x+1,y+1)))
    @Test fun completesCharacterBeforeNextEvenWhenNextStartsHigher() {
        val ordered=SatinStrokeOrder.order(listOf(stroke(1,0,0.0,0.0),stroke(0,1,0.0,5.0),stroke(1,2,5.0,2.0),stroke(0,3,0.0,10.0)))
        assertEquals(listOf(1,3,0,2),ordered.map { it.sourceIndex })
    }
    @Test fun strokeStartYThenXExactTieIsStable() {
        val ordered=SatinStrokeOrder.order(listOf(stroke(0,0,5.0,3.0),stroke(0,1,9.0,2.0),stroke(0,2,1.0,3.0),stroke(0,3,1.0,3.0)))
        assertEquals(listOf(1,2,3,0),ordered.map { it.sourceIndex })
    }
    @Test fun rejectsMissingOwnershipAndEmptyPath() {
        assertFailsWith<IllegalArgumentException> { SatinStrokeOrder.order(listOf(stroke(-1,0,0.0,0.0))) }
        assertFailsWith<IllegalArgumentException> { SatinStrokeOrder.order(listOf(SatinStrokeOrder.Stroke(0,0,emptyList()))) }
    }
}
