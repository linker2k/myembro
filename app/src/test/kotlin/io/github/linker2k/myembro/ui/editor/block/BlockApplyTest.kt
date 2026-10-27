// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor.block

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.edit.Project
import io.github.linker2k.myembro.engine.edit.ShapeObject
import io.github.linker2k.myembro.engine.edit.commands.CommandError
import io.github.linker2k.myembro.engine.edit.commands.CommandResult
import io.github.linker2k.myembro.engine.edit.commands.EditSession
import io.github.linker2k.myembro.engine.edit.commands.ProjectCommand
import io.github.linker2k.myembro.engine.edit.commands.SessionHistoryResult
import io.github.linker2k.myembro.engine.edit.commands.apply
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockApplyTest {
    private val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><rect x="0" y="0" width="100" height="100" fill="#3366CC"/>""" +
        """<circle cx="50" cy="50" r="20" fill="#FFCC00"/></svg>"""
    private fun project() = Project(hoop = HoopSpec.H100).add(ShapeObject.fromSvg("o", "two", svg, 40.0))
    private fun shape(p: Project) = p.find("o") as ShapeObject

    @Test fun patternAnd135DegreesMapToUpstreamFields() {
        val patch = BlockApply.patch(BlockStitchDraft(texture = BlockTexture.PATTERN, tile = "hexagon", autoAngle = false, angleDeg = 135))
        assertEquals("meander_fill", patch.fill_method); assertEquals("hexagon", patch.meander_pattern)
        assertEquals(false, patch.automatic_angle); assertEquals(135.0, patch.angle!!, 0.0)
        assertEquals(0.25, patch.row_spacing_mm!!, 0.0)          // NORMAL = upstream default
        assertNull(BlockApply.ROW_SPACING_MM[BlockDensity.DENSE]); assertNull(BlockApply.ROW_SPACING_MM[BlockDensity.SPARSE])
        val auto = BlockApply.patch(BlockStitchDraft())
        assertEquals(true, auto.automatic_angle); assertNull(auto.angle); assertNull(auto.meander_pattern)
    }

    @Test fun appliedSettingsReadBackIntoTheSheetAndCommitAsOneUndoStep() {
        val base = project()
        val region = shape(base).regions.first().id
        val draft = BlockStitchDraft(texture = BlockTexture.PATTERN, tile = "N3-11a", autoAngle = false, angleDeg = 135)
        val result = apply(base, ProjectCommand.SetBlockStitch("o", setOf(region), BlockApply.patch(draft)))
        assertTrue(result.toString(), result is CommandResult.Applied)
        assertEquals(draft, BlockApply.draft(shape(result.project).regions.first { it.id == region }.stitch))
        assertNull(BlockApply.check(result.project))
        val session = EditSession(base)
        assertTrue(session.commitSnapshot(result.project, session.revision) is SessionHistoryResult.Applied)
        assertEquals(1, session.snapshot().undoCount)
    }

    @Test fun aRefusedApplyKeepsThePreviousProjectAndGivesTheReason() {
        val base = project()
        val bad = apply(base, ProjectCommand.SetBlockStitch("o", setOf("no-such-region"), BlockApply.patch(BlockStitchDraft())))
        assertTrue(bad is CommandResult.Rejected)
        assertSame(base, bad.project)
        assertEquals(CommandError.INVALID_BLOCK_SETTINGS, (bad as CommandResult.Rejected).code)
        assertTrue(bad.message.isNotBlank())
    }

    @Test fun typedGenerationAndSewoutStatusDoesNotParseHumanMessages() {
        val p=Project()
        for(message in listOf("identical message", "후프 초과", "Could not build graph")) {
            val generated=CommandResult.Rejected(p,CommandError.GENERATION_FAILED,setOf("o"),emptyList(),message,
                io.github.linker2k.myembro.engine.edit.BlockGenerationKind.MEANDER_GRAPH)
            val status=BlockApply.rejectedStatus(generated)
            assertEquals(CommandError.GENERATION_FAILED,status.code)
            assertEquals(io.github.linker2k.myembro.engine.edit.BlockGenerationKind.MEANDER_GRAPH,status.kind)
            val blocked=BlockApply.rejectedStatus(generated.copy(code=CommandError.SEWOUT_BLOCKED,kind=null))
            assertEquals(CommandError.SEWOUT_BLOCKED,blocked.code);assertNull(blocked.kind)
        }
    }

    @Test fun cancellingInterruptsAMeanderGeneration() = runBlocking {
        // Measured on this fixture (JVM): meander at 10% tile scale takes ~16 s uncancelled (5% is refused at
        // once by the tile repetition limit, 40% takes ~0.4 s). Cancelled after 300 ms it must stop within 3 s.
        val base = project()
        val region = shape(base).regions.first().id
        val patch = BlockApply.patch(BlockStitchDraft(texture = BlockTexture.PATTERN, tile = "N3-11a")).copy(meander_scale_percent = listOf(10.0, 10.0))
        val job = async(Dispatchers.Default) { runInterruptible { apply(base, ProjectCommand.SetBlockStitch("o", setOf(region), patch)) } }
        Thread.sleep(300)
        assertTrue("finished before cancel", job.isActive)
        val start = System.nanoTime()
        job.cancel()
        withTimeout(3_000) { job.join() }
        assertTrue(job.isCancelled)
        println("CANCEL_STOPPED_MS ${(System.nanoTime() - start) / 1_000_000}")
    }
}
