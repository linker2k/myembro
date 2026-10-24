// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.sewout

import io.github.linker2k.myembro.engine.io.PecWriter
import io.github.linker2k.myembro.engine.io.PecThreads
import io.github.linker2k.myembro.engine.model.*
import kotlin.test.*

class CrosscheckScopeTest {
    private fun pes(max: Int): ByteArray {
        val pattern = EmbPattern(listOf(Stitch(0,0,Command.JUMP), Stitch(0,0,Command.STITCH)) +
            (50..max step 50).map { Stitch(it,it,Command.STITCH) } + Stitch(max,max,Command.STITCH),
            listOf(PecThreads.forIndex(5)))
        return ByteArray(22).also { "#PES0001".toByteArray().copyInto(it); it[8]=22 } + PecWriter.writePayload(pattern)
    }

    @Test fun corpusScopeOmitsOnlyHoopAndNeverMislabelsSizeAsInvalidFile() {
        for (max in 999..1001) {
            val bytes = pes(max)
            val strict = SewoutCheck.checkPes(bytes, options=SewoutOptions(origin=DesignOrigin.IMPORTED))
            assertEquals(max>999, FindingCode.HOOP_EXCEEDED in strict.findingCounts)
            assertFalse(FindingCode.INVALID_PES in strict.findingCounts)
            val reference = SewoutCheck.checkPes(bytes, options=SewoutOptions(origin=DesignOrigin.IMPORTED, applyHoop=false))
            assertFalse(FindingCode.HOOP_EXCEEDED in reference.findingCounts)
            assertFalse(FindingCode.MACHINE_AREA_EXCEEDED in reference.findingCounts)
            assertFalse(reference.hasBlockingIssues)
            assertEquals(Severity.WARNING, reference.findingSeverities[FindingCode.ZERO_LENGTH_MOTION])
            // Origin and exact dimensions remain hard file invariants in corpus scope.
            val damaged = bytes.copyOf().also { it[22+520] = (it[22+520]+1).toByte() }
            assertEquals(Severity.BLOCKER, SewoutCheck.checkPes(damaged,
                options=SewoutOptions(applyHoop=false)).findingSeverities[FindingCode.INVALID_PES])
        }
    }

    @Test fun boundedDetailsDoNotEraseConditionSeverities() {
        val findings = Findings(1)
        findings.add(FindingCode.SHORT_STITCH,Severity.INFO,"reference")
        findings.add(FindingCode.LONG_STITCH,Severity.WARNING,"long")
        findings.add(FindingCode.ZERO_LENGTH_MOTION,Severity.WARNING,"zero")
        findings.add(FindingCode.HOOP_EXCEEDED,Severity.BLOCKER,"hoop")
        assertEquals(1,findings.items.size)
        assertEquals(Severity.BLOCKER,findings.items.single().severity)
        assertEquals(mapOf(FindingCode.SHORT_STITCH to Severity.INFO, FindingCode.LONG_STITCH to Severity.WARNING,
            FindingCode.ZERO_LENGTH_MOTION to Severity.WARNING, FindingCode.HOOP_EXCEEDED to Severity.BLOCKER),findings.worstByCode)
        val bytes=pes(999)
        val small=SewoutCheck.checkPes(bytes,options=SewoutOptions(maxFindings=1))
        val full=SewoutCheck.checkPes(bytes,options=SewoutOptions(maxFindings=10000))
        assertEquals(full.findingSeverities,small.findingSeverities)
        assertEquals(full.verdict,small.verdict)
        assertTrue(small.suppressedFindings>0)
    }
}
