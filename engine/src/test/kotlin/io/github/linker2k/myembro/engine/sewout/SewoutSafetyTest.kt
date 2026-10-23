// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.sewout

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.*
import kotlin.test.*

/** Synthetic inputs only. Boundaries from the frozen POLICY.md, never private designs. */
class SewoutSafetyTest {
    private fun s(x: Int, y: Int = 0, c: Command = Command.STITCH) = Stitch(x,y,c)
    private fun p(vararg commands: Stitch) = EmbPattern(commands.toList(), listOf(EmbPattern.DEFAULT_PALETTE[0]))
    private fun check(pattern: EmbPattern, generated: Boolean = false) = SewoutCheck.check(pattern, options =
        SewoutOptions(origin = if (generated) DesignOrigin.APP_GENERATED else DesignOrigin.IMPORTED))
    private fun SewoutReport.of(code: FindingCode) = findings.filter { it.code == code }
    private fun dense(count: Int) = p(*Array(count) { s(it % 5, it / 5 % 5) })

    @Test fun density15IsNotBlocked16IsGeneratedBlockAndImportedCaution() {
        assertTrue(check(dense(15),true).of(FindingCode.DENSITY_HOTSPOT).isEmpty())
        val generated = check(dense(16),true).of(FindingCode.DENSITY_HOTSPOT).single()
        assertEquals(Severity.BLOCKER,generated.severity)
        assertEquals(16.0,generated.measured)
        assertEquals(CheckArea(0.0,0.0,5.0,5.0),generated.highlight!!.area)
        assertEquals(Severity.WARNING,check(dense(16)).of(FindingCode.DENSITY_HOTSPOT).single().severity)
    }
    @Test fun halfCellShiftDetectsDensityHiddenAcrossTheUnshiftedGridBoundary() {
        val cloud = p(*Array(16) { s(it % 4 - 2, it / 4 - 2) })
        assertEquals(16.0, check(cloud,true).of(FindingCode.DENSITY_HOTSPOT).single().measured)
        assertEquals(Severity.WARNING,check(cloud).of(FindingCode.DENSITY_HOTSPOT).single().severity)
    }
    @Test fun imported30IsCaution31And38Block() {
        assertFalse(check(dense(30)).hasBlockingIssues)
        assertEquals(SewoutVerdict.CAUTION,check(dense(30)).verdict)
        for (count in listOf(31,38)) assertEquals(Severity.BLOCKER,
            check(dense(count)).of(FindingCode.DENSITY_HOTSPOT).single().severity)
    }
    @Test fun onlyConsecutiveNeedlesRepeatNotSeparatedVisitsEvenAcrossControlCommands() {
        assertTrue(check(p(s(0),s(0),s(0)),true).of(FindingCode.REPEATED_PENETRATION).isEmpty())
        val repeated = p(s(0),s(0),s(0,c=Command.TRIM),s(0),s(0,c=Command.COLOR_CHANGE),s(0))
        assertEquals(Severity.BLOCKER,check(repeated,true).of(FindingCode.REPEATED_PENETRATION).single().severity)
        assertEquals(Severity.WARNING,check(repeated).of(FindingCode.REPEATED_PENETRATION).single().severity)
        assertTrue(check(p(s(0),s(20),s(0),s(20),s(0),s(20),s(0)),true)
            .of(FindingCode.REPEATED_PENETRATION).isEmpty())
    }
    @Test fun startAndEndMissingLocksBlockGeneratedAndWarnImported() {
        val source = p(s(0),s(40),s(80),s(80,c=Command.END))
        assertEquals(2,check(source,true).of(FindingCode.MISSING_LOCK_STITCH).size)
        assertTrue(check(source,true).of(FindingCode.MISSING_LOCK_STITCH).all { it.severity==Severity.BLOCKER })
        assertFalse(check(source).hasBlockingIssues)
        assertEquals(2,check(source).of(FindingCode.MISSING_LOCK_STITCH).size)
    }
    @Test fun shortReturnsAndTwoOrThreeCoincidentBoundaryNeedlesSecureThread() {
        for (source in listOf(p(s(0),s(5),s(0),s(40),s(35),s(40)),
            p(s(0),s(0),s(40),s(40)),p(s(0),s(0),s(0),s(40),s(40),s(40)))) {
            assertTrue(check(source,true).of(FindingCode.MISSING_LOCK_STITCH).isEmpty())
            assertFalse(check(source,true).hasBlockingIssues)
        }
        assertTrue(check(p(s(0),s(0),s(0),s(0)),true).hasBlockingIssues) // Lock does not waive repeats.
    }
    @Test fun jumpsCannotSupplyLockReturnAndTrimColorStopSplitThreadSections() {
        val jumpReturn=p(s(0),s(10),s(0,c=Command.JUMP),s(0),s(10))
        assertTrue(check(jumpReturn,true).of(FindingCode.MISSING_LOCK_STITCH).isNotEmpty())
        for (command in listOf(Command.TRIM,Command.COLOR_CHANGE,Command.STOP)) {
            val r=check(p(s(0),s(40),s(40,c=command),s(100,c=Command.JUMP),s(100),s(140)),true)
            assertEquals(4,r.of(FindingCode.MISSING_LOCK_STITCH).size)
        }
        assertTrue(check(p(s(0,c=Command.TRIM))).of(FindingCode.MISSING_LOCK_STITCH).isEmpty())
    }
    @Test fun sourceMarkersNotFilenameOrPlacementChooseGeneratedPolicy() {
        val raw=p(*Array(16) { s(0) })
        val generated=EmbPattern(raw.stitches,raw.threads,mapOf("myembro.inkstitch.finished" to "v1"))
        assertEquals(DesignOrigin.APP_GENERATED,SewoutCheck.originOf(generated))
        assertEquals(Severity.BLOCKER,SewoutCheck.check(generated).of(FindingCode.DENSITY_HOTSPOT).single().severity)
        val imported=Project(objects=listOf(StitchObject("raw","text.pes",raw)))
        assertEquals(Severity.WARNING,SewoutCheck.check(imported).of(FindingCode.DENSITY_HOTSPOT).single().severity)
    }
    @Test fun formatHoopAndMaximumLengthPoliciesAreNotDowngradedForImportedFiles() {
        assertTrue(SewoutCheck.checkPes(byteArrayOf(1,2,3)).hasBlockingIssues)
        assertEquals(Severity.BLOCKER,check(p(s(0),s(123))).of(FindingCode.LONG_STITCH).single().severity)
        assertTrue(check(p(s(0),s(1100))).of(FindingCode.HOOP_EXCEEDED).isNotEmpty())
        assertTrue(check(p(s(0),s(1),s(10))).of(FindingCode.SHORT_STITCH).all { it.severity==Severity.INFO })
    }
    @Test fun severeImportedDensitySurvivesPesRoundtripAndFindingSuppression() {
        val source=p(*Array(38) { s(0) })
        val r=SewoutCheck.checkPes(PesWriter.write(source),options=SewoutOptions(maxFindings=1))
        assertTrue(r.hasBlockingIssues)
        assertEquals(1,r.findings.size)
        assertEquals(FindingCode.DENSITY_HOTSPOT,r.findings.single().code)
        assertTrue(r.suppressedFindings>0)
    }
}
