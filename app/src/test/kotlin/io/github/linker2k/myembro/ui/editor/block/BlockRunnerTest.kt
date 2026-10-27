// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor.block

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.edit.Project
import io.github.linker2k.myembro.engine.edit.ShapeObject
import io.github.linker2k.myembro.engine.edit.BlockGenerationKind
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

    @Test fun typedEngineResultsDecideTheMessageNotTheirText() {
        // The same text under different codes/kinds gives different kinds: the message is never read.
        val text = "Could not build graph for meander stitching"
        fun gen(k: BlockGenerationKind?) = BlockFailure.of(CommandError.GENERATION_FAILED, text, k).kind
        assertEquals(BlockFailure.Kind.MEANDER_REGION_TOO_SMALL, gen(BlockGenerationKind.MEANDER_GRAPH))
        assertEquals(BlockFailure.Kind.MEANDER_FAILED, gen(BlockGenerationKind.MEANDER_GENERATION))
        assertEquals(BlockFailure.Kind.SATIN_UNSUPPORTED, gen(BlockGenerationKind.SATIN_GEOMETRY_UNSUPPORTED))
        assertEquals(BlockFailure.Kind.SATIN_TOO_LONG, gen(BlockGenerationKind.SATIN_STITCH_TOO_LONG))
        assertEquals(BlockFailure.Kind.NO_STITCHES, gen(BlockGenerationKind.EMPTY_REGION))
        assertEquals(BlockFailure.Kind.UNSUPPORTED_METHOD, gen(BlockGenerationKind.UNSUPPORTED_FILL_BACKEND))
        assertEquals(BlockFailure.Kind.GENERATION_FAILED, gen(BlockGenerationKind.OUTPUT_ENCODING))
        assertEquals(BlockFailure.Kind.GENERATION_FAILED, gen(null))
        assertEquals(BlockFailure.Kind.SEWOUT_BLOCKED, BlockFailure.of(CommandError.SEWOUT_BLOCKED, text).kind)
        assertEquals(BlockFailure.Kind.SETTINGS_INVALID, BlockFailure.of(CommandError.INVALID_BLOCK_SETTINGS, text).kind)
        // Every engine kind is mapped (the when is exhaustive; this guards new kinds being added silently).
        BlockGenerationKind.entries.forEach { gen(it) }
        val sew = BlockFailure.of(CommandError.SEWOUT_BLOCKED, "DENSITY_HOTSPOT: 0.5mm 칸에 18회; LONG_STITCH: 12.4mm; DENSITY_HOTSPOT: x")
        assertEquals(listOf("DENSITY_HOTSPOT", "LONG_STITCH"), sew.sewoutCodes)
        assertEquals("SEWOUT_BLOCKED: DENSITY_HOTSPOT: 0.5mm 칸에 18회; LONG_STITCH: 12.4mm; DENSITY_HOTSPOT: x", sew.detail)
        assertEquals("GENERATION_FAILED/MEANDER_GRAPH: $text", BlockFailure.of(CommandError.GENERATION_FAILED, text, BlockGenerationKind.MEANDER_GRAPH).detail)
        assertEquals(listOf("HOOP_SAFETY_MARGIN"), BlockFailure.sewout("HOOP_SAFETY_MARGIN: placed design exceeds integer hoop indices 0..size-1").sewoutCodes)
        assertEquals(emptyList<String>(), BlockFailure.sewout("something mentions LONG_STITCH mid-text").sewoutCodes)
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

class BlockRunnerSafetyTest {
    private val project = Project(hoop = HoopSpec.H100)
    private fun command() = ProjectCommand.SetBlockOrder("o", setOf("r"), io.github.linker2k.myembro.engine.edit.BlockOrderMove.FIRST)
    private suspend fun BlockRunner.settle() = withTimeout(10_000) { while (status is BlockStatus.Generating || status is BlockStatus.Checking) yield() }

    @Test fun errorsDuringGenerationKeepThePreviousSettingsInsteadOfCrashing() = runBlocking {
        for ((error, kind) in listOf(
            VerifyError("Verifier rejected class org.locationtech.jts.operation.relateng.RelatePointLocator") to BlockFailure.Kind.DEVICE_FAILED,
            NoClassDefFoundError("x") to BlockFailure.Kind.DEVICE_FAILED,
            ExceptionInInitializerError("x") to BlockFailure.Kind.DEVICE_FAILED,
            StackOverflowError() to BlockFailure.Kind.DEVICE_FAILED,
            OutOfMemoryError("meander") to BlockFailure.Kind.OUT_OF_MEMORY)) {
            var committed = false
            val runner = BlockRunner(this, Dispatchers.Default, reduce = { _, _ -> throw error })
            runner.apply(project, command(), { project }, { committed = true; true })
            runner.settle()
            val status = runner.status as BlockStatus.Blocked
            assertEquals(kind, status.failure.kind); assertTrue(status.applyFailed); assertFalse(committed)
            assertTrue(status.failure.detail.startsWith(error.javaClass.name))
        }
    }

    @Test fun theBadgeCheckSurvivesTheSameErrors() = runBlocking {
        val runner = BlockRunner(this, Dispatchers.Default, checker = { throw VerifyError("x") })
        runner.check(project); runner.settle()
        val status = runner.status as BlockStatus.Blocked
        assertEquals(BlockFailure.Kind.DEVICE_FAILED, status.failure.kind); assertFalse(status.applyFailed)
    }

    @Test fun aBrokenVmIsNotSwallowed() {
        assertEquals(null, BlockFailure.device(InternalError("vm")))
        assertEquals(null, BlockFailure.device(IllegalStateException("not an Error")))
    }
}

class BlockOrderTest {
    private val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><rect x="0" y="0" width="100" height="40" fill="#3366CC"/>""" +
        """<rect x="0" y="50" width="100" height="20" fill="#FFCC00"/><rect x="0" y="80" width="100" height="20" fill="#CC3366"/></svg>"""
    private suspend fun BlockRunner.settle() = withTimeout(60_000) { while (status is BlockStatus.Generating || status is BlockStatus.Checking) yield() }

    @Test fun orderMovesAreOneCommittedStepAndMultiSelectMovesTogether() = runBlocking {
        val base = Project(hoop = HoopSpec.H100).add(ShapeObject.fromSvg("o", "bars", svg, 60.0))
        val order = (base.find("o") as ShapeObject).regionOrder
        assertEquals(3, order.size)
        val runner = BlockRunner(this, Dispatchers.Default)
        val commits = mutableListOf<Project>()
        val pick = setOf(order[1], order[2])
        runner.apply(base, ProjectCommand.SetBlockOrder("o", pick, io.github.linker2k.myembro.engine.edit.BlockOrderMove.FIRST), { base }, { commits += it; true })
        runner.settle()
        assertEquals(BlockStatus.Passed, runner.status); assertEquals(1, commits.size)
        assertEquals(listOf(order[1], order[2], order[0]), (commits.single().find("o") as ShapeObject).regionOrder)
        val info = BlockOrderInfo.of((commits.single().find("o") as ShapeObject).regionOrder, pick)
        assertEquals(0, info.first); assertEquals(1, info.last); assertEquals(3, info.total)
        assertFalse(info.canMove(io.github.linker2k.myembro.engine.edit.BlockOrderMove.FIRST))
        assertFalse(info.canMove(io.github.linker2k.myembro.engine.edit.BlockOrderMove.FORWARD))
        assertTrue(info.canMove(io.github.linker2k.myembro.engine.edit.BlockOrderMove.LAST))
        assertTrue(info.canMove(io.github.linker2k.myembro.engine.edit.BlockOrderMove.BACKWARD))
        // Gapped selection can still move forward although it includes the first place.
        assertTrue(BlockOrderInfo.of(listOf("a", "b", "c"), setOf("a", "c")).canMove(io.github.linker2k.myembro.engine.edit.BlockOrderMove.FORWARD))
    }
}
