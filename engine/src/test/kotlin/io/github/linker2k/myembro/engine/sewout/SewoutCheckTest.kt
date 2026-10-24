// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.sewout

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.model.*
import java.io.File
import kotlin.math.*
import kotlin.test.*

/** Only original synthetic command sequences and the repository's redistributable fixtures/assets. */
class SewoutCheckTest {
    private val red = EmbPattern.DEFAULT_PALETTE[1]
    private val blue = EmbPattern.DEFAULT_PALETTE[0]
    private fun s(x: Int, y: Int = 0, c: Command = Command.STITCH) = Stitch(x, y, c)
    private fun p(vararg stitches: Stitch) = EmbPattern(stitches.toList(), listOf(red, blue))
    private fun SewoutReport.count(code: FindingCode) = findingCounts[code] ?: 0
    private fun workflow() = p(s(-100, -100, Command.JUMP), s(-100, -100), s(0, -100),
        s(0, -100, Command.COLOR_CHANGE), s(0, 0), s(0, 0, Command.STOP), s(0, 10),
        s(0, 10, Command.TRIM), s(100, 10, Command.JUMP), s(100, 10), s(110, 10), s(110, 10, Command.END))

    @Test fun chronologicalTasksPreserveStopsAndDeduplicateTrimPlusJump() {
        val source = workflow()
        val report = SewoutCheck.check(source, SewoutMachines.SE600,
            SewoutOptions(plannedSpm = 600.0, colorCutSetting = SwitchSetting.DISABLED))
        assertEquals(6, report.statistics.stitchCount)
        assertEquals(2, report.statistics.jumpCount)
        assertEquals(1, report.statistics.trimCount)
        assertEquals(1, report.statistics.colorChangeCount)
        assertEquals(1, report.statistics.stopCount)
        assertEquals(2, report.stages.size)
        assertEquals(22.0, report.statistics.sewnLengthMm, 1e-8)
        assertEquals(listOf(TaskKind.COLOR_CHANGE, TaskKind.PROGRAM_STOP, TaskKind.JUMP_THREAD_CUT, TaskKind.FINISH), report.tasks.map { it.kind })
        assertEquals(listOf(3, 5, 7, 11), report.tasks.map { it.commandIndex })
        val cut = report.tasks.single { it.kind == TaskKind.JUMP_THREAD_CUT }
        assertEquals(NeedlePoint(0.0, 10.0), cut.from)
        assertEquals(NeedlePoint(100.0, 10.0), cut.to)
        assertFalse(cut.machineStops)
        assertTrue(cut.operatorMustPause)
        assertEquals(CutRequirement.REQUIRED, cut.cutRequirement)
        assertEquals(1, report.statistics.requiredManualCuts)
        assertEquals(57.0, report.time.totalSeconds, 1e-8)
        assertEquals(report.time.totalSeconds, report.stages.sumOf { it.time.totalSeconds }, 1e-8)
        assertEquals(0.1875, report.threadUsage.upperMeters, 1e-8)
        assertEquals(0.081, report.threadUsage.bobbinMeters, 1e-8)
        assertEquals(report.threadUsage.upperMeters, report.stages.sumOf { it.threadUsage.upperMeters }, 1e-8)
        assertTrue(report.tasks.zipWithNext().all { (a,b) -> a.atEstimatedSeconds <= b.atEstimatedSeconds })
        assertEquals(source.stitches, workflow().stitches)
    }

    @Test fun unknownJumpTrimmingIsConditionalAndNeverInventsAMachinePause() {
        val report = SewoutCheck.check(workflow(), SewoutMachines.SE400.copy(automaticJumpTrim=Specification(), manualJumpCleanup=Specification()))
        assertEquals(1, report.statistics.possibleManualCuts)
        assertEquals(0, report.statistics.requiredManualCuts)
        assertEquals(CutRequirement.POSSIBLE, report.tasks.single { it.kind == TaskKind.JUMP_THREAD_CUT }.cutRequirement)
        assertEquals(SpeedBasis.ASSUMED, report.speed.basis)
        assertNull(report.machine.maximumEmbroiderySpm.value)
        assertTrue(report.time.conditionalOperatorSeconds > 0)
        assertTrue(report.time.withoutConditionalCutsSeconds < report.time.totalSeconds)
        assertEquals(Sufficiency.UNKNOWN, report.threadUsage.oneBobbin)
        assertEquals(Acceptance.UNVERIFIED, report.compatibility.acceptance)
        assertTrue(report.count(FindingCode.PES_VERSION_UNCONFIRMED) > 0)
    }

    @Test fun explicitAutomaticCutCapabilityAndSettingsChangeTimeTasksAndThreadWaste() {
        val machine = SewoutMachines.SE600.copy(automaticJumpTrim = Specification(true, "Synthetic test capability"))
        val report = SewoutCheck.check(workflow(), machine, SewoutOptions(plannedSpm = 600.0,
            jumpTrimSetting = SwitchSetting.ENABLED, colorCutSetting = SwitchSetting.ENABLED, availableBobbinMeters = 1.0))
        assertEquals(0, report.statistics.possibleManualCuts)
        assertEquals(0, report.statistics.requiredManualCuts)
        assertTrue(report.tasks.filter { it.cutRequirement != null }.all { it.cutRequirement == CutRequirement.AUTOMATIC })
        assertFalse(report.tasks.single { it.kind == TaskKind.JUMP_THREAD_CUT }.operatorMustPause)
        assertEquals(3.0, report.time.automaticCutSeconds, 1e-8)
        assertEquals(44.0, report.time.totalSeconds, 1e-8)
        assertEquals(0.1775, report.threadUsage.upperMeters, 1e-8)
        assertEquals(Sufficiency.ENOUGH, report.threadUsage.oneBobbin)
        val insufficient = SewoutCheck.check(workflow(), options = SewoutOptions(availableBobbinMeters = 0.0))
        assertEquals(Sufficiency.INSUFFICIENT, insufficient.threadUsage.oneBobbin)
        assertEquals(1, insufficient.count(FindingCode.BOBBIN_INSUFFICIENT))
    }

    @Test fun completedTrimBeforeStopDoesNotCreateAnotherCutForAnUnthreadedJump() {
        val report = SewoutCheck.check(p(s(0), s(20), s(20, c=Command.TRIM), s(20, c=Command.STOP),
            s(100, c=Command.JUMP), s(100), s(110)), SewoutMachines.SE600)
        assertEquals(1, report.statistics.requiredManualCuts)
        assertEquals(listOf(TaskKind.TRIM, TaskKind.PROGRAM_STOP, TaskKind.FINISH), report.tasks.map { it.kind })
        assertEquals(0.0, report.stages.single().uncutJumpLengthMm)
    }

    @Test fun stitchThresholdsUseEuclideanLengthAndIgnoreInitialAnchors() {
        val report = SewoutCheck.check(p(s(0), s(121), s(123), s(126), s(248)))
        assertEquals(2, report.count(FindingCode.LONG_STITCH))
        assertEquals(1, report.count(FindingCode.SHORT_STITCH))
        assertEquals(listOf(1,4), report.findings.filter { it.code == FindingCode.LONG_STITCH }.map { it.highlight!!.commandIndex })
        assertEquals(listOf(12.1,12.2), report.findings.filter { it.code == FindingCode.LONG_STITCH }.map { it.measured })
        assertEquals(0, SewoutCheck.check(p(s(100, c=Command.JUMP), s(100))).count(FindingCode.SHORT_STITCH))
        val diag = SewoutCheck.check(p(s(0), s(90,90)))
        assertEquals(1, diag.count(FindingCode.LONG_STITCH))
        val machine = SewoutMachines.SE400.copy(maximumStitchMm = Specification(1.0, "Synthetic limit"))
        assertEquals(1, SewoutCheck.check(p(s(0), s(11)), machine).count(FindingCode.MACHINE_STITCH_LENGTH_EXCEEDED))
    }

    @Test fun needleDensityUsesFloorForNegativesAndSeparatesThreadPassesFromPunctures() {
        val report = SewoutCheck.check(p(s(-1,-1), s(-1,-1), *Array(12) { s(0) }))
        assertEquals(2, report.density.single { it.column == -1L && it.row == -1L }.penetrations)
        assertEquals(12, report.density.single { it.column == 0L && it.row == 0L }.penetrations)
        assertEquals(0, report.statistics.densityHotspotCount)
        assertEquals(1, report.count(FindingCode.REPEATED_PENETRATION))
        val line = SewoutCheck.check(p(s(0), s(40)))
        assertEquals(2, line.density.sumOf { it.penetrations })
        assertEquals(8, line.density.sumOf { it.segmentPasses })
        assertEquals(4.0, line.density.sumOf { it.sewnLengthMm }, 1e-8)
        assertTrue(SewoutCheck.check(p(s(-10,-10),s(10,10))).density.all { it.column == it.row })
    }

    @Test fun repeatedSegmentsAndLayerAccumulationRemainLocalized() {
        val report = SewoutCheck.check(p(s(0), s(40), s(0), s(0,c=Command.COLOR_CHANGE), s(0), s(40)),
            options = SewoutOptions(overlapPassThreshold = 3))
        assertTrue(report.count(FindingCode.OVERLAP_ACCUMULATION) > 0)
        assertTrue(report.statistics.overlapCellCount > 0)
        assertTrue(report.density.any { it.stageIndices == setOf(0,1) })
        assertTrue(report.findings.filter { it.code == FindingCode.OVERLAP_ACCUMULATION }.all { it.highlight != null })
    }

    @Test fun projectChecksPlacementWithoutRecenteringAndSelectedHoopDoesNotExpandMachine() {
        val objectA = StitchObject("a", "a", p(s(0),s(100,100)), Transform(x=600.0))
        val project = Project(objects = listOf(objectA))
        val report = SewoutCheck.check(project)
        assertTrue(report.hasBlockingIssues)
        assertTrue(report.count(FindingCode.HOOP_EXCEEDED) > 0)
        assertEquals(Acceptance.REJECTED, report.compatibility.acceptance)
        assertEquals(Placement.HOOP_COORDINATES, report.options.placement)
        assertEquals(0, SewoutCheck.check(objectA.placedPattern()).count(FindingCode.HOOP_EXCEEDED))
        val large = SewoutCheck.check(p(s(0),s(100,100)), options = SewoutOptions(hoop = HoopSpec("SA434 coverage",1000,1700)))
        assertTrue(large.count(FindingCode.MACHINE_AREA_EXCEEDED) > 0)
        val small = SewoutCheck.check(p(s(0),s(601)), options = SewoutOptions(hoop=HoopSpec("SA431",600,200)))
        assertTrue(small.count(FindingCode.HOOP_EXCEEDED) > 0)
    }

    @Test fun confirmedLimitsAreScopedAndUnknownVersionsNeverPassAsCertified() {
        assertEquals(30_000, SewoutMachines.SE400.maxStitches.value)
        assertNull(SewoutMachines.SE600.maxStitches.value)
        assertEquals(100_000, SewoutMachines.SE700.internalMemoryStitches.value)
        val machine = SewoutMachines.SE700.copy(internalMemoryStitches = Specification(3,"Synthetic internal limit"))
        val source = p(s(0),s(20),s(40),s(60))
        assertEquals(0, SewoutCheck.check(source,machine).count(FindingCode.STITCH_LIMIT_EXCEEDED))
        assertEquals(1, SewoutCheck.check(source,machine,SewoutOptions(destination=Destination.INTERNAL_MEMORY)).count(FindingCode.STITCH_LIMIT_EXCEEDED))
        val speed = SewoutCheck.check(source,SewoutMachines.SE700,SewoutOptions(plannedSpm=900.0)).speed
        assertEquals(400.0,speed.effectiveSpm)
        assertEquals(SpeedBasis.USER_SETTING,speed.basis)
        assertEquals(320.0,SewoutCheck.check(source,SewoutMachines.SE700).speed.effectiveSpm)
        assertEquals("미확인",SewoutMachines.SE600.automaticJumpTrim.statusLabel)
    }

    @Test fun actualPesVersionsCountsSizeAndThumbnailsAreCheckedWithoutChangingBytes() {
        val bytes = io.github.linker2k.myembro.engine.PolicyFixtureWriter.writeV6(p(s(0),s(10)))
        val original = bytes.copyOf()
        val report = SewoutCheck.checkPes(bytes)
        assertEquals("0060",report.pesFile!!.version)
        assertEquals(true,report.pesFile.pecThumbnailsPresent)
        assertEquals(Acceptance.UNVERIFIED,report.compatibility.acceptance)
        val limited = SewoutMachines.SE400.copy(acceptedPesVersions=Specification(setOf("0001"),"Synthetic versions"),
            maxFileBytes=Specification(10L,"Synthetic byte limit"), maxColors=Specification(1,"Synthetic color limit"))
        val blocked = SewoutCheck.checkPes(bytes,limited)
        assertEquals(Acceptance.REJECTED,blocked.compatibility.acceptance)
        assertEquals(1,blocked.count(FindingCode.FILE_SIZE_EXCEEDED))
        val twoColors = io.github.linker2k.myembro.engine.PolicyFixtureWriter.writeV6(p(s(1),s(2),s(2,c=Command.COLOR_CHANGE),s(20)))
        assertEquals(1,SewoutCheck.checkPes(twoColors,limited).count(FindingCode.COLOR_LIMIT_EXCEEDED))
        assertContentEquals(original,bytes)
        assertTrue(SewoutCheck.checkPes(byteArrayOf(1,2,3)).hasBlockingIssues)
        val truncated = SewoutCheck.checkPes(bytes.copyOf(30))
        assertTrue(truncated.hasBlockingIssues)
        assertFalse(truncated.analysisComplete)
    }

    @Test fun resourceLimitsAndFindingSuppressionNeverTurnAnIncompleteAnalysisIntoAPass() {
        val source = p(s(0),s(200,200),s(300,300))
        val partial = SewoutCheck.check(source,options=SewoutOptions(maxCommands=2,maxFindings=1))
        assertFalse(partial.analysisComplete)
        assertTrue(partial.hasBlockingIssues)
        assertTrue(partial.count(FindingCode.ANALYSIS_LIMIT)>0)
        assertTrue(partial.suppressedFindings>0)
        assertEquals(Severity.BLOCKER, partial.findings.single().severity)
        assertTrue(partial.tasks.none { it.kind==TaskKind.FINISH })
        val grid = SewoutCheck.check(source,options=SewoutOptions(maxGridCells=1,maxTracedCells=2))
        assertFalse(grid.analysisComplete)
        assertTrue(grid.hasBlockingIssues)
        val integerEdge = SewoutCheck.check(p(s(Int.MIN_VALUE),s(Int.MAX_VALUE)),options=SewoutOptions(maxTracedCells=5,maxGridCells=5))
        assertFalse(integerEdge.analysisComplete)
        assertEquals(429496729.5,integerEdge.statistics.sewnLengthMm,1e-6)
    }

    @Test fun emptyEndTerminationAndOptionsAreWellDefined() {
        val empty = SewoutCheck.check(EmbPattern(emptyList(),emptyList()))
        assertEquals(0,empty.statistics.stitchCount); assertTrue(empty.tasks.isEmpty())
        val report = SewoutCheck.check(p(s(0),s(0,c=Command.END),s(99999)))
        assertEquals(1,report.statistics.stitchCount); assertEquals(2,report.inputCommands)
        assertEquals(0,report.count(FindingCode.LONG_STITCH))
        val trailingColor = SewoutCheck.check(p(s(0),s(0,c=Command.COLOR_CHANGE)))
        assertEquals(1,trailingColor.count(FindingCode.EMPTY_COLOR_STAGE))
        assertTrue(trailingColor.stages.all { it.startCommandIndex <= it.endCommandIndex })
        assertFailsWith<IllegalArgumentException> { SewoutOptions(plannedSpm=0.0) }
        assertFailsWith<IllegalArgumentException> { SewoutOptions(gridMm=Double.NaN) }
        assertFailsWith<IllegalArgumentException> { SewoutOptions(gridMm=1e-100) }
        assertFailsWith<IllegalArgumentException> { SewoutOptions(availableBobbinMeters=-1.0) }
    }

    @Test fun repositoryRedistributableSamplesProduceCompleteDeterministicReports() {
        val samples = File("../app/src/main/assets/samples").listFiles()!!.filter { it.extension=="pes" }
        assertTrue(samples.size>=20)
        for (file in samples) for (machine in SewoutMachines.all) {
            val bytes = file.readBytes()
            val report = SewoutCheck.checkPes(bytes,machine)
            assertTrue(report.analysisComplete,"${file.name}: incomplete")
            assertEquals(PecViewportCheck.check(bytes,report.options.hoop).errors.size,report.count(FindingCode.INVALID_PES),file.name)
            assertTrue(report.statistics.stitchCount>0,file.name)
            assertEquals(report.statistics.stitchCount,report.stages.sumOf { it.stitchCount })
        }
        val fixture = javaClass.getResource("/fixtures/flower.v6.pes")!!.readBytes()
        assertEquals(SewoutCheck.checkPes(fixture),SewoutCheck.checkPes(fixture))
    }
}
