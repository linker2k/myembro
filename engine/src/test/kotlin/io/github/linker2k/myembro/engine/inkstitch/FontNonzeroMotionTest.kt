// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch
import io.github.linker2k.myembro.engine.geometry.*
import io.github.linker2k.myembro.engine.inkstitch.lettering.*
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.model.*
import kotlin.test.*

class FontNonzeroMotionTest {
    private fun font(kind:String): InkStitchFont {
        val attrs=InkStitchParams(if(kind=="bean") mapOf("bean_stitch_repeats" to "1") else emptyMap())
        val rails=listOf(listOf(Point(0.0,0.0),Point(0.0,40.0)),listOf(Point(8.0,0.0),Point(8.0,40.0)))
        val element=when(kind) {
            "satin" -> InkSatin(rails,rails,0xFF000000.toInt(),attrs)
            "fill" -> InkFill(listOf(Polyline(listOf(Point(0.0,0.0),Point(30.0,0.0),Point(30.0,40.0),Point(0.0,40.0)),true)),FillRule.EVEN_ODD,0xFF000000.toInt(),attrs)
            else -> InkRun(rails.map { Polyline(it) },0xFF000000.toInt(),attrs)
        }
        return InkStitchFont(InkStitchFontInfo.parse("""{"name":"P4","reversible":false}"""),
            mapOf("A" to InkStitchGlyph("A",listOf(element),0.0,30.0,0.0,40.0)),0.0,emptyMap())
    }
    private fun assertNonzero(p:EmbPattern,label:String) {
        var x=0;var y=0;var stitches=0;var jumps=0
        for(s in p.stitches) if(s.command==Command.STITCH || s.command==Command.JUMP) {
            if(s.x==x && s.y==y) { if(s.command==Command.STITCH) stitches++ else jumps++ }
            x=s.x;y=s.y
        }
        println("P4 $label zero_needles=$stitches zero_jumps=$jumps")
        assertEquals(0,stitches,"$label zero needle after any motion (including JUMP)")
        assertEquals(0,jumps,"$label zero jump")
    }
    @Test fun productElementsNeverPierceJumpArrivalAgain() {
        for(kind in listOf("running","bean","satin","fill")) {
            val p=InkStitchLettering.render(font(kind),"AA",10.0)
            assertNonzero(p,kind)
            assertNonzero(PesReader.read(PesWriter.write(p)),"$kind PES")
        }
    }
    @Test fun samePointTrimRestartContinuesWithoutTrimOrJump() {
        fun stitch(x:Double)=io.github.linker2k.myembro.engine.inkstitch.stitch_plan.Stitch(x,0.0)
        val groups=listOf(
            io.github.linker2k.myembro.engine.inkstitch.stitch_plan.StitchGroup(EmbThread(0),listOf(stitch(1.0),stitch(20.0)),trimAfter=true),
            io.github.linker2k.myembro.engine.inkstitch.stitch_plan.StitchGroup(EmbThread(0),listOf(stitch(20.0),stitch(40.0))))
        val p=GeneratedPatternProcessor.fromGroups(groups,pecJumpTrims=true,
            lockAtThreadBoundaries=true,omitZeroLengthMotions=true)
        assertEquals(0,p.trimCount)
        assertNonzero(p,"same-point restart")
        assertNonzero(PesReader.read(PesWriter.write(p)),"same-point restart PES")
    }

    @Test fun rebuildingAfterScaleRetainsP4AndLockProvenance() {
        val p=InkStitchLettering.render(font("satin"),"AA",10.0)
        val scaled=GeneratedPatternProcessor.mapCoordinates(p) { x,y -> (x*2) to (y*2) }
        val rebuilt=GeneratedPatternProcessor.finish(scaled,rebuild=true)
        assertNonzero(rebuilt,"scaled rebuilt")
        val locks=rebuilt.metadata.getValue(GeneratedPatternProcessor.LOCK_INDICES_KEY).split(',').mapNotNull(String::toIntOrNull)
        assertTrue(locks.isNotEmpty());assertTrue(locks.all { rebuilt.stitches[it].command==Command.STITCH })
        assertEquals(rebuilt.stitches,GeneratedPatternProcessor.finish(rebuilt).stitches)
    }
}
