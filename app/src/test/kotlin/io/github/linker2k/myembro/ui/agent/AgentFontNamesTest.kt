// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.agent

import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.engine.edit.Project
import io.github.linker2k.myembro.ui.lettering.LetteringFont
import io.github.linker2k.myembro.ui.lettering.bundledLetteringFonts
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.*

/**
 * The prompt's bundled font names come from the localized string resources (e589dfe). A blank name
 * left the chatbot `f1 ""`; this reads the real values/ and values-ko/ strings, as the app would.
 */
class AgentFontNamesTest {
    private fun strings(dir: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        File("src/main/res/$dir").listFiles { f -> f.name.startsWith("strings") && f.extension == "xml" }!!.forEach { file ->
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
            for (i in 0 until nodes.length) nodes.item(i).let { out[it.attributes.getNamedItem("name").nodeValue] = it.textContent }
        }
        return out
    }
    private val resourceNames = R.string::class.java.fields.associate { it.getInt(null) to it.name }
    private fun names(dir: String): (LetteringFont) -> String {
        val values = strings(dir)
        return { f -> if (f.nameResource != 0) values.getValue(resourceNames.getValue(f.nameResource)) else f.name }
    }

    @Test fun bundledFontNamesAreNonBlankInEnglishAndKorean() {
        for ((dir, expected) in listOf("values" to listOf("Gowun Batang", "Jua", "Noto Sans KR"), "values-ko" to listOf("고운바탕", "둥근고딕", "담백한 고딕"))) {
            val fonts = AgentCatalog.bundledFonts(emptyMap(), names(dir))
            assertEquals(bundledLetteringFonts.map { it.id }, fonts.map { it.id })
            fonts.forEach { assertTrue(it.name.isNotBlank(), "$dir: blank name for ${it.id}") }
            assertEquals(expected, fonts.map { it.name }, dir)
            val prompt = AgentPromptBuilder.build(AgentProjectSummary.of(Project(), fonts) { null }, "안녕", "p-1").text
            fonts.forEachIndexed { i, f -> assertTrue("- f${i + 1} \"${f.name}\" id=${f.id} ko+la " in prompt, "$dir:\n$prompt") }
            assertFalse(Regex("(?m)^- f\\d+ \"\"").containsMatchIn(prompt), dir)
        }
    }

    @Test fun aBlankNameFallsBackToTheStableId() {
        // The pre-fix reading: LetteringFont.name is now empty for every bundled font.
        val fonts = AgentCatalog.bundledFonts(emptyMap()) { it.name }
        assertTrue(fonts.all { it.name.isBlank() })
        val summary = AgentProjectSummary.of(Project(), fonts) { null }
        assertEquals(listOf("gowun-batang", "jua", "noto-sans-kr"), summary.fonts.values.map { it.name })
        assertTrue("- f2 \"jua\" id=jua ko+la " in AgentPromptBuilder.build(summary, "x", "p-1").text)
    }
}
