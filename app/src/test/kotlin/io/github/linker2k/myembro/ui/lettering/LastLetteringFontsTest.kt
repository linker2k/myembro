// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.lettering

import kotlin.test.*

/** The last font per script, shared by the lettering sheet and the AI connection's add_text. */
class LastLetteringFontsTest {
    /** Stands in for the "lettering" SharedPreferences file: a new instance reads what an earlier one wrote. */
    private val saved = mutableMapOf<String, String>()
    private val store = object : LastLetteringFonts.Store {
        override fun get(key: String) = saved[key]
        override fun put(key: String, value: String) { saved[key] = value }
    }
    /** Coverage for the fake fonts: a Korean font with Latin, a Korean font without, a Latin script font. */
    private val writes = mapOf(
        "noto-sans-kr" to { t: String -> true },
        "hangul-only" to { t: String -> t.none { it in 'A'..'z' } },
        "sacramarif" to { t: String -> !LastLetteringFonts.hasHangul(t) },
        LastLetteringFonts.DEFAULT to { _: String -> true },
    )
    private fun choose(text: String, fonts: LastLetteringFonts = LastLetteringFonts(store)) =
        LastLetteringFonts.choose(text, fonts.korean, fonts.latin) { id -> writes[id]?.invoke(text) == true }

    @Test fun koreanAndLatinTextRememberTheirOwnFont() {
        val fonts = LastLetteringFonts(store)
        fonts.record("noto-sans-kr", "안녕")
        fonts.record("sacramarif", "Mina")
        assertEquals("noto-sans-kr" to "sacramarif", fonts.korean to fonts.latin)
        assertEquals("noto-sans-kr" to false, choose("안녕"))
        assertEquals("sacramarif" to false, choose("Hello"))
        // Mixed text is Korean text; the Korean font writes the Latin letters too.
        assertEquals("noto-sans-kr" to false, choose("안녕 Mina"))
        // Blank text changes nothing.
        fonts.record("hangul-only", "  "); assertEquals("noto-sans-kr", fonts.korean)
    }

    @Test fun aRememberedFontThatCannotWriteTheTextFallsBackToTheDefault() {
        val fonts = LastLetteringFonts(store)
        fonts.record("hangul-only", "가나")
        assertEquals("hangul-only" to false, choose("안녕"))
        assertEquals(LastLetteringFonts.DEFAULT to true, choose("안녕 Mina"))
        // Nothing remembered yet: the default, not reported as a fallback.
        assertEquals(LastLetteringFonts.DEFAULT to false, choose("Hello"))
    }

    @Test fun theChoiceSurvivesANewInstance() {
        LastLetteringFonts(store).record("noto-sans-kr", "안녕")
        LastLetteringFonts(store).record("sacramarif", "Hi")
        val restarted = LastLetteringFonts(store)
        assertEquals("noto-sans-kr", restarted.forText("한글"))
        assertEquals("sacramarif", restarted.forText("Latin"))
        assertEquals(setOf(LastLetteringFonts.KO, LastLetteringFonts.EN), saved.keys)
    }
}
