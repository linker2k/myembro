// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.*
import io.github.linker2k.myembro.engine.sewout.*
import kotlin.test.*

class ViewerSewoutTest {
    private fun pattern(x: Int = 0) = EmbPattern(listOf(Stitch(x, 0, Command.STITCH),
        Stitch(x + 50, 50, Command.STITCH), Stitch(x + 50, 50, Command.END)), listOf(EmbThread(0xFFAA5500.toInt(), "thread")))
    private fun report() = checkViewerSewout(pattern(), SewoutMachines.SE400, HoopSpec.H100, false)

    @Test fun `badge uses functional verdict and generated origin survives byte parsing`() {
        val p=pattern()
        val bytes=PesWriter.write(p)
        val imported=checkViewerSewout(p,SewoutMachines.SE400,HoopSpec.H100,false,bytes)
        val generated=checkViewerSewout(p,SewoutMachines.SE400,HoopSpec.H100,false,bytes,DesignOrigin.APP_GENERATED)
        assertEquals(SewoutVerdict.CAUTION,imported.verdict)
        assertEquals(io.github.linker2k.myembro.R.string.sewout_badge_caution,sewoutVerdictResource(imported))
        assertEquals(SewoutVerdict.BLOCK,generated.verdict)
        assertEquals(io.github.linker2k.myembro.R.string.sewout_badge_block,sewoutVerdictResource(generated))
        val pass=checkViewerSewout(EmbPattern(listOf(Stitch(0,0,Command.STITCH),Stitch(0,0,Command.STITCH),
            Stitch(40,0,Command.STITCH),Stitch(40,0,Command.STITCH),Stitch(40,0,Command.END)),p.threads),
            SewoutMachines.SE400,HoopSpec.H100,false)
        assertEquals(SewoutVerdict.PASS,pass.verdict)
        assertEquals(io.github.linker2k.myembro.R.string.sewout_badge_pass,sewoutVerdictResource(pass))
    }

    @Test fun `settings machine selects exact profile and unknown customs never inherit SE400 limits`() {
        assertSame(SewoutMachines.SE400, viewerSewoutMachine("se400", "Brother SE400"))
        assertSame(SewoutMachines.SE600, viewerSewoutMachine("se600", "Brother SE600"))
        assertSame(SewoutMachines.SE700, viewerSewoutMachine("se700", "Brother SE700"))
        val custom = viewerSewoutMachine(null, "My custom machine")
        assertNull(custom.maxWidthMm.value)
        assertNull(custom.maxStitches.value)
        assertNull(custom.maximumEmbroiderySpm.value)
        assertNull(custom.automaticJumpTrim.value)
        assertTrue(custom.knownHoopsMm.isEmpty())
        assertEquals("My custom machine", custom.name)
    }
    @Test fun `project placement remains fixed while imported patterns are centered`() {
        val p = pattern(600)
        val placed = checkViewerSewout(p, SewoutMachines.SE400, HoopSpec.H100, true)
        assertTrue(placed.hasBlockingIssues)
        assertTrue(placed.findings.any { it.code == FindingCode.HOOP_EXCEEDED })
        assertEquals(-500.0, placed.hoopWindow.minX)
        val centered = checkViewerSewout(p, SewoutMachines.SE400, HoopSpec.H100, false)
        assertFalse(centered.findings.any { it.code == FindingCode.HOOP_EXCEEDED })
        assertEquals(125.0, centered.hoopWindow.minX)
    }
    @Test fun `ordinary PES checks actual file but a project keeps original command coordinates`() {
        val p = pattern(100)
        val bytes = PesWriter.write(p)
        val file = checkViewerSewout(p, SewoutMachines.SE400, HoopSpec.H100, false, bytes)
        assertNotNull(file.pesFile)
        assertEquals(bytes.size, file.pesFile!!.byteCount)
        val project = checkViewerSewout(p, SewoutMachines.SE400, HoopSpec.H100, true, bytes)
        assertNull(project.pesFile)
        assertEquals(Placement.HOOP_COORDINATES, project.options.placement)
        assertEquals(100.0, project.statistics.bounds!!.minX)
    }
    @Test fun `unknown specifications stay unverified even with zero blockers`() {
        val report = report()
        assertFalse(report.hasBlockingIssues)
        assertEquals(Acceptance.UNVERIFIED, report.compatibility.acceptance)
        assertTrue(report.compatibility.checks.any { it.rule == CompatibilityRule.COLOR_COUNT && it.status == CompatibilityStatus.UNKNOWN })
        assertNull(report.speed.machineMaximumSpm.value)
        assertEquals(Sufficiency.UNKNOWN, report.threadUsage.oneBobbin)
        assertFalse(sewoutNeedsConfirmation(report))
    }
    @Test fun `blocker and incomplete analysis require confirmation including suppressed findings`() {
        val blocked = checkViewerSewout(pattern(600), SewoutMachines.SE400, HoopSpec.H100, true)
        assertTrue(sewoutNeedsConfirmation(blocked))
        assertTrue(sewoutNeedsConfirmation(blocked.copy(findings = emptyList(), suppressedFindings = blocked.findings.size)))
        assertTrue(sewoutNeedsConfirmation(report().copy(analysisComplete = false)))
    }
    @Test fun `operator steps and color estimates remain in source order and totals match`() {
        val p = EmbPattern(listOf(Stitch(0,0,Command.STITCH), Stitch(30,0,Command.STITCH),
            Stitch(150,0,Command.JUMP), Stitch(180,0,Command.STITCH), Stitch(180,0,Command.STOP),
            Stitch(180,0,Command.COLOR_CHANGE), Stitch(200,0,Command.STITCH), Stitch(220,0,Command.STITCH),
            Stitch(220,0,Command.END)), listOf(EmbThread(0xFFAA5500.toInt()), EmbThread(0xFF0055AA.toInt())))
        val r = checkViewerSewout(p, SewoutMachines.SE600, HoopSpec.H100, false)
        assertEquals(2, r.stages.size)
        assertTrue(r.tasks.any { it.kind == TaskKind.JUMP_THREAD_CUT && it.cutRequirement == CutRequirement.REQUIRED && it.operatorMustPause })
        assertTrue(r.tasks.any { it.kind == TaskKind.COLOR_CHANGE })
        assertTrue(r.tasks.any { it.kind == TaskKind.PROGRAM_STOP && it.machineStops })
        assertEquals(r.time.totalSeconds, r.stages.sumOf { it.time.totalSeconds }, 0.000001)
        assertEquals(r.threadUsage.upperMeters, r.stages.sumOf { it.threadUsage.upperMeters }, 0.000001)
        assertEquals(r.threadUsage.bobbinMeters, r.stages.sumOf { it.threadUsage.bobbinMeters }, 0.000001)
        assertEquals(r.tasks.map { it.ordinal }.sorted(), r.tasks.map { it.ordinal })
    }
    @Test fun `segment point area and command range highlights keep absolute coordinates`() {
        val p = pattern(600)
        assertEquals(CheckArea(610.0,20.0,640.0,40.0), viewerHighlightArea(p, Highlight(from = NeedlePoint(610.0,20.0), to = NeedlePoint(640.0,40.0))))
        assertEquals(CheckArea(600.0,0.0,650.0,50.0), viewerHighlightArea(p, Highlight(0,1)))
        val area = CheckArea(-10.0,-20.0,0.0,5.0)
        assertEquals(area, viewerHighlightArea(p, Highlight(area = area)))
        assertEquals(CheckArea(2.0,3.0,2.0,3.0), viewerHighlightArea(p, Highlight(from = NeedlePoint(2.0,3.0))))
    }
    @Test fun `global and invalid highlights have no invented location`() {
        val p = pattern()
        assertNull(viewerHighlightArea(p, Highlight()))
        assertNull(viewerHighlightArea(p, Highlight(commandIndex = 9999)))
        assertNull(viewerHighlightArea(p, Highlight(from = NeedlePoint(Double.NaN, 0.0))))
    }
    @Test fun `focus camera centers absolute target after normal and project canvas transforms`() {
        val area = CheckArea(600.0,20.0,650.0,50.0)
        for (center in listOf(0f, 625f)) {
            val camera = viewerFocusCamera(area, .8f, center, 0f, 800f, 600f)
            val screenX = 400f + camera.panX + (625f - center) * .8f * camera.zoom
            val screenY = 300f + camera.panY + 35f * .8f * camera.zoom
            assertEquals(400f, screenX, .01f)
            assertEquals(300f, screenY, .01f)
            assertTrue(camera.zoom in 1f..40f)
        }
    }
    @Test fun `summary rounds estimated minutes upward and retains zero`() {
        assertEquals(0, sewoutMinutes(0.0))
        assertEquals(1, sewoutMinutes(.2))
        assertEquals(1, sewoutMinutes(60.0))
        assertEquals(2, sewoutMinutes(60.1))
        assertEquals("0:00", sewoutClock(0.0))
        assertEquals("0:01", sewoutClock(.2))
        assertEquals("1:05", sewoutClock(65.0))
    }
    @Test fun `canvas hoop center follows motion including controls and ignores post END garbage`() {
        val p = EmbPattern(listOf(Stitch(-300,0,Command.JUMP), Stitch(0,0,Command.STITCH),
            Stitch(10,10,Command.STITCH), Stitch(100,10,Command.STOP), Stitch(100,10,Command.END),
            Stitch(99999,99999,Command.STITCH)), emptyList())
        val bounds = viewerMovementBounds(p)!!
        assertEquals(Bounds(-300,0,100,10), bounds)
        val r = checkViewerSewout(p, SewoutMachines.SE400, HoopSpec.H100, false)
        assertEquals((bounds.minX.toLong() + bounds.maxX) / 2.0, (r.hoopWindow.minX + r.hoopWindow.maxX) / 2)
    }
}
