// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.photo

import kotlin.test.*

class PhotoGenerationTimeoutTest {
    @Test fun oneMinuteBoundaryWithFakeClock() {
        assertEquals(60_000L, PHOTO_GENERATION_TIMEOUT_MS)
        val start = 1_000_000L
        assertFalse(photoGenerationTimedOut(start, start + 59_900))
        assertFalse(photoGenerationTimedOut(start, start + 59_999))
        assertTrue(photoGenerationTimedOut(start, start + 60_000))
        assertTrue(photoGenerationTimedOut(start, start + 60_001))
    }
    @Test fun repeatedExtensionsHaveExactBoundaries() {
        var allowance = photoExtendedAllowance(60_000, 60_000)
        assertEquals(120_000, allowance)
        assertFalse(photoGenerationTimedOut(0, 119_999, allowance))
        assertTrue(photoGenerationTimedOut(0, 120_000, allowance))
        assertTrue(photoGenerationTimedOut(0, 120_001, allowance))
        allowance = photoExtendedAllowance(120_000, allowance)
        assertEquals(180_000, allowance)
        assertFalse(photoGenerationTimedOut(0, 179_999, allowance))
        assertTrue(photoGenerationTimedOut(0, 180_000, allowance))
        assertTrue(photoGenerationTimedOut(0, 180_001, allowance))
        assertEquals(360_000, photoExtendedAllowance(300_000, allowance))
    }
    @Test fun measuredProgressKeepsCountersAcrossUnmeasuredPhases() {
        val progress = PhotoGenerationProgress().checkpoint("regions=2/3;stitches=421")
        assertEquals(2, progress.completedRegions)
        assertEquals(3, progress.totalRegions)
        assertEquals(421, progress.stitches)
        assertEquals(progress.copy(phase="pes"), progress.checkpoint("pes"))
        assertEquals(500, progress.checkpoint("stitches_ready=500").stitches)
        val route = progress.checkpoint("region_stage=rows").checkpoint("region_stage=route")
        assertEquals("route", route.regionStage)
        assertEquals(null, route.regionSteps)
        val inside = route.checkpoint("region_step=1200/3797")
        assertEquals(1200, inside.regionStep)
        assertEquals(3797, inside.regionSteps)
        assertEquals("stitches", inside.phase)
        val next = inside.checkpoint("regions=3/3;stitches=900")
        assertEquals(null, next.regionStep)
        assertEquals(null, next.regionSteps)
        assertEquals(null, next.regionStage)
    }
}
