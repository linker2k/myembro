// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.sewout

import io.github.linker2k.myembro.engine.model.*
import io.github.linker2k.myembro.engine.io.PecWriter
import kotlin.test.*

class JumpThreadCleanupTest {
    private fun s(x:Int,c:Command=Command.STITCH)=Stitch(x,0,c)
    private fun p(vararg stitches:Stitch)=EmbPattern(stitches.toList(),listOf(EmbPattern.DEFAULT_PALETTE[0]))
    private fun travel()=p(s(0,Command.JUMP),s(10),s(10,Command.TRIM),s(20,Command.JUMP),s(30,Command.JUMP),
        s(30),s(30,Command.JUMP),s(40),s(50,Command.JUMP),s(50,Command.END))
    @Test fun se400FactsAreObservedAndDistinctFromTheColorEndCutter() {
        assertEquals(false,SewoutMachines.SE400.automaticJumpTrim.value)
        assertEquals(true,SewoutMachines.SE400.manualJumpCleanup.value)
        assertContains(SewoutMachines.SE400.automaticJumpTrim.source!!,"사용자 SE400 실기")
        assertEquals(true,SewoutMachines.SE400.colorEndCutter.value)
    }
    @Test fun splitJumpsCountOnceAndTrimRequestsDoNotPretendToCutOnSe400() {
        val source=travel();val before=source.stitches.toList()
        assertEquals(JumpThreadCleanup(1,1),JumpThreadCleanup.assess(source,SewoutMachines.SE400))
        assertEquals(1,SewoutCheck.check(source,SewoutMachines.SE400).statistics.manualJumpThreadCount)
        assertNull(SewoutCheck.check(source,SewoutMachines.SE400,SewoutOptions(maxCommands=2)).statistics.manualJumpThreadCount)
        val header=ByteArray(22).also {"#PES0001".toByteArray().copyInto(it);it[8]=22}
        assertEquals(JumpThreadCleanup(1,1),JumpThreadCleanup.assessPes(header+PecWriter.writePayload(source),SewoutMachines.SE400))
        assertEquals(before,source.stitches)
    }
    @Test fun colorBoundariesResetConnectionsButProgramStopsDoNotClaimACut() {
        val source=p(s(0),s(10,Command.JUMP),s(10,Command.COLOR_CHANGE),s(20),
            s(30,Command.JUMP),s(30,Command.STOP),s(40),s(50,Command.JUMP),s(50,Command.END))
        assertEquals(JumpThreadCleanup(1,1),JumpThreadCleanup.assess(source,SewoutMachines.SE400))
        assertEquals(JumpThreadCleanup(0,0),JumpThreadCleanup.assess(p(s(0,Command.JUMP),s(0),s(10)),SewoutMachines.SE400))
    }
    @Test fun unconfirmedOrDisabledAutomaticSettingsNeverPretendZeroManualCuts() {
        val unknown=SewoutMachines.SE400.copy(automaticJumpTrim=Specification(),manualJumpCleanup=Specification())
        assertNull(JumpThreadCleanup.assess(travel(),unknown).manualCutCount)
        val automatic=unknown.copy(automaticJumpTrim=Specification(true,"synthetic"))
        assertNull(JumpThreadCleanup.assess(travel(),automatic).manualCutCount)
        assertEquals(0,JumpThreadCleanup.assess(travel(),automatic,SwitchSetting.ENABLED).manualCutCount)
        assertEquals(1,JumpThreadCleanup.assess(travel(),automatic,SwitchSetting.DISABLED).manualCutCount)
    }
}
