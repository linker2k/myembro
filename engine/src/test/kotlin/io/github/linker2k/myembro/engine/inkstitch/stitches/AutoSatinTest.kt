// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.stitches
import io.github.linker2k.myembro.engine.inkstitch.elements.utils.StrokeToSatin
import io.github.linker2k.myembro.engine.inkstitch.utils.Point
import io.github.linker2k.myembro.engine.model.EmbThread
import kotlin.test.*

/** Analytic source-contract tests, not official Ink/Stitch execution equivalence. */
class AutoSatinTest {
    private val color=EmbThread(0xff000000.toInt(),"Black")
    private fun section(path:List<Point>)=StrokeToSatin.filteredRungs(requireNotNull(StrokeToSatin.convertPathToSatin(path,4.0)))
    @Test fun straightColumnCoversWholeSatinOnce() {
        val result=AutoSatin.route(listOf(section(listOf(Point(0.0,0.0),Point(40.0,0.0)))),color)
        assertEquals(1,result.operations.size)
        assertFalse(result.operations.single().underpath)
        assertEquals(0.0,result.operations.single().start);assertEquals(1.0,result.operations.single().end)
        assertEquals(0,result.jumpEdges);assertTrue(result.groups.all { !it.trimAfter && !it.forceLockStitches })
    }
    @Test fun branchTraversalsRunBeforeCoveringSatinAndNoInputMutation() {
        val sections=listOf(section(listOf(Point(0.0,0.0),Point(40.0,0.0))),section(listOf(Point(20.0,0.0),Point(20.0,25.0))))
        val snapshot=sections.toList();val result=AutoSatin.route(sections,color)
        assertTrue(result.operations.any { it.underpath })
        for((i,op) in result.operations.withIndex())if(op.underpath) {
            assertTrue(result.operations.drop(i+1).any { !it.underpath && it.element==op.element && it.start<=op.start && it.end>=op.end })
        }
        assertEquals(snapshot,sections)
        assertTrue(result.groups.isNotEmpty());assertTrue(result.groups.all { !it.trimAfter })
    }
    @Test fun threeInkComponentsSewWithoutInternalJumpAndLockEveryBoundary() {
        val inputs=listOf(
            listOf(section(listOf(Point(0.0,0.0),Point(40.0,0.0))),section(listOf(Point(20.0,0.0),Point(20.0,25.0)))),
            listOf(section(listOf(Point(0.0,60.0),Point(40.0,60.0)))),
            listOf(section(listOf(Point(100.0,0.0),Point(140.0,0.0)))))
        val groups=mutableListOf<io.github.linker2k.myembro.engine.inkstitch.stitch_plan.StitchGroup>()
        for(input in inputs) {
            if(groups.isNotEmpty())groups[groups.lastIndex]=groups.last().copy(trimAfter=true)
            val result=AutoSatin.route(input,color)
            assertEquals(0,result.discardedSatins)
            groups+=result.groups
        }
        val generated=io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor.fromGroups(
            groups,pecJumpTrims=true,lockAtThreadBoundaries=true,omitZeroLengthMotions=true)
        val bytes=io.github.linker2k.myembro.engine.io.PesWriter.write(generated)
        val decoded=io.github.linker2k.myembro.engine.io.PesReader.read(bytes)
        val jumps=decoded.stitches.count { it.command==io.github.linker2k.myembro.engine.model.Command.JUMP }
        assertEquals(2,jumps,"Only two component transitions; the initial origin JUMP is omitted by P4")
        val viewport=io.github.linker2k.myembro.engine.sewout.PecViewportCheck.check(bytes,io.github.linker2k.myembro.engine.edit.HoopSpec.H100)
        assertTrue(viewport.valid);assertEquals(0,viewport.minX);assertEquals(0,viewport.minY)
        var x=0;var y=0
        for(stitch in decoded.stitches)if(stitch.command in setOf(io.github.linker2k.myembro.engine.model.Command.STITCH,io.github.linker2k.myembro.engine.model.Command.JUMP)) {
            assertTrue(stitch.x!=x || stitch.y!=y,"No zero-length physical motion")
            x=stitch.x;y=stitch.y
        }
        assertTrue(generated.metadata.getValue(io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor.LOCK_INDICES_KEY).isNotBlank())
    }
}
