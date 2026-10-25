// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import io.github.linker2k.myembro.engine.agent.AgentScenario
import io.github.linker2k.myembro.engine.edit.StitchObject
import io.github.linker2k.myembro.ui.agent.*
import io.github.linker2k.myembro.ui.settings.EmbroiderySettings
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class EditorAgentExecutorTest {
    private val base = AgentScenario.builtin("myembro_logo.pes", "logo", "H100")
    private val session = EditorSession(base)
    private var published = 0
    private fun executor(machine: String = "se400") = EditorAgentExecutor({ session }, { EmbroiderySettings(machineModel = machine) },
        { listOf(base.hoop) }, publish = { block -> published++; block() })
    private val handles = AgentHandles(mapOf("o1" to "logo"), emptyMap())
    private fun script(commands: String) = (AgentScriptParser.parse(
        """{"myembroScript":1,"promptId":"p-T","summary":"s","commands":[$commands]}""") as AgentParseResult.Parsed).script
    private val alignRecolor = script("""{"tool":"align_objects","args":{"objectIds":["o1"],"alignment":"LEFT"}},""" +
        """{"tool":"recolor_object","args":{"objectId":"o1","threadIndex":0,"threadColor":"#112233"}}""")

    @Test fun previewChangesNothingThenApplyIsOneUndo() = runBlocking {
        val ready = assertIs<AgentPreviewResult.Ready>(executor().preview(alignRecolor, handles, 0))
        val preview = ready.preview
        assertNull(preview.blockingFindings, preview.toString())
        assertSame(base, session.project); assertEquals(0, session.history.undoCount)
        assertSame(base, preview.before)
        assertEquals(0xFF112233.toInt(), (preview.after!!.find("logo") as StitchObject).source.threads[0].color)
        assertEquals(listOf("align_objects", "recolor_object"), preview.changes.map { it.tool })
        assertEquals(emptyList(), preview.separate)

        assertEquals(AgentApplyResult.Applied, executor().apply(alignRecolor, handles, 0, approvedRemovals = emptySet()))
        assertEquals(1, published); assertEquals(1, session.history.undoCount)
        assertEquals(0xFF112233.toInt(), (session.project.find("logo") as StitchObject).source.threads[0].color)
        assertTrue(session.stepHistory(redo = false))
        assertSame(base, session.project)
        println("AGENT_EXECUTOR PASS: preview unchanged; apply = 1 undo step; undo restores original")
    }

    // add_text is implemented since agent/dispatcher 0258c70; an unknown font is now the rejection.
    @Test fun addTextWithUnknownFontIsRejectedWithItsScriptIndex() = runBlocking {
        val s = script("""{"tool":"send_usb","args":{}},{"tool":"add_text","args":{"objectId":"new:t","text":"Mina","fontId":"hershey","fontRevision":"x","heightMm":10,"threadColor":"#000000","singleLineBold":false}}""")
        val rejected = assertIs<AgentPreviewResult.Rejected>(executor().preview(s, handles, 0))
        assertEquals(listOf("FONT_NOT_FOUND" to 1), rejected.problems.map { it.code to it.index })
        assertSame(base, session.project)
    }

    @Test fun addTextWithAFontHandleGetsTheResolverRevisionAndIsOneUndo() = runBlocking {
        val h = AgentHandles(mapOf("o1" to "logo"), mapOf("f1" to io.github.linker2k.myembro.engine.lettering.HersheyFont.DEFAULT_ID))
        val s = script("""{"tool":"add_text","args":{"objectId":"new:hi","text":"Hi","fontId":"f1","fontRevision":"f1","heightMm":10,"threadColor":"#D0021B","singleLineBold":false}},""" +
            """{"tool":"set_object_transforms","args":{"transforms":[{"objectId":"new:hi","xMm":0,"yMm":40,"rotationDeg":0,"scaleXPercent":100,"scaleYPercent":100}]}}""")
        val preview = assertIs<AgentPreviewResult.Ready>(executor().preview(s, h, 0), "preview").preview
        assertNull(preview.blockingFindings, preview.toString())
        assertEquals(2, preview.after!!.objects.size); assertSame(base, session.project)
        assertIs<AgentApplyResult.Applied>(executor().apply(s, h, 0, approvedRemovals = emptySet()))
        assertEquals("Hi", (session.project.find("agent-agent-p-T-hi") as io.github.linker2k.myembro.engine.edit.TextObject).text)
        assertTrue(session.stepHistory(redo = false)); assertSame(base, session.project)
        assertTrue(session.stepHistory(redo = true)); assertEquals(2, session.project.objects.size)
    }

    @Test fun staleRevisionAndRemovalConsent() = runBlocking {
        assertEquals("REVISION_MISMATCH", assertIs<AgentPreviewResult.Rejected>(executor().preview(alignRecolor, handles, 5)).problems.single().code)
        val remove = script("""{"tool":"remove_objects","args":{"objectIds":["o1"]}}""")
        val preview = assertIs<AgentPreviewResult.Ready>(executor().preview(remove, handles, 0)).preview
        assertEquals(listOf(AgentRemoval(0, listOf("myembro_logo.pes"))), preview.removals)
    }

    private val twoObjects = base.copy(objects = base.objects + base.objects.single().withId("copy") + base.objects.single().withId("copy2"))
    private val twoSession = EditorSession(twoObjects)
    private fun twoExecutor() = EditorAgentExecutor({ twoSession }, { EmbroiderySettings(machineModel = "se400") }, { listOf(base.hoop) },
        publish = { block -> block() })
    private val twoHandles = AgentHandles(mapOf("o1" to "logo", "o2" to "copy", "o3" to "copy2"), emptyMap())
    // recolor + two separate removals: per-item approvals, never a partial apply.
    private val recolorRemoveTwo = script("""{"tool":"recolor_object","args":{"objectId":"o1","threadIndex":0,"threadColor":"#112233"}},""" +
        """{"tool":"remove_objects","args":{"objectIds":["o2"]}},{"tool":"remove_objects","args":{"objectIds":["o3"]}}""")

    @Test fun noTicksAppliesNothing() = runBlocking {
        val preview = assertIs<AgentPreviewResult.Ready>(twoExecutor().preview(recolorRemoveTwo, twoHandles, 0)).preview
        assertEquals(listOf(1, 2), preview.removals.map { it.callIndex })
        assertEquals(listOf("logo"), preview.after!!.objects.map { it.id }) // the picture shows every removal
        for (ticked in listOf(emptySet(), setOf(1), setOf(2))) {
            val r = assertIs<AgentApplyResult.Rejected>(twoExecutor().apply(recolorRemoveTwo, twoHandles, 0, ticked), "$ticked")
            assertEquals("CONFIRMATION_REQUIRED", r.problems.single().code, "$ticked")
            assertSame(twoObjects, twoSession.project); assertEquals(0, twoSession.history.undoCount) // not even the recolor
        }
    }

    @Test fun tickedRemovalsApplyAsOneUndo() = runBlocking {
        assertEquals(AgentApplyResult.Applied, twoExecutor().apply(recolorRemoveTwo, twoHandles, 0, setOf(1, 2)))
        assertEquals(listOf("logo"), twoSession.project.objects.map { it.id }); assertEquals(1, twoSession.history.undoCount)
        assertEquals(0xFF112233.toInt(), (twoSession.project.objects.single() as StitchObject).source.threads[0].color)
        assertTrue(twoSession.stepHistory(redo = false)); assertSame(twoObjects, twoSession.project)
    }

    @Test fun anApprovalWithAnotherHashIsRefused() {
        val calls = AgentScriptCalls.of(recolorRemoveTwo, twoHandles, "agent-p-T").calls
        val export = kotlinx.serialization.json.Json.parseToJsonElement("""{"machineId":"se400","frameId":"${EditorAgentExecutor.frameId(base.hoop)}","pesVersion":"V1"}""").let { it as kotlinx.serialization.json.JsonObject }
        val kernel = io.github.linker2k.myembro.engine.agent.ToolDispatcher(frames = mapOf(EditorAgentExecutor.frameId(base.hoop) to base.hoop))
        val good = listOf(1, 2).map { io.github.linker2k.myembro.engine.agent.ScriptApprovals.request("agent-p-T", 0, calls, it) }
        for (bad in listOf(good[0].copy(argumentsHash = "0".repeat(64)), io.github.linker2k.myembro.engine.agent.ScriptApprovals.request("agent-p-T", 1, calls, 2))) {
            val r = twoSession.runCommandScript(kernel, "agent-p-T", 0, calls, export, approvals = listOf(good[0], good[1]).map { if (it.callIndex == bad.callIndex) bad else it })
            assertEquals("INVALID_APPROVAL", r.code, r.output.toString()); assertSame(twoObjects, twoSession.project)
        }
        val ok = twoSession.runCommandScript(kernel, "agent-p-T", 0, calls, export, approvals = good)
        assertTrue(ok.ok, ok.output.toString())
    }

    @Test fun aScriptWithASeparateToolIsNeverApplied() = runBlocking {
        val s = script("""{"tool":"recolor_object","args":{"objectId":"o1","threadIndex":0,"threadColor":"#112233"}},{"tool":"send_usb","args":{}},{"tool":"restore_backup","args":{}}""")
        val preview = assertIs<AgentPreviewResult.Ready>(executor().preview(s, handles, 0)).preview
        assertEquals(listOf("send_usb", "restore_backup"), preview.separate)
        val r = assertIs<AgentApplyResult.Rejected>(executor().apply(s, handles, 0, emptySet()))
        assertEquals(listOf("SCRIPT_TOOL_FORBIDDEN" to 1, "SCRIPT_TOOL_FORBIDDEN" to 2), r.problems.map { it.code to it.index })
        assertSame(base, session.project); assertEquals(0, session.history.undoCount)
    }

    @Test fun unknownMachineIsReportedBeforeRunning() = runBlocking {
        assertEquals("MACHINE_UNKNOWN", assertIs<AgentPreviewResult.Rejected>(executor("nope").preview(alignRecolor, handles, 0)).problems.single().code)
    }
}
