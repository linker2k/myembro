// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.elements

import io.github.linker2k.myembro.engine.lettering.SatinColumn
import io.github.linker2k.myembro.engine.lettering.SatinOptions
import kotlin.test.*

class SatinUnderlayPolicyTest {
    private fun flags(p: SatinParameters) = Triple(p.center_walk_underlay, p.contour_underlay, p.zigzag_underlay)

    @Test fun widthBoundaryIsInclusiveAtFourMillimetres() {
        val none = SatinParameters()
        for (w in listOf(0.0, 1.0, 3.999)) assertEquals(Triple(true, false, false), flags(SatinUnderlayPolicy.withDefault(none, w)))
        for (w in listOf(4.0, 4.001, 9.0)) assertEquals(Triple(false, true, true), flags(SatinUnderlayPolicy.withDefault(none, w)))
    }

    @Test fun onlyTheSwitchesChangeAndExplicitChoicesAreKept() {
        val chosen = SatinUnderlayPolicy.withDefault(SatinParameters(), 5.0)
        assertEquals(SatinParameters().copy(contour_underlay = true, zigzag_underlay = true), chosen)
        val explicit = SatinParameters(contour_underlay = true)
        assertSame(explicit, SatinUnderlayPolicy.withDefault(explicit, 1.0))
    }

    @Test fun letteringColumnsGetUnderlayUnlessOptedOut() {
        val left = listOf(0.0 to 0.0, 0.0 to 10.0); val right = listOf(2.0 to 0.0, 2.0 to 10.0)
        val auto = SatinColumn.generate(left, right, SatinOptions())
        val top = SatinColumn.generate(left, right, SatinOptions(autoUnderlay = false))
        val center = SatinColumn.generate(left, right, SatinOptions(autoUnderlay = false, centerUnderlay = true))
        assertEquals(center.stitches, auto.stitches)
        assertTrue(auto.stitchCount > top.stitchCount)
    }
}
