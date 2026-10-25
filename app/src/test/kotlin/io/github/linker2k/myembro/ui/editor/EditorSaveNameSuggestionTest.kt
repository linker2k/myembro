// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import io.github.linker2k.myembro.engine.export.Preflight
import io.github.linker2k.myembro.engine.model.EmbPattern
import kotlin.test.*

class EditorSaveNameSuggestionTest {
    private fun suggestion(original: String, exists: (String) -> Boolean = { false }): String =
        suggestDesignName(original) { candidate ->
            assertTrue(Preflight.isValidFileNameStem(candidate), candidate)
            assertTrue(Preflight.check(EmbPattern(emptyList(), emptyList()), "$candidate.pes").isEmpty(), candidate)
            assertEquals(candidate, designSaveStem(candidate))
            exists(candidate)
        }

    @Test fun `logo space is replaced before proposing or checking collisions`() {
        assertEquals("myembro_logo_2", suggestion("myembro logo.pes"))
        assertEquals("myembro_logo_4", suggestion("myembro logo.pes") { it in setOf("myembro_logo_2", "myembro_logo_3") })
    }

    @Test fun `Korean names receive a valid suggestion without changing manual names`() {
        assertEquals("design_2", suggestion("정민.pes"))
        assertEquals("design_3", suggestion("정민.pes") { it == "design_2" })
        assertEquals("name___2", suggestion("name정민.myembro"))
        assertEquals("정민", designSaveStem("정민.pes"))
    }

    @Test fun `special characters whitespace paths and dots cannot leak into suggestions`() {
        for (name in listOf("a:b?c*<d>|e\"f.pes", "a\tb\nc.pes", "a\u0000b.pes", "heart❤.pes", "../myembro logo.PES", "a.b.myembro")) {
            suggestion(name)
        }
        assertEquals("design_2", suggestion("❤?!"))
        assertEquals("lemon_v2_2", suggestion("lemon.v2.pes"))
        assertEquals("A-z_10", suggestion("A-z_09.pes"))
    }

    @Test fun `existing 80 character limit reserves suffix space including digit growth`() {
        for (length in listOf(1, 77, 78, 79, 80, 81)) {
            val proposed = suggestion("a".repeat(length))
            assertEquals(minOf(length + 2, 80), proposed.length)
            assertTrue(proposed.endsWith("_2"))
        }
        val root = "a".repeat(77)
        val proposed = suggestion("${root}_9") { it.endsWith("_10") }
        assertEquals("${root}_11", proposed)
        assertEquals(80, proposed.length)
        assertEquals(80, suggestion("a".repeat(78) + " 한글").length)
    }

    @Test fun `unchanged machine filename rule still rejects invalid manual stems`() {
        for (name in listOf("", "정민", "myembro logo", "a.b", "a?b", "a\tb")) {
            assertFalse(Preflight.isValidFileNameStem(name), name)
        }
        assertFailsWith<IllegalArgumentException> { suggestion("  ") }
    }
}
