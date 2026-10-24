// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.agent

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.edit.Project
import io.github.linker2k.myembro.engine.edit.StitchObject
import io.github.linker2k.myembro.engine.edit.TextObject
import io.github.linker2k.myembro.engine.edit.Transform
import io.github.linker2k.myembro.engine.model.*
import kotlin.test.*

class AgentPromptBuilderTest {
    private val logo = StitchObject("uuid-logo", "Logo", EmbPattern(listOf(Stitch(0, 0, Command.STITCH), Stitch(10, 10, Command.STITCH)),
        listOf(EmbThread(0xFF112233.toInt()), EmbThread(0xFFABCDEF.toInt()))), Transform(rotationDeg = 90.0))
    private val name = TextObject("uuid-name", "Name", text = "안녕", fontId = "jua", heightMm = 12.0,
        thread = EmbThread(0xFFD0021B.toInt()), visible = false)
    private val second = TextObject("uuid-2", "Again", text = "myembro", fontId = "jua")
    private val project = Project(HoopSpec("100 × 100", 1000, 1000), listOf(logo, name, second))
    private val bounds = mapOf("uuid-logo" to Bounds(-200, -100, 200, 300))
    private val summary = AgentProjectSummary.of(project) { bounds[it.id] }

    @Test fun handlesReplaceIdsAndFontsAreShared() {
        assertEquals(mapOf("o1" to "uuid-logo", "o2" to "uuid-name", "o3" to "uuid-2"), summary.handles.objects)
        assertEquals(mapOf("f1" to "jua"), summary.handles.fonts)
        val text = AgentPromptBuilder.build(summary, "  Put my name under the logo  ", "p-AB12").text
        assertFalse("uuid-" in text)
    }

    @Test fun promptHasInstructionsToolsDesignAndRequestInOrder() {
        val text = AgentPromptBuilder.build(summary, "  Put my name under the logo  ", "p-AB12").text
        val parts = listOf("Answer with ONLY the JSON command list", "\"promptId\":\"p-AB12\"", "Tools", "Design:", "Fonts:", "Request:")
        assertTrue(parts.map { text.indexOf(it) }.zipWithNext().all { (a, b) -> a in 0 until b }, text)
        assertTrue(text.endsWith("Request:\n\"\"\"\nPut my name under the logo\n\"\"\""))
        AgentTools.SIGNATURES.forEach { assertTrue("- $it" in text, it) }
    }

    @Test fun objectsAreDescribedInMillimetres() {
        val text = AgentPromptBuilder.build(summary, "x", "p-1").text
        assertTrue("- hoop: 100 × 100 100 x 100 mm" in text, text)
        assertTrue("- o1 design \"Logo\" center=(0,10)mm size=40x40mm rotation=90deg scale=100%x100% colors=#112233,#ABCDEF order=0\n" in text, text)
        assertTrue("- o2 text \"Name\" text=\"안녕\" font=f1 textHeight=12mm rotation=0deg scale=100%x100% colors=#D0021B order=1 hidden\n" in text, text)
        assertTrue("- f1 = jua" in text)
    }

    @Test fun emptyProjectAndDeterminism() {
        val empty = AgentProjectSummary.of(Project()) { null }
        val a = AgentPromptBuilder.build(empty, "Write hi", "p-1").text
        assertEquals(a, AgentPromptBuilder.build(empty, "Write hi", "p-1").text)
        assertTrue("- no objects yet" in a)
        assertFalse("Fonts:" in a)
    }

    @Test fun boundsFailureLeavesSizeOut() {
        val s = AgentProjectSummary.of(Project(objects = listOf(second))) { error("font not loaded") }
        assertNull(s.objects.single().bounds)
    }

    @Test fun toolListMatchesTheDesignDocument() {
        assertEquals(16, AgentTools.SIGNATURES.size)
        assertEquals(2181, AgentTools.SIGNATURES.joinToString("\n").length) // docs/agent/mobile-standalone.md "Length"
        assertFalse(AgentTools.SIGNATURES.any { "transactionId" in it })
    }

    @Test fun repairPromptListsProblems() {
        assertEquals("myembro could not use your last reply (prompt p-1):\n- a\n- b\nReply again with ONLY the corrected ```json block in the same format.",
            AgentPromptBuilder.repair("p-1", listOf("a", "b")))
    }
}
