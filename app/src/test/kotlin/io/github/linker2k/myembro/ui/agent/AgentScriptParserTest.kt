// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.agent

import io.github.linker2k.myembro.ui.agent.AgentScriptProblem.Code
import kotlin.test.*

class AgentScriptParserTest {
    private val script = """{"myembroScript":1,"promptId":"p-7Q2K","summary":"Add Mina","commands":[""" +
        """{"tool":"add_text","args":{"objectId":"new:name","text":"Mina"}},""" +
        """{"tool":"center_objects","args":{"objectIds":["o1","new:name"]}}]}"""

    private fun parsed(reply: String) = assertIs<AgentParseResult.Parsed>(AgentScriptParser.parse(reply)).script
    private fun problems(reply: String) = assertIs<AgentParseResult.Invalid>(AgentScriptParser.parse(reply)).problems

    @Test fun readsAFencedBlockSurroundedByProse() {
        val s = parsed("Sure! Here is the plan.\n\n```json\n$script\n```\n\nPaste this back into myembro.")
        assertEquals("p-7Q2K", s.promptId)
        assertEquals("Add Mina", s.summary)
        assertEquals(listOf("add_text", "center_objects"), s.commands.map { it.tool })
        assertEquals("\"Mina\"", s.commands[0].args["text"].toString())
    }

    @Test fun readsAnUnfencedObjectInsideProse() {
        val s = parsed("Here you go: $script Let me know if you want changes {like this}.")
        assertEquals(2, s.commands.size)
    }

    @Test fun readsAFenceWithoutLanguageAndWindowsLineEnds() {
        assertEquals(2, parsed("Done.\r\n```\r\n$script\r\n```\r\n").commands.size)
    }

    @Test fun ignoresOtherCodeBlocksWithoutTheMarker() {
        assertEquals(2, parsed("```kotlin\nval x = 1\n```\n```json\n$script\n```").commands.size)
    }

    @Test fun braceInsideAStringDoesNotEndTheUnfencedObject() {
        val tricky = script.replace("\"Mina\"", "\"Mi}na\\\"{\"")
        assertEquals("\"Mi}na\\\"{\"", parsed("Text before $tricky after").commands[0].args["text"].toString())
    }

    @Test fun noScript() {
        assertEquals(Code.NO_SCRIPT, problems("What colour would you like the text to be?").single().code)
        assertEquals(Code.NO_SCRIPT, problems("").single().code)
    }

    @Test fun twoScriptsAreNotGuessedBetween() {
        assertEquals(Code.MULTIPLE_SCRIPTS, problems("```json\n$script\n```\nor\n```json\n$script\n```").single().code)
        assertEquals(Code.MULTIPLE_SCRIPTS, problems("$script\n$script").single().code)
    }

    @Test fun brokenJsonReportsLineAndColumn() {
        val broken = "```json\n{\"myembroScript\":1,\n\"promptId\":\"p-7Q2K\",\n\"summary\":\"x\" \"commands\":[]}\n```"
        val result = assertIs<AgentParseResult.Broken>(AgentScriptParser.parse(broken))
        assertEquals(3, result.line)
        assertNotNull(result.column)
        assertEquals("\"summary\":\"x\" \"commands\":[]}", result.excerpt)
    }

    @Test fun truncatedUnfencedReplyIsBrokenNotMissing() {
        assertIs<AgentParseResult.Broken>(AgentScriptParser.parse("Here: " + script.dropLast(10)))
    }

    @Test fun lenientJsonIsRejected() {
        assertIs<AgentParseResult.Broken>(AgentScriptParser.parse("```json\n" + script.replace("]}", ",]}") + "\n```"))
        assertIs<AgentParseResult.Broken>(AgentScriptParser.parse("```json\n" + script.replace("\"summary\"", "summary") + "\n```"))
    }

    @Test fun envelopeIsStrict() {
        assertEquals(Code.UNSUPPORTED_VERSION, problems(script.replace("\"myembroScript\":1", "\"myembroScript\":2")).single().code)
        assertEquals(Code.UNSUPPORTED_VERSION, problems(script.replace("\"myembroScript\":1", "\"myembroScript\":\"1\"")).single().code)
        assertEquals(listOf(Code.UNKNOWN_FIELD to "extra"), problems(script.replace("{\"myembroScript\"", "{\"extra\":0,\"myembroScript\"")).map { it.code to it.path })
        assertEquals(listOf(Code.NO_COMMANDS to "commands"),
            problems("""{"myembroScript":1,"promptId":"p","summary":"","commands":[]}""").map { it.code to it.path })
        assertEquals(listOf(Code.MISSING_FIELD to "promptId", Code.WRONG_TYPE to "summary"),
            problems("""{"myembroScript":1,"summary":3,"commands":[{"tool":"x","args":{}}]}""").map { it.code to it.path })
    }

    @Test fun commandsAreStrictButToolNamesAreLeftToTheExecutor() {
        val reply = """{"myembroScript":1,"promptId":"p","summary":"","commands":[{"tool":"make_rainbow","args":{}},""" +
            """{"tool":"add_text"},{"tool":"","args":{}},{"tool":"x","args":[],"note":1},7]}"""
        assertEquals(listOf(Code.MISSING_FIELD to "commands[1].args", Code.WRONG_TYPE to "commands[2].tool",
            Code.UNKNOWN_FIELD to "commands[3].note", Code.WRONG_TYPE to "commands[3].args", Code.WRONG_TYPE to "commands[4]"),
            problems(reply).map { it.code to it.path })
        val unknownTool = """{"myembroScript":1,"promptId":"p","summary":"","commands":[{"tool":"make_rainbow","args":{}}]}"""
        assertEquals("make_rainbow", parsed(unknownTool).commands.single().tool)
    }

    @Test fun nonObjectJsonFence() {
        assertEquals(Code.NOT_AN_OBJECT, problems("```json\n[1,2]\n```").single().code)
    }
}
