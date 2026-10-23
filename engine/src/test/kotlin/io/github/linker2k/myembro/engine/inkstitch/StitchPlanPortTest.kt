// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch

import io.github.linker2k.myembro.engine.geometry.Point
import io.github.linker2k.myembro.engine.inkstitch.stitches.InkRunning
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.*
import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.model.Stitch
import kotlin.test.*

/** S2 delegates locking to S1 rather than reproducing a machine-file byte pattern. */
class StitchPlanPortTest {
    @Test fun beanGoldenMatchesPinnedDefinition() {
        assertEquals(listOf(0.0,2.0,0.0,2.0,4.0,2.0,4.0),
            InkRunning.bean(listOf(Point(0.0,0.0), Point(2.0,0.0), Point(4.0,0.0))).map { it.x })
    }
    @Test fun nativeFinishingUsesSharedHalfStitchAndIsIdempotent() {
        val raw = EmbPattern(listOf(Stitch(0,0,Command.JUMP),Stitch(0,0,Command.STITCH),Stitch(20,0,Command.STITCH),
            Stitch(20,0,Command.TRIM),Stitch(50,0,Command.JUMP),Stitch(50,0,Command.STITCH),Stitch(70,0,Command.STITCH),Stitch(70,0,Command.END)), emptyList())
        val safe=GeneratedPatternProcessor.finish(raw)
        assertEquals(safe,GeneratedPatternProcessor.finish(safe))
        assertEquals(listOf(0,8,15,8,0,20,13,5,13,20), safe.stitches.filter { it.command==Command.STITCH }.take(10).map { it.x })
        assertTrue(safe.metadata.getValue(GeneratedPatternProcessor.LOCK_INDICES_KEY).isNotBlank())
        assertEquals(2,PatternStartAnalysis.starts(safe).size)
        assertFalse(safe.metadata.containsKey("myembro.stitch-plan"))
    }
}
