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
    }
}
