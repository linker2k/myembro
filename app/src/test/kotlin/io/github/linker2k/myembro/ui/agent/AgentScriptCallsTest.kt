// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.agent

import io.github.linker2k.myembro.engine.edit.Project
import io.github.linker2k.myembro.engine.edit.TextObject
import kotlinx.serialization.json.*
import kotlin.test.*

class AgentScriptCallsTest {
    private val handles = AgentHandles(mapOf("o1" to "uuid-logo", "o2" to "uuid-name"), mapOf("f1" to "jua"))
    private fun script(commands: String) = (AgentScriptParser.parse(
        """{"myembroScript":1,"promptId":"p-1","summary":"s","commands":[$commands]}""") as AgentParseResult.Parsed).script

    @Test fun handlesExpandAndTheAppOwnsTheTransaction() {
        val calls = AgentScriptCalls.of(script(
            """{"tool":"align_objects","args":{"objectIds":["o1","o2"],"alignment":"CENTER_X"}},""" +
            """{"tool":"duplicate_object","args":{"sourceId":"o1","newObjectId":"new:copy","transform":{"xMm":0,"yMm":0,"rotationDeg":0,"scaleXPercent":100,"scaleYPercent":100}}},""" +
            """{"tool":"add_text","args":{"objectId":"new:t","text":"o1","fontId":"f1","fontRevision":"f1","heightMm":10,"threadColor":"#000000","singleLineBold":false}}"""),
            handles, "agent-p-1")
        assertEquals(emptyList(), calls.problems)
        val (align, duplicate, text) = calls.calls.map { it.arguments.jsonObject }
        assertEquals(listOf("uuid-logo", "uuid-name"), align.getValue("objectIds").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("agent-p-1", align.getValue("transactionId").jsonPrimitive.content)
        assertEquals("agent-agent-p-1-copy", duplicate.getValue("newObjectId").jsonPrimitive.content)
        assertEquals("jua", text.getValue("fontId").jsonPrimitive.content)
        assertEquals("o1", text.getValue("text").jsonPrimitive.content) // only id fields are expanded
        assertEquals(listOf(0, 1, 2), calls.indices)
    }

    @Test fun followUpsNeverEnterTheTurn() {
        val calls = AgentScriptCalls.of(script(
            """{"tool":"send_usb","args":{}},{"tool":"recolor_object","args":{"objectId":"o1","threadIndex":0,"threadColor":"#112233"}},""" +
            """{"tool":"save_pes","args":{}},{"tool":"send_usb","args":{}}"""), handles, "t")
        assertEquals(listOf("recolor_object"), calls.calls.map { it.name })
        assertEquals(listOf(1), calls.indices)
        assertEquals(listOf("send_usb", "save_pes"), calls.followUps)
    }

    @Test fun unknownHandlesAreProblemsAndOtherToolsKeepTheirArguments() {
        val calls = AgentScriptCalls.of(script(
            """{"tool":"remove_objects","args":{"objectIds":["o7"]}},{"tool":"delete_font","args":{"fontId":"x"}}"""), handles, "t")
        assertEquals(listOf(AgentProblem("UNKNOWN_HANDLE", 0, "remove_objects", "o7")), calls.problems)
        assertNull(calls.calls[1].arguments.jsonObject["transactionId"])
    }

    @Test fun changeRowsUseObjectNames() {
        val before = Project(objects = listOf(TextObject("uuid-logo", "Logo"), TextObject("uuid-name", "Name")))
        val calls = AgentScriptCalls.of(script(
            """{"tool":"align_objects","args":{"objectIds":["o1","o2"],"alignment":"CENTER_X"}},""" +
            """{"tool":"recolor_object","args":{"objectId":"o1","threadIndex":0,"threadColor":"#d0021b"}},""" +
            """{"tool":"remove_objects","args":{"objectIds":["o2"]}}"""), handles, "t")
        assertEquals(listOf(
            AgentChange(AgentChange.Kind.CHANGE, "align_objects", listOf("Logo", "Name"), listOf("CENTER_X")),
            AgentChange(AgentChange.Kind.CHANGE, "recolor_object", listOf("Logo"), listOf("1", "#D0021B")),
            AgentChange(AgentChange.Kind.REMOVE, "remove_objects", listOf("Name"))), calls.changes(before))
        assertEquals(listOf("Name"), calls.removedNames(before))
    }
}
