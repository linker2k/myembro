// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import io.github.linker2k.myembro.engine.geometry.*
import kotlin.test.*

class PictureFillMaskTest {
    @Test fun closingUsesExpansionRadiusAndBridgesInternalGapWithoutGrowingExtent() {
        val region=SvgPath.polygons("M0 0 H100 V100 H0 Z M102 0 H202 V100 H102 Z",FillRule.EVEN_ODD)
        assertFalse(region.covers(Point(101.0,50.0)))
        val closed=PictureFillMask.closing(region,.3)
        assertTrue(closed.covers(Point(101.0,50.0)))
        assertSame(region,PictureFillMask.closing(region,0.0))
        assertTrue(closed.geometry.difference(region.geometry.convexHull()).area<1e-8)
        assertFailsWith<IllegalArgumentException> { PictureFillMask.closing(region,Double.NaN) }
    }
}
