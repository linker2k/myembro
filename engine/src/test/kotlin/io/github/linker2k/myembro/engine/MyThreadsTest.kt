// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine

import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.inkstitch.svg.VectorArt
import io.github.linker2k.myembro.engine.inkstitch.svg.image.ColorDrawingPipeline
import kotlin.test.*
import org.junit.Test

class MyThreadsTest {
    @Test fun enabledDisabledAndEmptyPreserveFallback() {
        val red = PecThreads.forIndex(5)
        val blue = PecThreads.forIndex(2)
        assertEquals(red, VectorArt.nearestThread(red.color))
        assertEquals(blue, VectorArt.nearestThread(red.color, MyThreads(true,setOf(2))))
        assertEquals(red, VectorArt.nearestThread(red.color, MyThreads(false,setOf(2))))
        assertEquals(red, VectorArt.nearestThread(red.color, MyThreads(true)))
        assertEquals(listOf(blue), MyThreads(true,setOf(2)).ranked(red.color).map { it.first })
        assertEquals(PecThreads.distance(blue.color,red.color), MyThreads(true,setOf(2)).ranked(red.color).single().second)
    }
    @Test fun photoUsesOnlyOwnedThreadAndKeepsDefaultWhenDisabled() {
        val w=32; val pixels=IntArray(w*w) { p -> if(p%w in 5..26 && p/w in 5..26) PecThreads.forIndex(5).color else 0 }
        val original=ColorDrawingPipeline.trace(w,w,pixels,20.0,2)
        val disabled=ColorDrawingPipeline.trace(w,w,pixels,20.0,2,myThreads=MyThreads(false,setOf(2)))
        val owned=ColorDrawingPipeline.trace(w,w,pixels,20.0,2,myThreads=MyThreads(true,setOf(2)))
        assertEquals(original.layers.map { it.thread },disabled.layers.map { it.thread })
        assertTrue(owned.layers.isNotEmpty())
        assertTrue(owned.layers.all { it.thread==PecThreads.forIndex(2) })
        assertNotEquals(original.layers.map { it.thread },owned.layers.map { it.thread })
    }
}
