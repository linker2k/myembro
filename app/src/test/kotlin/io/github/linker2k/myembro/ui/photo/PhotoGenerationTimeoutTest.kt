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
    }
}
