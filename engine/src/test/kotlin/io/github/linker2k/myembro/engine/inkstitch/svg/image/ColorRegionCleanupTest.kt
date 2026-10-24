// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import kotlin.test.*

class ColorRegionCleanupTest {
    @Test fun borderModeIsNotTheTopLeftPixel() {
        val labels=IntArray(25) { 0 };labels[0]=1;labels[12]=2
        assertEquals(0,ColorRegionCleanup.borderMode(5,5,labels))
    }
    @Test fun smallFragmentMergesOnlyIntoAdjacentRetainedRegionAndHolesStayTransparent() {
        val labels=IntArray(25) { 0 };labels[12]=1;labels[6]=-1
        val actual=ColorRegionCleanup.mergeSmall(5,5,labels,0.1,0.5)
        assertEquals(0,actual[12]);assertEquals(-1,actual[6])
        assertEquals(1,ColorRegionCleanup.mergeSmall(5,5,labels,0.1,0.1)[12])
    }
}
