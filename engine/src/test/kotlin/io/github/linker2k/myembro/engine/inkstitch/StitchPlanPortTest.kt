// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch

import io.github.linker2k.myembro.engine.geometry.Point
import io.github.linker2k.myembro.engine.inkstitch.stitches.InkRunning
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.*
import io.github.linker2k.myembro.engine.model.*
import io.github.linker2k.myembro.engine.model.Stitch
import kotlin.math.hypot
import kotlin.test.*

/** Temporary generated-pattern boundary pending worker's shared StitchPlan API. */
class StitchPlanPortTest {
    @Test fun beanAndLockGoldensMatchPinnedDefinitions() {
        val bean = InkRunning.bean(listOf(Point(0.0,0.0), Point(2.0,0.0), Point(4.0,0.0)))
        assertEquals(listOf(0.0,2.0,0.0,2.0,4.0,2.0,4.0), bean.map { it.x })
        val line = listOf(Point(0.0,0.0), Point(3.0,0.0))
        fun xs(kind: LockKind, end: Boolean = false) = InkLock.points(line, end, kind).map { it.x }
        assertEquals(listOf(0.0,0.75,1.5,0.75,0.0), xs(LockKind.HALF_STITCH))
        assertEquals(listOf(0.0,0.4,0.8,0.4), xs(LockKind.BACK_FORTH))
        assertEquals(listOf(0.0,0.4), xs(LockKind.COMPACT))
        xs(LockKind.COMPACT, true).zip(listOf(2.6,3.0)).forEach { (a,b) -> assertEquals(b,a,1e-9) }
    }
    @Test fun commonFinisherLocksAfterTrimsRemovesZeroAndSubMinimumAndRejectsHotspots() {
        val raw=EmbPattern(listOf(Stitch(0,0,Command.JUMP),Stitch(0,0,Command.STITCH),Stitch(1,0,Command.STITCH),Stitch(20,0,Command.STITCH),
            Stitch(20,0,Command.TRIM),Stitch(50,0,Command.JUMP),Stitch(50,0,Command.STITCH),Stitch(70,0,Command.STITCH),Stitch(70,0,Command.END)), listOf(EmbThread(0xFF000000.toInt())))
        val safe=StitchPlanFinisher.finish(raw)
        assertEquals(safe,StitchPlanFinisher.finish(safe))
        safe.stitches.zipWithNext().forEach { (a,b) -> if(b.command==Command.STITCH)assertTrue(hypot((b.x-a.x).toDouble(),(b.y-a.y).toDouble())>=3) }
        val starts=StitchPlanFinisher.starts(safe)
        assertEquals(2,starts.size);assertTrue(starts.all { it.shortStitches==0 })
        val first=safe.stitches.take(3)
        assertEquals(listOf(Command.JUMP,Command.STITCH,Command.STITCH),first.map{it.command})
        assertEquals(listOf(0,4,0),first.map{it.x})
        val crowd=EmbPattern(listOf(Stitch(0,0,Command.JUMP))+(0..30).map { Stitch(if(it%2==0)0 else 4,0,Command.STITCH) }, listOf(EmbThread(0xFF000000.toInt())))
        assertFailsWith<UnsafeStitchPlanException>{StitchPlanFinisher.finish(crowd)}
    }
}
