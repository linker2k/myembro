// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.lettering

import java.util.Locale
import kotlin.test.*

class LetteringHeightDisplayTest {
    @Test fun `slider height switched to a running font displays one decimal without changing its value`() {
        val original = Locale.getDefault()
        try {
            for (locale in listOf(Locale.US, Locale.KOREA, Locale.GERMANY)) {
                Locale.setDefault(locale)
                val height = 17.14017105102539
                val draft = height.toString()
                assertEquals(String.format(locale, "%.1f", height), letteringHeightDisplayText(draft, height))
                assertEquals(17.14017105102539, draft.toDouble())
                assertEquals(height, draft.toDouble()) // Existing input validation and persisted height are unchanged.
                assertEquals(String.format(locale, "%.1f", 18.0), letteringHeightDisplayText("18.0", 18.0))
            }
        } finally { Locale.setDefault(original) }
    }

    @Test fun `incomplete and user entered drafts retain existing input text`() {
        for (draft in listOf("", "17.", "17,", "17,14", "017.14", "17.140", "invalid")) {
            assertEquals(draft, letteringHeightDisplayText(draft, 17.14))
        }
    }
}
