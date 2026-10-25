// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PhotoFilterTest {
    @Test fun otsuReturnsTheConstantValueAndDarkInkIncludesEquality() {
        for (value in listOf(0, 73, 255)) {
            val input = ByteArray(9) { value.toByte() }
            assertEquals(value, OtsuThreshold.thresholdOtsu(input))
            assertContentEquals(ByteArray(9), OtsuThreshold.darkMask(input))
        }
    }

    @Test fun otsuFirstMaximumRetainsTheLowestThresholdAcrossAnEmptyGap() {
        val input = byteArrayOf(20, 20, 20, 180.toByte(), 180.toByte(), 180.toByte())
        assertEquals(20, OtsuThreshold.thresholdOtsu(input))
        assertContentEquals(byteArrayOf(0, 0, 0, -1, -1, -1), OtsuThreshold.darkMask(input))
    }

    @Test fun otsuUniformRampIsDeterministicAndPreservesInput() {
        val input = ByteArray(256) { it.toByte() }
        val unchanged = input.copyOf()
        assertEquals(127, OtsuThreshold.thresholdOtsu(input))
        assertEquals(127, OtsuThreshold.thresholdOtsu(input))
        val mask = OtsuThreshold.darkMask(input)
        assertEquals(128, mask.count { it == 0.toByte() })
        assertContentEquals(unchanged, input)
    }

    @Test fun autocontrastLTruncatesFractionalValuesAndPreservesInput() {
        val input = byteArrayOf(10, 20, 30)
        val unchanged = input.copyOf()
        assertContentEquals(byteArrayOf(0, 127, -1), Autocontrast.apply(input))
        assertContentEquals(unchanged, input)
    }

    @Test fun autocontrastRgbTreatsEachChannelIndependentlyIncludingConstants() {
        val input = byteArrayOf(10, 50, 100, 20, 50, -106, 30, 50, -56)
        // Pillow retains floating truncation: the last blue value becomes 254, not 255.
        assertContentEquals(byteArrayOf(0, 50, 0, 127, 50, 127, -1, 50, -2),
            Autocontrast.apply(input, channels = 3))
        assertContentEquals(input, byteArrayOf(10, 50, 100, 20, 50, -106, 30, 50, -56))
    }

    @Test fun autocontrastFullRangeAndEmptyInputsAreIdentity() {
        val ramp = ByteArray(256) { it.toByte() }
        assertContentEquals(ramp, Autocontrast.apply(ramp))
        assertContentEquals(byteArrayOf(), Autocontrast.apply(byteArrayOf()))
        assertContentEquals(byteArrayOf(), Autocontrast.apply(byteArrayOf(), 3))
    }

    @Test fun rejectsUnsupportedContractsInsteadOfSilentlyConverting() {
        assertFailsWith<IllegalArgumentException> { OtsuThreshold.thresholdOtsu(byteArrayOf()) }
        assertFailsWith<IllegalArgumentException> { Autocontrast.apply(byteArrayOf(1, 2, 3, 4), 4) }
        assertFailsWith<IllegalArgumentException> { Autocontrast.apply(byteArrayOf(1, 2), 3) }
        assertFailsWith<IllegalArgumentException> { Autocontrast.apply(byteArrayOf(), 0) }
    }
}
