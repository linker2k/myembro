// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.lettering

import kotlin.test.Test
import kotlin.test.assertEquals

class QuickFontSlotsTest {
    private val latinOnly: (String) -> Boolean = { t -> t.none { it in '가'..'힣' } }
    private val fonts = listOf(
        QuickFont("gowun-batang", embroidery = false, hangul = true, theme = "serif", covers = { true }),
        QuickFont("jua", embroidery = false, hangul = true, theme = "display", covers = { true }),
        QuickFont("noto-sans-kr", embroidery = false, hangul = true, theme = "sans", covers = { true }),
        QuickFont("ink-venezia", embroidery = true, hangul = false, theme = "serif", covers = latinOnly),
        QuickFont("ink-allegria", embroidery = true, hangul = false, theme = "script", covers = latinOnly),
        QuickFont("ink-pacificlo", embroidery = true, hangul = false, theme = "script", covers = latinOnly),
        QuickFont("ttf-dancing", embroidery = false, hangul = false, theme = "script", covers = latinOnly),
    )

    @Test fun libraryPickTakesSlotOneAndThePreviousFontSlotTwo() {
        // Recents are newest first: the picked font, then the one shown before it.
        assertEquals(listOf("ink-allegria", "gowun-batang", "ink-pacificlo"),
            quickFontSlots("ink-allegria", listOf("ink-allegria", "gowun-batang"), fonts, "Jung"))
    }

    @Test fun thirdSlotIsASameThemeNeighbour() {
        assertEquals("ink-venezia", quickFontSlots("gowun-batang", listOf("gowun-batang", "jua"), fonts, "Jung")[2])
        // A script TTF's neighbour is a script font; embroidery fonts lead for Latin text.
        assertEquals("ink-allegria", quickFontSlots("ttf-dancing", listOf("ttf-dancing"), fonts, "Jung")[1])
    }

    @Test fun languageDecidesWhatFillsEmptySlots() {
        assertEquals(listOf("ink-venezia", "ink-allegria", "ink-pacificlo"), quickFontSlots("ink-venezia", emptyList(), fonts.filter { it.theme != "serif" || it.id == "ink-venezia" }, "Jung").let {
            listOf(it[0]) + it.drop(1).sorted() })
        // Hangul text: no embroidery font covers it, so Hangul TTFs fill the slots.
        assertEquals(listOf("ink-venezia", "gowun-batang", "jua"), quickFontSlots("ink-venezia", emptyList(), fonts, "봄날"))
    }

    @Test fun fontsThatDoNotCoverTheTextAreNotAddedButCurrentAndRecentStay() {
        val slots = quickFontSlots("ink-allegria", listOf("ink-allegria", "ink-venezia"), fonts, "봄날")
        assertEquals(listOf("ink-allegria", "ink-venezia", "gowun-batang"), slots)
    }

    @Test fun firstOpenPicksADownloadedEmbroideryFontForLatinAndGowunForHangul() {
        val installed = fonts.filter { it.embroidery }
        assertEquals("ink-venezia", defaultLetteringFont(emptyList(), "Jung", installed))
        assertEquals("gowun-batang", defaultLetteringFont(emptyList(), "봄날", installed))
        assertEquals("gowun-batang", defaultLetteringFont(emptyList(), "Jung", emptyList()), "none downloaded yet")
        assertEquals("gowun-batang", defaultLetteringFont(listOf("jua"), "Jung", installed), "history wins")
    }
}
