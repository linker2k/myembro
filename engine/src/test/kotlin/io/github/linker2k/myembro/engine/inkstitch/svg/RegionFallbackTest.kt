// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg

import io.github.linker2k.myembro.engine.inkstitch.elements.Stroke
import io.github.linker2k.myembro.engine.inkstitch.utils.Point
import io.github.linker2k.myembro.engine.model.EmbThread
import kotlin.test.*

class RegionFallbackTest {
    private fun group()=Stroke(listOf(listOf(Point(0.0,0.0),Point(40.0,0.0))),EmbThread(0xff000000.toInt())).toStitchGroups(null)
    @Test fun satinNullThenTatamiGateFailureFallsBackToRunning() {
        val calls=mutableListOf<StitchTypeRule.Type>();var checks=0
        val result=StitchTypeRule.generateRegion(StitchTypeRule.Type.SATIN,validate={ ++checks>1 }) { type ->
            calls+=type
            if(type==StitchTypeRule.Type.SATIN) requireNotNull(null) { "convert-to-satin returned null" }
            group()
        }
        assertEquals(listOf(StitchTypeRule.Type.SATIN,StitchTypeRule.Type.TATAMI,StitchTypeRule.Type.RUNNING),calls)
        assertEquals(StitchTypeRule.Type.RUNNING,result.type)
        assertFalse(result.excluded)
    }
    @Test fun allGeneratorsFailOnlyExcludesTheirRegionAndNextOneStillWorks() {
        val failed=StitchTypeRule.generateRegion(StitchTypeRule.Type.SATIN) { error("unsupported region") }
        assertTrue(failed.excluded);assertTrue(failed.groups.isEmpty())
        val good=StitchTypeRule.generateRegion(StitchTypeRule.Type.RUNNING) { group() }
        assertFalse(good.excluded);assertTrue(good.groups.isNotEmpty())
    }
    @Test fun cancellationDoesNotGetReportedAsAnExcludedRegion() {
        assertFailsWith<InterruptedException> { StitchTypeRule.generateRegion(StitchTypeRule.Type.SATIN) { throw InterruptedException() } }
        assertFailsWith<java.util.concurrent.CancellationException> { StitchTypeRule.generateRegion(StitchTypeRule.Type.SATIN) { throw java.util.concurrent.CancellationException() } }
    }
}
