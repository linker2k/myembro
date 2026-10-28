// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui

import io.github.linker2k.myembro.engine.model.*
import io.github.linker2k.myembro.engine.sewout.*
import io.github.linker2k.myembro.usb.*
import kotlin.test.*

class ManualJumpAdviceTest {
    @Test fun onlyKnownAbsentAutomaticTrimmingWithPositiveCountGetsAdvice() {
        for(auto in listOf<Boolean?>(null,true,false))for(count in listOf<Int?>(null,-1,0,1,2))
            assertEquals(auto==false && count!=null && count>0,manualJumpAdvice(count,"Brother SE400",auto)!=null)
        assertNull(manualJumpAdvice(2,null,false));assertNull(manualJumpAdvice(2,"",false))
        assertEquals(ManualJumpAdvice(2,"SE400"),manualJumpAdvice(2,"Brother SE400",false))
    }
    @Test fun viewerUsesTheReportProfileAndNeverPartialCounts() {
        val p=EmbPattern(listOf(Stitch(0,0,Command.STITCH),Stitch(10,0,Command.JUMP),Stitch(20,0,Command.STITCH)),listOf(EmbPattern.DEFAULT_PALETTE[0]))
        assertEquals(ManualJumpAdvice(1,"SE400"),manualJumpAdvice(SewoutCheck.check(p,SewoutMachines.SE400)))
        assertEquals(ManualJumpAdvice(1,"SE600"),manualJumpAdvice(SewoutCheck.check(p,SewoutMachines.SE600)))
        assertEquals(ManualJumpAdvice(1,"SE700"),manualJumpAdvice(SewoutCheck.check(p,SewoutMachines.SE700)))
        assertNull(manualJumpAdvice(SewoutCheck.check(p,SewoutMachines.SE400,SewoutOptions(maxCommands=1))))
    }
    @Test fun usbUsesPersistedTransferProfileAndRequiresKnownCountsForEverySuccessfulFile() {
        val file=UsbTransferredFile("A.PES",100,manualJumpThreadCount=2,automaticJumpTrim=false,machineName="Brother SE400")
        val r=UsbTransferRecord(drive="USB",files=listOf(file,file.copy(name="B.PES",manualJumpThreadCount=1)))
        assertEquals(ManualJumpAdvice(3,"SE400"),manualJumpAdvice(r))
        assertEquals(ManualJumpAdvice(3,"SE400"),manualJumpAdvice(r.copy(failures=listOf("C.PES" to "failed"))))
        assertNull(manualJumpAdvice(r.copy(files=listOf(file.copy(automaticJumpTrim=true)))))
        assertNull(manualJumpAdvice(r.copy(files=listOf(file.copy(automaticJumpTrim=null)))))
        assertNull(manualJumpAdvice(r.copy(files=listOf(file.copy(manualJumpThreadCount=null)))))
        assertNull(manualJumpAdvice(r.copy(files=listOf(file,file.copy(machineName="other")))))
        assertNull(manualJumpAdvice(r.copy(files=emptyList())))
        assertNull(manualJumpAdvice(r.copy(files=listOf(UsbTransferredFile("old.PES",100,manualJumpThreadCount=2)))))
    }
}
