// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

import io.github.linker2k.myembro.engine.edit.commands.*
import io.github.linker2k.myembro.engine.inkstitch.svg.image.ColorDrawingPipeline
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.sewout.*
import java.io.File
import kotlin.test.*

/** "Empty" block type (user decision 2026-11-01): the block stays, sews nothing, and can be filled again. */
class BlockEmptyTest {
    private fun heart() = ShapeObject.fromSvg("heart", "heart", File("src/test/resources/emoji/fluent/red_heart_flat.svg").readText(), 30.0)

    /** Two touching squares in different colours, through the colour-picture route. */
    private fun photo(): ShapeObject {
        val w = 160; val h = 80
        val pixels = IntArray(w * h) { i -> val x = i % w; val y = i / w
            when { y !in 10..69 -> 0xffffffff.toInt(); x in 10..79 -> 0xffdd2222.toInt(); x in 80..149 -> 0xff2233cc.toInt(); else -> 0xffffffff.toInt() } }
        val preview = ColorDrawingPipeline.traceRegions(w, h, pixels, 60.0, 2)
        return assertNotNull(ShapeObjectFactory.fromPhoto("photo", "photo", ColorDrawingPipeline.generate(preview), 60.0))
    }

    private fun empty(p: Project, ids: Set<String>) =
        apply(p, ProjectCommand.SetBlockStitch(p.objects.single().id, ids, BlockStitchPatch(type = BlockStitch.Type.EMPTY)))

    private fun sewn(p: Project) = p.flatten().stitches.count { it.command == Command.STITCH }

    private fun checkEmptiedOneRegion(original: ShapeObject) {
        val p = Project(objects = listOf(original))
        val target = original.regions.first()
        val applied = assertIs<CommandResult.Applied>(empty(p, setOf(target.id)))
        val shape = assertIs<ShapeObject>(applied.project.objects.single())
        // The block stays and reports EMPTY; the others keep their stitches.
        assertEquals(original.regions.map { it.id }, shape.regions.map { it.id })
        assertEquals(BlockStitch.Type.EMPTY, shape.regions.first().stitch.type)
        assertEquals(0, shape.regionPattern(target.id).stitches.count { it.command == Command.STITCH })
        assertTrue(sewn(applied.project) in 1 until sewn(p))
        // Its thread leaves the thread list when no other block uses it.
        if (original.regions.drop(1).none { it.color.sameColor(target.color) })
            assertTrue(applied.project.flatten().threads.none { it.sameColor(target.color) })
        // Saved and reopened identically; the file passes the sewing check.
        val restored = ProjectJson.decode(ProjectJson.encode(applied.project))
        assertEquals(applied.project, restored)
        assertEquals(applied.project.flatten().stitches, restored.flatten().stitches)
        val report = SewoutCheck.checkPes(PesWriter.write(applied.project.flatten()), options = SewoutOptions(origin = DesignOrigin.APP_GENERATED))
        assertFalse(report.hasBlockingIssues)
        // Choosing the original type again restores the original stitches.
        val back = assertIs<CommandResult.Applied>(apply(applied.project, ProjectCommand.SetBlockStitch(original.id, setOf(target.id),
            BlockStitchPatch(type = target.defaultStitch.type))))
        assertEquals(p.flatten().stitches, back.project.flatten().stitches)
    }

    @Test fun vectorBlockEmptiesAndRestores() = checkEmptiedOneRegion(heart())

    @Test fun photoBlockEmptiesAndRestores() = checkEmptiedOneRegion(photo())

    /** Emptying every block of the only object leaves no stitches: the sewing check refuses it and nothing changes. */
    @Test fun emptyingEveryBlockOfTheOnlyObjectIsRefused() {
        for (shape in listOf(heart(), photo())) {
            val p = Project(objects = listOf(shape))
            val rejected = assertIs<CommandResult.Rejected>(empty(p, shape.regions.map { it.id }.toSet()))
            assertEquals(CommandError.SEWOUT_BLOCKED, rejected.code)
            assertTrue("EMPTY_DESIGN" in rejected.message, rejected.message)
            assertSame(p, rejected.project)
        }
    }

    @Test fun emptyIsOneUndoStep() {
        val p = Project(objects = listOf(heart()))
        val session = EditSession(p)
        val id = (p.objects.single() as ShapeObject).regions.first().id
        assertIs<TurnResult.Committed>(session.executeTurn("empty", 0, listOf(ProjectCommand.SetBlockStitch("heart", setOf(id),
            BlockStitchPatch(type = BlockStitch.Type.EMPTY)))))
        val emptied = session.project
        assertNotEquals(p, emptied)
        session.undo(session.revision); assertEquals(p, session.project)
        session.redo(session.revision); assertEquals(emptied, session.project)
    }
}
