// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.sewout

import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.model.*
import kotlin.test.*

class CompositeTrimRegressionTest {
    private fun s(x:Int,y:Int,c:Command=Command.STITCH)=Stitch(x,y,c)
    @Test fun trimCrossesColorOrStopOnlyWhenItCanRideTheNextRealJump() {
        for(control in listOf(Command.COLOR_CHANGE,Command.STOP)) {
            val pattern=EmbPattern(listOf(s(20,0),s(0,20),s(20,0),s(20,0,Command.TRIM),s(20,0,control),
                s(50,20,Command.JUMP),s(50,20),s(60,20),s(50,20),s(50,20,Command.END)),
                listOf(PecThreads.forIndex(5),PecThreads.forIndex(2)))
            for(version in PesVersion.entries) {
                val bytes=PesWriter.write(pattern,version)
                val expected=listOf(s(20,0),s(0,20),s(20,0),s(20,0,control),s(20,0,Command.TRIM),
                    s(50,20,Command.JUMP),s(60,20),s(50,20),s(50,20,Command.END))
                assertEquals(expected,PesReader.read(bytes).stitches)
                assertTrue(PesOutputSafety.errors(bytes,io.github.linker2k.myembro.engine.edit.HoopSpec.H100,pattern).isEmpty())
            }
        }
    }
    @Test fun terminalTrimDoesNotInventAStationaryMoveAndRawPecStaysVerbatim() {
        val pattern=EmbPattern(listOf(s(20,0),s(0,20),s(20,0),s(20,0,Command.TRIM),s(20,0,Command.END)),listOf(PecThreads.forIndex(5)))
        for(version in PesVersion.entries) {
            val read=PesReader.read(PesWriter.write(pattern,version))
            assertEquals(pattern.stitches.take(3)+s(20,0,Command.END),read.stitches)
        }
        assertEquals(1,PesReader.read(PecWriter.write(pattern)).trimCount)
    }
    @Test fun genuineSixteenNeedleDensityIsRejectedWithoutDroppingBodyStitches() {
        val body=List(16) { i->s(1-i%2,1-i%2) }
        val source=EmbPattern(body,listOf(PecThreads.forIndex(5)),mapOf("myembro.inkstitch.finished" to "v1"))
        assertEquals(body,PesWriter.prepareForOutput(source).stitches)
        for(version in PesVersion.entries) {
            val error=assertFailsWith<IllegalArgumentException> { PesWriter.write(source,version) }
            assertTrue(error.message!!.contains("DENSITY_HOTSPOT"))
        }
        assertEquals(body,source.stitches)
    }
}
