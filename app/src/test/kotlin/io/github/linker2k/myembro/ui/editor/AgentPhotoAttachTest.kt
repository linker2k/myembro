// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import io.github.linker2k.myembro.engine.agent.AgentScenario
import io.github.linker2k.myembro.ui.agent.*
import io.github.linker2k.myembro.ui.settings.EmbroiderySettings
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Base64
import kotlin.test.*

/** "Attach photo" in Ask AI: only an id and a pixel size leave the app, never the image (docs/agent/mobile-standalone.md). */
class AgentPhotoAttachTest {
    private val base = AgentScenario.builtin("myembro_logo.pes", "logo", "H100")
    // The repository's own test image, never a personal photo.
    private val image = File("../engine/src/test/resources/image-to-design/robot.png")
    /** Width and height from the PNG IHDR chunk (bytes 16–23, big-endian). */
    private val picked = image.readBytes().let { b -> fun int(at: Int) = (0..3).fold(0) { v, i -> v shl 8 or (b[at + i].toInt() and 0xFF) }
        object { val width = int(16); val height = int(20) } }
    private val uri = "content://media/picker/0/com.android.providers.media.photopicker/media/1000000042"

    private fun controller(executor: AgentScriptExecutor = NotConnectedAgentScriptExecutor) = AgentAssistController(executor)
    private suspend fun AgentAssistController.withPhoto() = apply {
        openAsk(base); request = "이 사진으로 도안 만들어 줘"; attach(uri, picked.width, picked.height)
    }

    @Test fun promptNamesTheAttachmentAndItsSizeOnly() = runBlocking {
        val c = controller().withPhoto()
        val prompt = assertNotNull(c.prompt)
        assertTrue("- photo a1 attached (${picked.width}x${picked.height} px)" in prompt.text, prompt.text)
        assertTrue("- ${AgentTools.PHOTO_SIGNATURE}" in prompt.text)
        assertEquals(listOf(AgentAttachment("a1", picked.width, picked.height)), prompt.attachments)
        assertEquals(mapOf("a1" to "a1"), prompt.handles.attachments)
        // Neither the picked location nor any of the image's bytes.
        assertFalse("content://" in prompt.text || "1000000042" in prompt.text || "robot" in prompt.text)
        val head = Base64.getEncoder().encodeToString(image.readBytes().copyOf(24))
        assertFalse(head.take(16) in prompt.text)
    }

    @Test fun withoutAPhotoThePromptHasNoPhotoTool() = runBlocking {
        val c = controller().apply { openAsk(base); request = "x" }
        assertFalse("prepare_photo" in c.prompt!!.text)
        c.attach(uri, 10, 20); c.detach("a1")
        assertFalse("prepare_photo" in c.prompt!!.text || "a1" in c.prompt!!.text)
    }

    @Test fun theShareIsPlainTextWithoutAnImage() = runBlocking {
        val prompt = controller().withPhoto().prompt!!
        assertEquals("text/plain", AgentShare.MIME_TYPE)
        assertEquals(mapOf("android.intent.extra.TEXT" to prompt.text), AgentShare.extras(prompt))
        assertFalse("android.intent.extra.STREAM" in AgentShare.extras(prompt))
        // The share sheet builds its intent from AgentShare only: no stream, no image type, no URI grant.
        val screen = File("src/main/kotlin/io/github/linker2k/myembro/ui/agent/AgentScreens.kt").readText()
        for (forbidden in listOf("EXTRA_STREAM", "setType(\"image", "FLAG_GRANT_READ_URI_PERMISSION", "setClipData", "putParcelable"))
            assertFalse(forbidden in screen, forbidden)
        assertTrue("setType(AgentShare.MIME_TYPE)" in screen)
    }

    @Test fun theSourceStaysInTheAppWithThePendingPrompt() = runBlocking {
        val c = controller().withPhoto()
        c.attach(uri + "3", 30, 40)
        assertTrue("- photo a2 attached (30x40 px)" in c.prompt!!.text)
        c.sent()
        assertEquals(uri, c.source("a1")); assertEquals(uri + "3", c.source("a2"))
        assertEquals(emptyList(), c.attachments)
        c.cancelPending()
        assertNull(c.source("a1"))
    }

    private val session = EditorSession(base)
    private fun executor() = EditorAgentExecutor({ session }, { EmbroiderySettings(machineModel = "se400") },
        { listOf(base.hoop) }, publish = { block -> block() })
    private fun script(commands: String) = (AgentScriptParser.parse(
        """{"myembroScript":1,"promptId":"p-T","summary":"s","commands":[$commands]}""") as AgentParseResult.Parsed).script
    private fun photo(id: String) = script("""{"tool":"prepare_photo","args":{"attachmentId":"$id","frameId":"H100","style":"OUTLINE",""" +
        """"detail":1,"colorCount":2,"grayscale":false,"widthMm":40,"crop":{"zoom":1,"panX":0,"panY":0}}}""")
    private val handles = AgentHandles(mapOf("o1" to "logo"), emptyMap(), attachments = mapOf("a1" to "a1"))

    @Test fun prepareShowsNotImplementedUntilThePhotoToolIsConnected() = runBlocking {
        val problem = assertIs<AgentPreviewResult.Rejected>(executor().preview(photo("a1"), handles, 0)).problems.single()
        assertEquals(AgentProblem("NOT_IMPLEMENTED", 0, "prepare_photo", problem.message), problem)
        assertEquals(base, session.project)
    }

    @Test fun onlyAPhotoAttachedToThisPromptIsAccepted() = runBlocking {
        for (id in listOf("a2", uri, "/sdcard/Pictures/x.jpg", "https://example.com/x.jpg")) {
            val problems = assertIs<AgentPreviewResult.Rejected>(executor().preview(photo(id), handles, 0), id).problems
            assertEquals(listOf(AgentProblem("UNKNOWN_HANDLE", 0, "prepare_photo", id)), problems)
        }
    }
}
