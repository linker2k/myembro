// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor.block

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.edit.Project
import io.github.linker2k.myembro.engine.edit.ShapeObject
import io.github.linker2k.myembro.engine.edit.commands.CommandError
import io.github.linker2k.myembro.engine.edit.commands.ProjectCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockRunnerTest {
    private val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><rect x="0" y="0" width="100" height="100" fill="#3366CC"/>""" +
        """<circle cx="50" cy="50" r="20" fill="#FFCC00"/></svg>"""
    private fun project() = Project(hoop = HoopSpec.H100).add(ShapeObject.fromSvg("o", "two", svg, 40.0))
    private fun region(p: Project) = (p.find("o") as ShapeObject).regions.first().id
    private suspend fun BlockRunner.settle() = withTimeout(60_000) { while (status is BlockStatus.Generating || status is BlockStatus.Checking) yield() }

    @Test fun cancelStopsALongGenerationAndCommitsNothing() = runBlocking {
        // ~16 s uncancelled on the JVM (BlockApplyTest measurement); cancelled after 300 ms.
        val base = project()
        var now = 0L
        val runner = BlockRunner(this, Dispatchers.Default, clock = { now })
        var committed: Project? = null
        val patch = BlockApply.patch(BlockStitchDraft(texture = BlockTexture.PATTERN)).copy(meander_scale_percent = listOf(10.0, 10.0))
        runner.apply(base, ProjectCommand.SetBlockStitch("o", setOf(region(base)), patch), { base }, { committed = it; true })
        val generating = runner.status as BlockStatus.Generating
        now = 12_345; assertEquals(12_345L, runner.elapsedMs(generating))
        Thread.sleep(300)
        runner.cancelApply()
        assertEquals(BlockStatus.Cancelled, runner.status)
        Thread.sleep(1000); yield()
        assertEquals(BlockStatus.Cancelled, runner.status)
        assertEquals(null, committed)
    }

    @Test fun appliedResultIsCommittedOnceAndPasses() = runBlocking {
        val base = project()
        val runner = BlockRunner(this, Dispatchers.Default)
        val commits = mutableListOf<Project>()
        val patch = BlockApply.patch(BlockStitchDraft(texture = BlockTexture.PATTERN, autoAngle = false, angleDeg = 135))
        runner.apply(base, ProjectCommand.SetBlockStitch("o", setOf(region(base)), patch), { base }, { commits += it; true })
        runner.settle()
        assertEquals(BlockStatus.Passed, runner.status); assertEquals(1, commits.size)
    }

    @Test fun aChangedDesignOrRefusalCommitsNothing() = runBlocking {
        val base = project()
        val runner = BlockRunner(this, Dispatchers.Default)
        var committed = false
        val patch = BlockApply.patch(BlockStitchDraft(texture = BlockTexture.CONTOUR))
        runner.apply(base, ProjectCommand.SetBlockStitch("o", setOf(region(base)), patch), { project() }, { committed = true; true })
        runner.settle()
        assertEquals(BlockFailure.Kind.DESIGN_CHANGED, (runner.status as BlockStatus.Blocked).failure.kind); assertFalse(committed)
        runner.apply(base, ProjectCommand.SetBlockStitch("o", setOf("nope"), patch), { base }, { committed = true; true })
        runner.settle()
        val refused = runner.status as BlockStatus.Blocked
        assertTrue(refused.applyFailed); assertEquals(BlockFailure.Kind.SETTINGS_INVALID, refused.failure.kind); assertFalse(committed)
    }

    @Test fun engineTextIsClassifiedAndKeptOnlyAsDetail() {
        fun kind(m: String) = BlockFailure.of(CommandError.SEWOUT_BLOCKED, m).kind
        assertEquals(BlockFailure.Kind.MEANDER_REGION_TOO_SMALL, kind("Could not build graph for meander stitching; enlarge the shape or reduce pattern scale"))
        assertEquals(BlockFailure.Kind.MEANDER_TOO_MANY_REPEATS, kind("Meander tile repetition resource limit exceeded"))
        assertEquals(BlockFailure.Kind.SATIN_UNSUPPORTED, kind("SATIN_GEOMETRY_UNSUPPORTED"))
        assertEquals(BlockFailure.Kind.NO_STITCHES, kind("EMPTY_REGION"))
        assertEquals(BlockFailure.Kind.UNSUPPORTED_METHOD, kind("LINEAR_GRADIENT_REQUIRES_COLOR_STOPS: block gradients need…"))
        val sew = BlockFailure.of(CommandError.SEWOUT_BLOCKED, "DENSITY_HOTSPOT: 0.5mm 칸에 18회; LONG_STITCH: 12.4mm; DENSITY_HOTSPOT: x")
        assertEquals(BlockFailure.Kind.SEWOUT_BLOCKED, sew.kind)
        assertEquals(listOf("DENSITY_HOTSPOT", "LONG_STITCH"), sew.sewoutCodes)
        assertEquals("SEWOUT_BLOCKED: DENSITY_HOTSPOT: 0.5mm 칸에 18회; LONG_STITCH: 12.4mm; DENSITY_HOTSPOT: x", sew.detail)
        assertEquals(listOf("HOOP_SAFETY_MARGIN"), BlockFailure.sewout("HOOP_SAFETY_MARGIN: placed design exceeds integer hoop indices 0..size-1").sewoutCodes)
        assertEquals(emptyList<String>(), BlockFailure.sewout("something unexpected").sewoutCodes)
        assertEquals(BlockFailure.Kind.SETTINGS_INVALID, BlockFailure.of(CommandError.INVALID_BLOCK_SETTINGS, "UNKNOWN_REGION").kind)
    }

    @Test fun theRealSmallRegionRefusalIsAMeanderSizeFailure() = runBlocking {
        // The butterfly spot case from 5560, reproduced: meander on a 4mm circle.
        val small = Project(hoop = HoopSpec.H100).add(ShapeObject.fromSvg("o", "dot",
            """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><circle cx="5" cy="5" r="5" fill="#333333"/></svg>""", 4.0))
        val runner = BlockRunner(this, Dispatchers.Default)
        runner.apply(small, ProjectCommand.SetBlockStitch("o", setOf(region(small)), BlockApply.patch(BlockStitchDraft(texture = BlockTexture.PATTERN))),
            { small }, { true })
        runner.settle()
        val status = runner.status
        assertTrue(status.toString(), status is BlockStatus.Blocked)
        assertEquals(BlockFailure.Kind.MEANDER_REGION_TOO_SMALL, (status as BlockStatus.Blocked).failure.kind)
        assertSame(true, status.applyFailed)
    }
}
