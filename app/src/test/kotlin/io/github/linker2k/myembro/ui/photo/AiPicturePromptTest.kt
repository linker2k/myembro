// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.photo

import io.github.linker2k.myembro.engine.edit.HoopSpec
import java.util.Locale
import kotlin.test.*

class AiPicturePromptTest {
    @Test fun withoutPhotoPreservesRequestAndLettering() {
        val request = "웃는 강아지 얼굴, 아래에 코코\n정확한 글자: '코코 ♥'"
        val prompt = AiPicturePrompt.build(request, false, HoopSpec.H100, 40.0, 4)
        assertTrue(prompt.contains(request))
        assertTrue(prompt.contains("No reference image is attached"))
        assertTrue(prompt.contains("40.00 x 40.00 mm"))
        assertTrue(prompt.contains("1.00 mm") && prompt.contains("2.50%"))
        assertTrue(prompt.contains("square 2000×2000 px PNG"))
        assertTrue(prompt.contains("No antialiasing, gradients, shadows") && prompt.contains("bold Gothic/sans-serif"))
    }
    @Test fun withPhotoUsesTheSameRulesAndOwnedHexPalette() {
        val prompt = AiPicturePrompt.build("코코", true, HoopSpec.H130x180, 40.0, 3,
            listOf(0xff112233.toInt(), 0xffaabbcc.toInt(), 0xff112233.toInt()))
        assertTrue(prompt.contains("Use the attached reference image"))
        assertTrue(prompt.contains("1300:1800"))
        assertTrue(prompt.contains("40.00 x 55.38 mm"))
        assertTrue(prompt.contains("ONLY from this thread palette: #112233, #AABBCC."))
        assertTrue(prompt.contains("exactly 3"))
    }
    @Test fun dimensionsIgnoreDeviceLocaleAndRejectHoopBoundary() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            assertTrue(AiPicturePrompt.build("A dog", false, HoopSpec.H100, 99.9, 2).contains("99.90"))
            assertFailsWith<IllegalArgumentException> { AiPicturePrompt.build("A dog", false, HoopSpec.H100, 100.0, 2) }
            assertFailsWith<IllegalArgumentException> { AiPicturePrompt.build("A dog", false, HoopSpec.H100, Double.NaN, 2) }
        } finally { Locale.setDefault(before) }
    }
    @Test fun twoColorSmileUsesTheDefaultSquarePngContract() {
        val prompt = AiPicturePrompt.build("2가지 색으로 스마일", false, HoopSpec.H100, 40.0)
        assertTrue(prompt.contains("2가지 색으로 스마일"))
        assertTrue(prompt.contains("2000×2000 px PNG"))
        assertTrue(prompt.contains("exactly 2 solid foreground colors (+ white background)"))
        assertTrue(prompt.contains("No antialiasing, gradients, shadows"))
    }
    @Test fun photoFlagOnlyChangesReferenceSentence() {
        val with = AiPicturePrompt.build("Smile", true, HoopSpec.H100, 40.0, 2).lines()
        val without = AiPicturePrompt.build("Smile", false, HoopSpec.H100, 40.0, 2).lines()
        assertEquals(with.filterIndexed { index, _ -> index != 1 }, without.filterIndexed { index, _ -> index != 1 })
    }
}
