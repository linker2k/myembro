// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.model

import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor
import io.github.linker2k.myembro.engine.io.*
import kotlin.test.*

class ThreadStartCleanupTest {
    private fun s(x:Int,y:Int=10,c:Command=Command.STITCH)=Stitch(x,y,c)
    @Test fun t3RemovesOnlyStationaryCommandsAndKeepsEveryNonzeroHalfStitchLeg() {
        val input=listOf(s(10,c=Command.JUMP),s(10,c=Command.JUMP),s(10),s(15),s(20),s(15),s(10),s(30),s(30,c=Command.END))
        val expected=listOf(input[0])+input.drop(3)
        val source=EmbPattern(input,listOf(EmbPattern.DEFAULT_PALETTE[0]))
        val output=GeneratedPatternProcessor.forSewout(source)
        assertEquals(expected,output.stitches)
        assertEquals(input,source.stitches)
        assertSame(output,GeneratedPatternProcessor.forSewout(output))
        val viewport = listOf(s(5,0),s(10,0),s(5,0),s(0,0),s(20,0),s(20,0,Command.END))
        for(v in PesVersion.entries)assertEquals(viewport,PesReader.read(PesWriter.write(source,v)).stitches)
    }
    @Test fun samePlaceTrimRestartJoinsSewingButDistantRestartRetainsTrim() {
        val source=listOf(s(10),s(20),s(20,c=Command.TRIM),s(20,c=Command.JUMP),s(20),s(25),s(30),s(25))
        assertEquals(listOf(source[0],source[1])+source.drop(5),ThreadStartCleanup.clean(source).stitches)
        val distant=listOf(s(10),s(20,c=Command.TRIM),s(50,c=Command.JUMP),s(50),s(55))
        assertEquals(listOf(distant[0],distant[1],distant[2],distant[4]),ThreadStartCleanup.clean(distant).stitches)
    }
    @Test fun restartWithoutZeroNeedleStillDropsSamePlaceTrimAndColorStopAreNotJoined() {
        val source=listOf(s(10),s(10,c=Command.TRIM),s(10,c=Command.JUMP),s(15),s(20))
        assertEquals(listOf(source[0],source[3],source[4]),ThreadStartCleanup.clean(source).stitches)
        for(control in listOf(Command.COLOR_CHANGE,Command.STOP)) {
            val commands=listOf(s(10),s(10,c=Command.TRIM),s(10,c=control),s(10,c=Command.JUMP),s(15))
            assertTrue(ThreadStartCleanup.clean(commands).stitches.any { it.command==Command.TRIM })
            assertTrue(ThreadStartCleanup.clean(commands).stitches.any { it.command==control })
        }
    }
    @Test fun zeroNeedlesInsideBodyAreAlsoRemovedWithoutChangingNonzeroTrajectory() {
        val input=listOf(s(10),s(20),s(20),s(30),s(30,c=Command.END))
        assertEquals(listOf(input[0],input[1],input[3],input[4]),ThreadStartCleanup.clean(input).stitches)
    }
}
