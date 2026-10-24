// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine

import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.model.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.*

/** SE400 regression: machine centers the PEC rectangle, whose origin must be top-left. */
class PesViewportTest {
    private val points = listOf(100 to -50,100 to 50,-100 to 50,-100 to -50,100 to -50)
    private val source = EmbPattern(points.map { (x,y)->Stitch(x,y,Command.STITCH) } + Stitch(100,-50,Command.END),
        listOf(PecThreads.forIndex(5)))
    private val expected = listOf(200 to 0,200 to 100,0 to 100,0 to 0,200 to 0)
        .map { (x,y)->Stitch(x,y,Command.STITCH) } + Stitch(200,0,Command.END)
    private fun u16(b:ByteArray,i:Int)=(b[i].toInt() and 255) or ((b[i+1].toInt() and 255) shl 8)
    private fun u32(b:ByteArray,i:Int)=ByteBuffer.wrap(b,i,4).order(ByteOrder.LITTLE_ENDIAN).int
    @Test fun everyPublicWriterOverloadUsesTheSameTopLeftViewport() {
        val before=source.stitches.toList()
        val outputs=listOf(PesWriter.write(source),PesWriter.writeV1(source),PesWriter.writeV6(source)) +
            PesVersion.entries.flatMap { v->listOf(PesWriter.write(source,v),PesWriter.write(source,PesWriteSettings(v))) }
        for(bytes in outputs) {
            assertEquals(expected,PesReader.read(bytes).stitches)
            val pec=u32(bytes,8)
            assertEquals(200,u16(bytes,pec+520));assertEquals(100,u16(bytes,pec+522))
            assertTrue(PesReader.read(bytes).warnings.isEmpty())
            // Re-reading a top-left PES and writing it again must not shift it a second time.
            assertEquals(expected,PesReader.read(PesWriter.write(PesReader.read(bytes))).stitches)
        }
        assertEquals(before,source.stitches)
    }
    @Test fun v6EditorTransformAndPecDimensionsAgree() {
        val b=PesWriter.writeV6(source)
        val marker=b.toString(Charsets.ISO_8859_1).indexOf("CEmbOne")
        val matrix=marker+7+16
        fun f(i:Int)=ByteBuffer.wrap(b,matrix+i*4,4).order(ByteOrder.LITTLE_ENDIAN).float
        assertEquals(listOf(1f,0f,0f,1f,900f,1050f),(0..5).map(::f))
        assertEquals(200,u16(b,matrix+24+6));assertEquals(100,u16(b,matrix+24+8))
    }
    @Test fun originRemainsImplicitWhenStationaryInitialJumpIsRemoved() {
        val input=EmbPattern(listOf(Stitch(-100,-50,Command.JUMP),Stitch(-100,-50,Command.STITCH),
            Stitch(100,50,Command.STITCH)),source.threads)
        for(v in PesVersion.entries) {
            val bytes=PesWriter.write(input,v)
            assertEquals(listOf(Stitch(200,100,Command.STITCH),Stitch(200,100,Command.END)),PesReader.read(bytes).stitches)
            val pec=u32(bytes,8)
            assertEquals(200,u16(bytes,pec+520));assertEquals(100,u16(bytes,pec+522))
        }
    }
}
