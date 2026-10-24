// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.stitch_plan
import io.github.linker2k.myembro.engine.inkstitch.utils.Point
import io.github.linker2k.myembro.engine.io.PesReader
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.model.EmbThread
import kotlin.test.*

class PecJumpTrimCompatibilityTest {
    @Test fun approvedBoundaryTiesAlsoCoverImplicitPecTrimRestarts() {
        val groups=listOf(0.0,50.0).map { x -> StitchGroup(EmbThread(0xFF000000.toInt()),
            listOf(Stitch(Point(x,0.0)),Stitch(Point(x+10,0.0)))) }
        val p=GeneratedPatternProcessor.fromGroups(groups,pecJumpTrims=true,lockAtThreadBoundaries=true)
        val locks=p.metadata.getValue(GeneratedPatternProcessor.LOCK_INDICES_KEY).split(',').map(String::toInt).toSet()
        val starts=p.stitches.indices.filter { p.stitches[it].command==Command.JUMP }.map { jump ->
            (jump+1 until p.stitches.size).first { p.stitches[it].command==Command.STITCH } }
        assertEquals(2,starts.size);assertTrue(starts.all { it in locks })
        assertEquals(1,p.trimCount)
    }

    @Test fun optInKeepsNeedlesAndLockProvenanceWhileMatchingSourceTrimJumps() {
        fun group(x:Double,trim:Boolean=false)=StitchGroup(EmbThread(0xFF000000.toInt()),
            listOf(Stitch(Point(x,0.0)),Stitch(Point(x+10,0.0))),trimAfter=trim,
            lockStart=InkLockStitch(),lockEnd=InkLockStitch())
        val groups=listOf(group(0.0),group(50.0))
        val raw=GeneratedPatternProcessor.fromGroups(groups)
        val compatible=GeneratedPatternProcessor.fromGroups(groups,pecJumpTrims=true)
        assertEquals(0,raw.trimCount)
        assertEquals(1,compatible.trimCount)
        assertEquals(raw.stitches.filter { it.command==Command.STITCH },compatible.stitches.filter { it.command==Command.STITCH })
        assertEquals(1,PesReader.read(PesWriter.write(compatible)).trimCount)
        val locks=compatible.metadata.getValue(GeneratedPatternProcessor.LOCK_INDICES_KEY).split(',').map(String::toInt)
        assertTrue(locks.isNotEmpty());assertTrue(locks.all { compatible.stitches[it].command==Command.STITCH })
        assertSame(compatible,GeneratedPatternProcessor.finish(compatible))
        assertEquals(1,GeneratedPatternProcessor.fromGroups(listOf(group(0.0,true),group(50.0)),pecJumpTrims=true).trimCount)
    }
}
