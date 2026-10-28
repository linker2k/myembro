// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.sewout

import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.model.*
import java.io.File
import kotlin.test.*

class P7DensityPolicyTest {
    private fun pattern(n:Int)=EmbPattern(List(n) { i->
        if(i%2==0) Stitch(0,1,Command.STITCH) else Stitch(1,0,Command.STITCH)
    }+Stitch(if(n%2==1)0 else 1,if(n%2==1)1 else 0,Command.END),listOf(PecThreads.forIndex(5)),
        mapOf(GeneratedPatternProcessor.FINISHED_KEY to "v1"))
    private fun unchecked(p:EmbPattern):ByteArray {
        val header=ByteArray(22).also { "#PES0001".toByteArray().copyInto(it);it[8]=22 }
        return header+PecWriter.writePayload(p,topLeftViewport=true)
    }
    @Test fun generatedAndImported15_16_30_31BoundariesKeepWarningsAndLocations() {
        val out=System.getenv("P7_FIXTURE_OUT")?.let { File(it).apply { mkdirs() } }
        for(n in listOf(14,15,16,29,30,31,32)) {
            val p=pattern(n);val bytes=unchecked(p)
            for(origin in listOf(DesignOrigin.APP_GENERATED,DesignOrigin.IMPORTED)) {
                val report=SewoutCheck.checkPes(bytes,options=SewoutOptions(origin=origin))
                assertEquals(n,report.statistics.peakCellPenetrations,"$origin n=$n")
                val findings=report.findings.filter { it.code==FindingCode.DENSITY_HOTSPOT }
                when {
                    n<=15 -> assertTrue(findings.isEmpty())
                    n<=30 -> {
                        assertTrue(findings.isNotEmpty());assertTrue(findings.all { it.severity==Severity.WARNING })
                        assertTrue(findings.all { it.highlight?.area!=null });assertFalse(report.hasBlockingIssues,report.findings.toString())
                    }
                    else -> assertTrue(findings.any { it.severity==Severity.BLOCKER })
                }
            }
            for(version in PesVersion.entries) {
                if(n<=30) assertTrue(PesWriter.write(p,version).isNotEmpty())
                else assertTrue(assertFailsWith<PesOutputSafety.Rejected> { PesWriter.write(p,version) }
                    .report.findings.any { it.code==FindingCode.DENSITY_HOTSPOT && it.severity==Severity.BLOCKER })
            }
            out?.resolve("density-$n.pes")?.writeBytes(bytes)
        }
    }
}
