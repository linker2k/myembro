// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch

import io.github.linker2k.myembro.engine.inkstitch.elements.SatinColumn
import io.github.linker2k.myembro.engine.inkstitch.elements.SatinParameters
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.*
import io.github.linker2k.myembro.engine.inkstitch.svg.Units
import io.github.linker2k.myembro.engine.inkstitch.utils.Point
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.engine.model.Command
import kotlin.test.*

class SatinElementContractTest {
    private fun point(xMm:Double,yMm:Double)=Point(Units.mmToPixels(xMm),Units.mmToPixels(yMm))
    private val left=listOf(point(0.0,0.0),point(0.0,20.0))
    private val right=listOf(point(4.0,0.0),point(4.0,20.0))
    @Test fun svgCoordinatesTagsAndGroupSettingsSurviveSharedPatternAdapter() {
        val thread=EmbThread(0xFFB5492A.toInt(),"Rust")
        val lock=LockStitch { stitches,_->stitches.take(2).map { it.copy(tags=it.tags+"lock_stitch") } }
        val element=SatinColumn.fromSubpaths(listOf(left,right),SatinParameters(center_walk_underlay=true,min_stitch_length_mm=.1,min_jump_stitch_length_mm=3.0),color=thread,
            trim_after=true,stop_after=true,lockStart=lock,lockEnd=lock,force_lock_stitches=true)
        val group=element.toStitchGroups(null).single()
        assertSame(thread,group.color);assertTrue(group.trimAfter);assertTrue(group.stopAfter)
        assertSame(lock,group.lockStart);assertSame(lock,group.lockEnd);assertTrue(group.forceLockStitches)
        assertEquals(.1,group.minStitchLengthMm);assertEquals(3.0,group.minJumpLengthMm)
        assertTrue(group.stitches.any { "satin_center_walk" in it.tags })
        assertTrue(group.stitches.any { "satin_column_edge" in it.tags })
        assertTrue(group.stitches.none { it.isJump || it.isTrim || it.isEnd || it.isLock }) // Passive groups.
        assertEquals(2,group.lockStitches(LockPosition.START).size)
        val raw=EmbPatternAdapter.toEmbPattern(StitchPlan(listOf(ColorBlock(group.color,group.stitches))))
        assertEquals(40,raw.bounds!!.width);assertEquals(200,raw.bounds!!.height)
        assertEquals(Command.END,raw.stitches.last().command)
        assertTrue(group.stitches.maxOf { it.y }>Units.mmToPixels(19.9))
    }
    @Test fun previousStitchUsesSvgPixelsAndKeepsCenterEntryInsideTheColumn() {
        val group=SatinColumn(left,right).toStitchGroups(Stitch(point(2.0,7.0))).single()
        assertNull(group.minStitchLengthMm);assertNull(group.minJumpLengthMm)
        assertEquals(Units.mmToPixels(7.0),group.stitches.first().y,1e-5)
        assertEquals(Units.mmToPixels(2.0),group.stitches.first().x,1e-5)
    }
    @Test fun eAndZigzagPreserveTheirSourcePerNeedleTags() {
        val e=SatinColumn.generateLayers(left,right,SatinParameters(satin_method="e_stitch")).last()
        assertTrue("left" in e.stitchTags.first())
        val zig=SatinColumn.generateLayers(left,right,SatinParameters(satin_method="zigzag",max_stitch_length_mm=1.5,zigzag_underlay=true,zigzag_underlay_max_stitch_length_mm=1.5))
        assertTrue(zig.last().stitchTags.any { "peak_stitch" in it && "peak_a" in it })
        assertTrue(zig.last().stitchTags.any { "zigzag_split_stitch" in it })
        assertTrue(zig.dropLast(1).flatMap { it.stitchTags }.any { "split_stitch" in it })
    }

}
