// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.photo

import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor
import io.github.linker2k.myembro.engine.model.*
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.sewout.*
import kotlin.test.*

/** Synthetic integer points; deterministic seed 20261028. No raster/user image fixture. */
class TextureNormalizedDensityTest {
    private val points15To17=listOf(5 to -2,6 to -1,5 to -2,4 to -8,0 to -1,-1 to -7,-3 to -4,-1 to -2,2 to -8,0 to 1,3 to -7,3 to -1,-3 to 0,1 to -7,1 to 0,6 to -2,-3 to -2,3 to -5,0 to -8,1 to -5,3 to -8,3 to -7,-2 to -8,5 to -1,-2 to -2,3 to 1,0 to -1,0 to -3,5 to 1,-3 to -7,1 to -2,5 to -2,5 to -1,-2 to -6,1 to 0,1 to -6,3 to -7,3 to -6,0 to 0,4 to -1,3 to -4,-1 to 0,5 to -7,-1 to -7,-1 to 1,6 to 1,-3 to -8,5 to -6,3 to -3,3 to -1,-3 to -6,2 to -2,4 to 1,-1 to 0,0 to 1,-1 to 0)
    private val points18To12=listOf(-1 to -4,0 to -3,-1 to -4,2 to 1,4 to -1,4 to -5,2 to -7,7 to 0,3 to -1,0 to -5,5 to -7,1 to -5,5 to -1,7 to -7,3 to -5,5 to -6,1 to -2,6 to -1,0 to -1,6 to 0,2 to -1,6 to -6,0 to -1,6 to 1,4 to -7,3 to -4,4 to -4,7 to 2,1 to -2,3 to -7,0 to -2,-2 to -5,1 to -7,1 to 1,7 to -6,4 to -2,-2 to -3,7 to -4,0 to -5,2 to -4,6 to 0,7 to 1,6 to 0)
    @Test fun rawAndNormalizedPhasesDoNotCausePreflightRejectionOrHideWarning() {
        for((rawPeak,wirePeak,points) in listOf(Triple(18,12,points18To12),Triple(15,17,points15To17))) {
            val p=EmbPattern(points.map { (x,y)->Stitch(x,y,Command.STITCH) }+
                Stitch(points.last().first,points.last().second,Command.END),listOf(PecThreads.forIndex(5)),
                mapOf(GeneratedPatternProcessor.FINISHED_KEY to "v1"))
            val raw=SewoutCheck.check(p,options=SewoutOptions(origin=DesignOrigin.APP_GENERATED))
            assertEquals(rawPeak,raw.statistics.peakCellPenetrations)
            val actual=ThreadTextureOutputGate.check(p,PesWriteSettings(),SewoutMachines.SE400)
            assertEquals(wirePeak,actual.report.statistics.peakCellPenetrations)
            assertTrue(actual.canUse,actual.report.findings.toString());assertNotNull(actual.bytes)
            assertTrue(ThreadTextureOutputGate.canEdit(true,true,false,actual.report,true))
            assertContentEquals(actual.bytes,PesWriter.write(p))
            val density=actual.report.findings.filter { it.code==FindingCode.DENSITY_HOTSPOT }
            if(wirePeak<16) assertTrue(density.isEmpty()) else {
                assertTrue(density.isNotEmpty());assertTrue(density.all { it.severity==Severity.WARNING })
                assertTrue(density.all { it.highlight?.area!=null })
            }
            println("TEXTURE_PHASE raw=$rawPeak normalized=$wirePeak canUse=${actual.canUse} verdict=${actual.report.verdict}")
        }
    }
}
