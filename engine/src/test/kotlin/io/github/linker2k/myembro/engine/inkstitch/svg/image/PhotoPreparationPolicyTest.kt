// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import kotlin.math.PI
import kotlin.test.*

class PhotoPreparationPolicyTest {
    @Test fun laddersKeepTheFormerDefaults() {
        val p = PhotoPreparationPolicy
        assertEquals(listOf(128, 181, 192, 256, 362, 512, 724, 1024), p.resolutionSteps(p.MONO_DEFAULT_EDGE))
        assertEquals(listOf(128, 181, 256, 362, 512, 724, 1024), p.resolutionSteps(p.COLOR_DEFAULT_EDGE))
        assertEquals(listOf(0.0, 0.25, 0.5, 1.0, 2.0, 4.0, 8.0, 16.0), p.pieceAreaSteps(p.MONO_DEFAULT_PIECE_AREA_MM2))
        assertEquals(listOf(0.25, 0.5, 1.0, 2.0, PI, 4.0, 8.0, 16.0), p.pieceAreaSteps(p.COLOR_DEFAULT_PIECE_AREA_MM2, allowOff = false))
        // Former detail 1.
        assertEquals(p.photoRasterSize(1, 99.9, 99.9), p.photoRasterEdge(p.MONO_DEFAULT_EDGE, 99.9, 99.9))
        assertEquals(p.photoRasterSize(1, 20.0, 20.0), p.photoRasterEdge(p.MONO_DEFAULT_EDGE, 20.0, 20.0))
        assertEquals(p.photoMinimumArea(1), p.MONO_DEFAULT_PIECE_AREA_MM2)
    }

    /** Steps stop at the decoded picture's long side (inclusive); the mode default always stays. */
    @Test fun stepsStopAtTheInputLongSide() {
        val p = PhotoPreparationPolicy
        assertEquals(724, p.resolutionSteps(p.COLOR_DEFAULT_EDGE, 1023).filter { it != p.COLOR_DEFAULT_EDGE }.max())
        assertEquals(1024, p.resolutionSteps(p.COLOR_DEFAULT_EDGE, 1024).max())
        assertEquals(1024, p.resolutionSteps(p.COLOR_DEFAULT_EDGE, 1025).max())
        assertEquals(listOf(128, 192), p.resolutionSteps(p.MONO_DEFAULT_EDGE, 128))
        assertEquals(listOf(128, 181, 192), p.resolutionSteps(p.MONO_DEFAULT_EDGE, 181))
    }
}
