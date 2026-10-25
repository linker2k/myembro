// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.agent

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.edit.Project
import io.github.linker2k.myembro.engine.edit.StitchObject
import io.github.linker2k.myembro.engine.edit.TextObject
import io.github.linker2k.myembro.engine.model.*
import kotlin.test.*

/** Prompt size for the manager's three Korean requests on a logo + text design with the bundled lists. */
class AgentPromptLengthTest {
    private val fonts = listOf(
        AgentFontInfo("gowun-batang", "고운바탕", true, true, 5.0, 60.0),
        AgentFontInfo("jua", "둥근고딕", true, true, 5.0, 60.0),
        AgentFontInfo("noto-sans-kr", "담백한 고딕", true, true, 26.25, 60.0),
        AgentFontInfo("hershey-script-1", "Hershey Script 1-stroke", false, true, 5.0, 60.0),
        AgentFontInfo("inkstitch-abril", "Abril En Flor", false, true, 10.0, 40.0),
    )
    // Same shape as assets/samples/manifest.json (29 samples); names and sizes are fixture values.
    private val designs = (1..29).map { AgentDesignInfo("sample_$it", "샘플 도안 $it", 60.0 + it, 55.5) }
    private val project = Project(HoopSpec("100 × 100", 1000, 1000), listOf(
        StitchObject("uuid-logo", "myembro logo", EmbPattern(listOf(Stitch(0, 0, Command.STITCH)), listOf(EmbThread(0xFFB8862B.toInt()), EmbThread(0xFFC2185B.toInt())))),
        TextObject("uuid-hi", "안녕", text = "안녕", fontId = "jua", heightMm = 12.0)))
    private val bounds = mapOf("uuid-logo" to Bounds(-300, -317, 300, 316), "uuid-hi" to Bounds(-120, 380, 120, 500))
    private val summary = AgentProjectSummary.of(project, fonts, designs) { bounds[it.id] }
    private val requests = listOf("로고 아래에 안녕 15mm", "글자 빨간색으로", "전체 가운데")

    @Test fun listsFontsAndDesignsWithHandles() {
        val text = AgentPromptBuilder.build(summary, requests[0], "p-AB12").text
        assertTrue("- f2 \"둥근고딕\" ko+la h5-60mm\n" in text, text)
        assertTrue("- f3 \"담백한 고딕\" ko+la h26.3-60mm\n" in text, text)
        assertTrue("- f5 \"Abril En Flor\" la h10-40mm\n" in text, text)
        assertTrue("- d29 \"샘플 도안 29\" 89x55.5mm\n" in text, text)
        assertTrue("- o2 text \"안녕\" text=\"안녕\" font=f2 " in text, text) // the design's font reuses its list handle
        assertEquals("jua", summary.handles.fonts["f2"]); assertEquals("sample_1", summary.handles.designs["d1"])
    }

    @Test fun measuredLengthsAreDeterministic() {
        val lengths = requests.map { r -> AgentPromptBuilder.build(summary, r, "p-AB12").text.length }
        assertEquals(lengths, requests.map { r -> AgentPromptBuilder.build(summary, r, "p-AB12").text.length })
        val shortest = AgentPromptBuilder.build(summary, requests[0], "p-AB12", budgetChars = 1).text.length
        println("PROMPT_LENGTH chars full=$lengths shortest=$shortest")
        lengths.forEach { assertTrue(it in 4_000..8_000, "$it") }
    }

    @Test fun shortensInTheDocumentedOrderOnlyWhenOverBudget() {
        val full = AgentPromptBuilder.build(summary, requests[0], "p-AB12")
        assertEquals(0, full.level); assertFalse(full.overBudget)
        assertEquals(full, AgentPromptBuilder.build(summary, requests[0], "p-AB12", budgetChars = full.text.length))
        val l1 = AgentPromptBuilder.build(summary, requests[0], "p-AB12", budgetChars = full.text.length - 1)
        assertEquals(1, l1.level)
        assertFalse("Hershey Script" in l1.text) // Korean request: Latin-only fonts dropped
        assertTrue("(list left out to save space)" in l1.text)
        assertTrue("myembro logo" in l1.text)
        val l2 = AgentPromptBuilder.build(summary, requests[0], "p-AB12", budgetChars = l1.text.length - 1)
        assertEquals(2, l2.level)
        assertTrue("- o2 text \"안녕\"" in l2.text); assertFalse("\"myembro logo\"" in l2.text) // only objects named in the request
        assertTrue("1 more objects" in l2.text)
        val l3 = AgentPromptBuilder.build(summary, requests[0], "p-AB12", budgetChars = l2.text.length - 1)
        assertEquals(3, l3.level); assertFalse(l3.overBudget)
        assertTrue(AgentPromptBuilder.build(summary, requests[0], "p-AB12", budgetChars = 1).let { it.level == 3 && it.overBudget && it.text == l3.text })
        assertFalse("Prepare and add text" in l3.text); assertTrue("- add_text(objectId:str" in l3.text)
        assertTrue(l3.text.endsWith("\"\"\"\n로고 아래에 안녕 15mm\n\"\"\""))
        assertTrue(listOf(full, l1, l2, l3).map { it.text.length }.zipWithNext().all { (a, b) -> b < a })
        println("PROMPT_LEVELS chars " + listOf(full, l1, l2, l3).map { it.text.length })
    }

    @Test fun designHandlesExpandOnlyForAssetIds() {
        val script = (AgentScriptParser.parse("""{"myembroScript":1,"promptId":"p","summary":"","commands":[""" +
            """{"tool":"add_design","args":{"assetId":"d3","objectId":"new:x","name":"d3"}}]}""") as AgentParseResult.Parsed).script
        val args = AgentScriptCalls.of(script, summary.handles, "t").calls.single().arguments.toString()
        assertTrue("\"assetId\":\"sample_3\"" in args && "\"name\":\"d3\"" in args, args)
    }
}
