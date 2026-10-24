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
        """{"tool":"recolor_object","args":{"objectId":"o1","threadIndex":0,"threadColor":"#112233"}},{"tool":"send_usb","args":{}}""")

    @Test fun previewChangesNothingThenApplyIsOneUndo() = runBlocking {
        val ready = assertIs<AgentPreviewResult.Ready>(executor().preview(alignRecolor, handles, 0))
        val preview = ready.preview
        assertNull(preview.blockingFindings, preview.toString())
        assertSame(base, session.project); assertEquals(0, session.history.undoCount)
        assertSame(base, preview.before)
        assertEquals(0xFF112233.toInt(), (preview.after!!.find("logo") as StitchObject).source.threads[0].color)
        assertEquals(listOf("align_objects", "recolor_object"), preview.changes.map { it.tool })
        assertEquals(listOf("send_usb"), preview.followUps)

        assertEquals(AgentApplyResult.Applied(listOf("send_usb")), executor().apply(alignRecolor, handles, 0, removalConfirmed = false))
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

    @Test fun staleRevisionAndRemovalConsent() = runBlocking {
        assertEquals("REVISION_MISMATCH", assertIs<AgentPreviewResult.Rejected>(executor().preview(alignRecolor, handles, 5)).problems.single().code)
        val remove = script("""{"tool":"remove_objects","args":{"objectIds":["o1"]}}""")
        val preview = assertIs<AgentPreviewResult.Ready>(executor().preview(remove, handles, 0)).preview
        assertEquals(listOf("myembro_logo.pes"), preview.removals)
        assertIs<AgentApplyResult.Rejected>(executor().apply(remove, handles, 0, removalConfirmed = false))
        assertSame(base, session.project)
    }

    @Test fun unknownMachineIsReportedBeforeRunning() = runBlocking {
        assertEquals("MACHINE_UNKNOWN", assertIs<AgentPreviewResult.Rejected>(executor("nope").preview(alignRecolor, handles, 0)).problems.single().code)
    }
}
