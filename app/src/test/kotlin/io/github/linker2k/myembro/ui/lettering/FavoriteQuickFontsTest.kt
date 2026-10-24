// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.lettering

import kotlin.test.*

class FavoriteQuickFontsTest {
    private val fonts = listOf(
        QuickFont("current", false, true, "serif") { true },
        QuickFont("recent", false, true, "serif") { true },
        QuickFont("favorite1", false, true, "sans") { true },
        QuickFont("favorite2", false, true, "sans") { true },
        QuickFont("latin", true, false, "script") { it.none { c -> c in '가'..'힣' } },
    )
    @Test fun favoritesReplaceRecentAndThemeCandidates() {
        assertEquals(listOf("current", "favorite1", "favorite2"),
            quickFontSlots("current", listOf("recent"), fonts, "정민", setOf("favorite2", "favorite1")))
    }
    @Test fun incompatibleFavoriteIsSkippedAndCurrentIsNeverDuplicated() {
        val slots = quickFontSlots("current", listOf("recent"), fonts, "정민", setOf("latin", "current", "favorite1"))
        assertEquals(listOf("current", "favorite1", "recent"), slots)
    }
}
