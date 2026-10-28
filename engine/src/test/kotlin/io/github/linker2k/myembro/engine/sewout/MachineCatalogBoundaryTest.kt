// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.sewout

import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.model.*
import kotlin.test.*

class MachineCatalogBoundaryTest {
    private fun pattern(x:Int,y:Int)=EmbPattern(listOf(Stitch(x,0,Command.STITCH),Stitch(0,y,Command.STITCH),Stitch(x,0,Command.STITCH)),listOf(PecThreads.forIndex(5)))
    @Test fun uniqueIdsAndSourcedFactsDoNotInheritUnknownCapabilities() {
        assertEquals(SewoutMachines.all.size,SewoutMachines.all.map {it.id}.toSet().size)
        for(p in BrotherMachineCatalog.all) {
            assertTrue(p.sourceUrls.isNotEmpty(),p.id)
            assertTrue(p.hoops.isNotEmpty(),p.id)
            val machine=p.sewoutMachine()
            assertNull(machine.maxStitches.value,p.id)
            assertNull(machine.acceptedPesVersions.value,p.id)
            assertEquals(p.automaticJumpTrim,machine.automaticJumpTrim.value,p.id)
        }
        assertTrue(SewoutMachine("unknown","unknown",Specification(),Specification()).knownHoopsMm.isEmpty())
    }
    @Test fun allProfileHoopsUseTheSameWriterAndSewoutIntegerBoundaries() {
        for(p in BrotherMachineCatalog.all) {
            val hoop=p.maximumArea
            val x=hoop.width-1;val y=hoop.height-1
            val bytes=PesWriter.write(pattern(x,y),PesWriteSettings(hoop=hoop))
            assertTrue(PecViewportCheck.check(bytes,hoop).valid,p.id)
            val report=SewoutCheck.checkPes(bytes,p.sewoutMachine(),SewoutOptions(hoop=hoop,origin=DesignOrigin.APP_GENERATED))
            assertFalse(FindingCode.HOOP_EXCEEDED in report.findingCounts,p.id)
            assertFalse(FindingCode.INVALID_PES in report.findingCounts,p.id)
            assertFailsWith<IllegalArgumentException>(p.id) {PesWriter.write(pattern(x+1,y),PesWriteSettings(hoop=hoop))}
            assertFailsWith<IllegalArgumentException>(p.id) {PesWriter.write(pattern(x,y+1),PesWriteSettings(hoop=hoop))}
        }
    }
}
