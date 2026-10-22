// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DimensionsTest {
    @Test fun `actual dimensions use 25 point 4 mm and exact quarter fractions`() {
        assertEquals("25.4mm (1in)", Dimensions.length(25.4))
        assertEquals("½", Dimensions.inches(12.7))
        assertEquals("¼", Dimensions.inches(6.35))
        assertEquals("1¾", Dimensions.inches(44.45))
        assertEquals("-½", Dimensions.inches(-12.7))
        assertEquals("0", Dimensions.inches(0.0))
    }
    @Test fun `non quarter sizes round half up to one decimal without locale dependence`() {
        assertEquals("0.2", Dimensions.inches(3.81))
        assertEquals("3.9", Dimensions.inches(100.0))
        assertEquals("1.3×2.4mm (0×0.1in)", Dimensions.size(1.25, 2.35))
    }
    @Test fun `manufacturer nominal hoop labels are distinct from true conversions`() {
        assertEquals("100×100mm (4×4in)", Dimensions.hoopSize(100, 100))
        assertEquals("20×60mm (1×2½in)", Dimensions.hoopSize(20, 60))
        assertEquals("60×20mm (2½×1in)", Dimensions.hoopSize(60, 20))
        assertEquals("100×170mm (4×6¾in)", Dimensions.hoopSize(100, 170))
        assertEquals("100×100mm (3.9×3.9in)", Dimensions.size(100.0, 100.0))
    }
    @Test fun `invalid sizes are rejected`() {
        assertFailsWith<IllegalArgumentException> { Dimensions.size(-1.0, 1.0) }
        assertFailsWith<IllegalArgumentException> { Dimensions.length(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { Dimensions.inches(Double.POSITIVE_INFINITY) }
    }
}
