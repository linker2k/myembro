// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.sewout

import io.github.linker2k.myembro.engine.edit.Project
import io.github.linker2k.myembro.engine.edit.StitchObject
import io.github.linker2k.myembro.engine.edit.Transform
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.*
import kotlin.test.*

/** Original synthetic geometry only; never read private regression files from committed tests. */
class SewoutSafetyTest {
    private fun s(x: Int, y: Int = 0, c: Command = Command.STITCH) = Stitch(x, y, c)
    private fun p(vararg stitches: Stitch) = EmbPattern(stitches.toList(), listOf(EmbPattern.DEFAULT_PALETTE[0]))
    private fun SewoutReport.of(code: FindingCode) = findings.filter { it.code == code }

    @Test fun ninthDistinctPunctureInHalfMillimeterCellBlocksButEighthDoesNot() {
        fun pattern(count: Int) = p(*Array(count) { s(it % 3, it / 3) })
        val eight = SewoutCheck.check(pattern(8))
        assertFalse(eight.hasBlockingIssues)
        assertTrue(eight.of(FindingCode.DENSITY_HOTSPOT).isEmpty())
        val nine = SewoutCheck.check(pattern(9))
        assertTrue(nine.hasBlockingIssues)
        val finding = nine.of(FindingCode.DENSITY_HOTSPOT).single()
        assertEquals(Severity.BLOCKER, finding.severity)
        assertEquals(9.0, finding.measured)
        assertEquals(9.0, finding.threshold)
        assertEquals(CheckArea(0.0, 0.0, 5.0, 5.0), finding.highlight!!.area)
        assertEquals(1, nine.statistics.densityHotspotCount)
        assertTrue(nine.of(FindingCode.REPEATED_PENETRATION).isEmpty())
        assertFalse(SewoutCheck.check(pattern(9), options = SewoutOptions(hotspotPenetrations = 10)).hasBlockingIssues)
    }

    @Test fun fourthExactPunctureBlocksIncludingAcrossTrimAndColorBoundaries() {
        assertFalse(SewoutCheck.check(p(*Array(3) { s(0) })).hasBlockingIssues)
        val report = SewoutCheck.check(p(s(0), s(0), s(0, c = Command.TRIM),
            s(0), s(0, c = Command.COLOR_CHANGE), s(0)))
        assertTrue(report.hasBlockingIssues)
        val finding = report.of(FindingCode.REPEATED_PENETRATION).single()
        assertEquals(Severity.BLOCKER, finding.severity)
        assertEquals(4.0, finding.measured)
        assertEquals(4.0, finding.threshold)
        assertEquals(5, finding.highlight!!.commandIndex)
        assertEquals(NeedlePoint(0.0, 0.0), finding.highlight.from)
        assertTrue(report.of(FindingCode.DENSITY_HOTSPOT).isEmpty())
        assertFalse(SewoutCheck.check(p(*Array(4) { s(0) }),
            options = SewoutOptions(repeatedPointThreshold = 5)).hasBlockingIssues)
    }

    @Test fun missingLocksWarnAtStartAndFirstNeedleAfterTrimWithoutConsumingTravelOrStops() {
        val report = SewoutCheck.check(p(s(0), s(30), s(30, c = Command.TRIM), s(30, c = Command.TRIM),
            s(100, c = Command.JUMP), s(100, c = Command.STOP), s(100), s(130),
            s(130, c = Command.TRIM), s(130, c = Command.END)))
        val warnings = report.of(FindingCode.MISSING_LOCK_STITCH)
        assertEquals(listOf(0, 6), warnings.map { it.highlight!!.commandIndex })
        assertEquals(listOf(1, 7), warnings.map { it.highlight!!.endCommandIndex })
        assertTrue(warnings.all { it.severity == Severity.WARNING })
        assertTrue(warnings[1].message.contains("트림 뒤"))
        assertEquals(NeedlePoint(100.0, 0.0), warnings[1].highlight!!.from)
        assertFalse(report.hasBlockingIssues)
        assertTrue(SewoutCheck.check(p(s(0, c = Command.TRIM))).of(FindingCode.MISSING_LOCK_STITCH).isEmpty())
    }

    @Test fun colorChangeAlsoRequiresAStartLockButPlainStopDoesNotStartANewCheck() {
        val report = SewoutCheck.check(p(s(0), s(30), s(30, c = Command.STOP), s(60),
            s(60, c = Command.COLOR_CHANGE), s(100, c = Command.JUMP), s(100), s(130)))
        val warnings = report.of(FindingCode.MISSING_LOCK_STITCH)
        assertEquals(listOf(0, 6), warnings.map { it.highlight!!.commandIndex })
        assertTrue(warnings.last().message.contains("색 교체 뒤"))
    }

    @Test fun compactBacktrackingAndTriangleLocksAreRecognizedAtBothStarts() {
        val report = SewoutCheck.check(p(s(0, c = Command.JUMP), s(0), s(0), s(10), s(0), s(10), s(40),
            s(40, c = Command.TRIM), s(100, c = Command.JUMP), s(100), s(110), s(105, 10), s(100), s(140)))
        assertTrue(report.of(FindingCode.MISSING_LOCK_STITCH).isEmpty())
        assertFalse(report.hasBlockingIssues)
    }

    @Test fun repeatedAnchorsTinyMovesLongBacktracksAndOrdinaryRunningAreNotStartLocks() {
        for (source in listOf(p(*Array(4) { s(0) }), p(s(0), s(2), s(0), s(2)),
            p(s(0), s(30), s(0), s(30)), p(s(0), s(3), s(6), s(9)),
            p(s(0), s(10), s(10, c = Command.JUMP), s(0), s(10)))) {
            assertEquals(1, SewoutCheck.check(source).of(FindingCode.MISSING_LOCK_STITCH).size)
        }
    }

    @Test fun projectFlattenChecksEveryVisibleObjectStartEvenWithIdenticalThread() {
        val source = p(s(0), s(30))
        val first = StitchObject("first", "first", source)
        val second = first.copy(id = "second", transform = Transform(x = 100.0))
        val hidden = first.copy(id = "hidden", visible = false)
        val project = Project(objects = listOf(first, second, hidden))
        val flat = project.flatten()
        val report = SewoutCheck.check(project)
        assertEquals(0, report.statistics.colorChangeCount)
        val warnings = report.of(FindingCode.MISSING_LOCK_STITCH)
        assertEquals(2, warnings.size)
        for (warning in warnings) {
            val index = warning.highlight!!.commandIndex!!
            assertEquals(Command.STITCH, flat.stitches[index].command)
            assertEquals(flat.stitches[index].x.toDouble(), warning.highlight.from!!.x)
        }
        assertTrue(warnings.last().message.contains("트림 뒤"))
    }

    @Test fun syntheticFanSatinRemainsBlockedAfterPesRoundTripForEveryProfile() {
        val needles = mutableListOf(s(0))
        repeat(9) { needles += s(60, (it - 4) * 10); needles += s(0) }
        val bytes = PesWriter.writeV6(p(*needles.toTypedArray()))
        for (machine in SewoutMachines.all) {
            val report = SewoutCheck.checkPes(bytes, machine)
            assertTrue(report.analysisComplete)
            assertTrue(report.hasBlockingIssues)
            assertEquals(Severity.BLOCKER, report.of(FindingCode.DENSITY_HOTSPOT).single().severity)
            assertEquals(1, report.of(FindingCode.REPEATED_PENETRATION).size)
            assertEquals(1, report.of(FindingCode.MISSING_LOCK_STITCH).size)
            assertTrue(report.density.maxOf { it.penetrations } >= 10)
        }
    }

    @Test fun densityAndRepeatBlocksSurviveFindingSuppression() {
        val report = SewoutCheck.check(p(*Array(38) { s(0) }), options = SewoutOptions(maxFindings = 1))
        assertTrue(report.analysisComplete)
        assertTrue(report.hasBlockingIssues)
        assertEquals(1, report.findings.size)
        assertEquals(Severity.BLOCKER, report.findings.single().severity)
        assertEquals(1, report.findingCounts[FindingCode.DENSITY_HOTSPOT])
        assertEquals(1, report.findingCounts[FindingCode.REPEATED_PENETRATION])
        assertEquals(1, report.findingCounts[FindingCode.MISSING_LOCK_STITCH])
        assertTrue(report.suppressedFindings > 0)
    }
}
