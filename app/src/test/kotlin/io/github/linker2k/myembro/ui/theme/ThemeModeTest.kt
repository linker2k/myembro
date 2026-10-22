// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThemeModeTest {
    @Test fun `missing and unknown preferences stay light even on a dark system`() {
        for (stored in listOf(null, "", "old-value")) {
            assertEquals(ThemeMode.LIGHT, ThemeMode.fromStored(stored))
            assertFalse(ThemeMode.fromStored(stored).isDark(true))
        }
    }

    @Test fun `explicit light and dark ignore the system while system mode follows it`() {
        for (system in listOf(false, true)) {
            assertFalse(ThemeMode.LIGHT.isDark(system))
            assertTrue(ThemeMode.DARK.isDark(system))
            assertEquals(system, ThemeMode.SYSTEM.isDark(system))
        }
        for (mode in ThemeMode.entries) assertEquals(mode, ThemeMode.fromStored(mode.name))
    }
}
