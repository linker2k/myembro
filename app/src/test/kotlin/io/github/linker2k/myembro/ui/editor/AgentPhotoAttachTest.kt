// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import io.github.linker2k.myembro.engine.agent.AgentScenario
import io.github.linker2k.myembro.engine.agent.AttachmentStore
import io.github.linker2k.myembro.engine.agent.PhotoRaster
import io.github.linker2k.myembro.engine.agent.ToolDispatcher
import io.github.linker2k.myembro.engine.edit.StitchObject
import io.github.linker2k.myembro.ui.agent.*
import io.github.linker2k.myembro.ui.settings.EmbroiderySettings
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Base64
import kotlin.test.*

/**
 * "Attach photo" in Ask AI: only an id and a pixel size leave the app, never the image; prepare_photo
 * runs before the turn and add_generated_art adds its result as one undo step.
 */
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
        assertTrue("- photo width: 20-99.9mm, cropped to the hoop shape" in prompt.text, prompt.text)
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

    // An empty hoop: the photo alone is checked, not its overlap with the logo.
    private val empty = base.copy(objects = emptyList())
    private val session = EditorSession(empty)
    private var reads = 0
    /** The repository image as the picked photo: RGBA PNG decoded here, nearest sampling, white paper when asked. */
    private val store = AttachmentStore { id, request ->
        if (id != "a1") null else {
            reads++
            val (w, h, argb) = decodePng(image.readBytes())
            PhotoRaster(request.width, request.height, IntArray(request.width * request.height) { i ->
                val p = argb[(i / request.width) * h / request.height * w + (i % request.width) * w / request.width]
                if (request.compositeWhite && (p ushr 24) < 128) -1 else p
            })
        }
    }
    private fun executor() = EditorAgentExecutor({ session }, { EmbroiderySettings(machineModel = "se400") },
        { listOf(base.hoop) }, publish = { block -> block() }, kernelFactory = { ToolDispatcher(frames = it, attachmentStore = store) })
    private fun script(commands: String) = (AgentScriptParser.parse(
        """{"myembroScript":1,"promptId":"p-T","summary":"s","commands":[$commands]}""") as AgentParseResult.Parsed).script
    private fun prepare(id: String = "a1", mode: String = "silhouette", colors: Int = 2, width: Double = 40.0, filter: String = "original") =
        """{"tool":"prepare_photo","args":{"attachmentId":"$id","mode":"$mode","colors":$colors,"widthMm":$width,"filter":"$filter"}}"""
    private fun add(result: String = "r1") = """{"tool":"add_generated_art","args":{"resultId":"$result","objectId":"new:photo","name":"Robot"}}"""
    private val handles = AgentHandles(emptyMap(), emptyMap(), attachments = mapOf("a1" to "a1"))

    private fun previewApplyUndo(mode: String) = runBlocking {
        val s = script(prepare(mode = mode) + "," + add())
        val executor = executor()
        val preview = assertIs<AgentPreviewResult.Ready>(executor.preview(s, handles, 0), mode).preview
        assertNull(preview.blockingFindings, "$mode: ${preview.blockingFindings}")
        assertSame(empty, session.project); assertEquals(0, session.history.undoCount)
        assertEquals(emptyList(), preview.before!!.objects)
        assertEquals(listOf("agent-agent-p-T-photo"), preview.after!!.objects.map { it.id })
        assertEquals(listOf(AgentChange(AgentChange.Kind.ADD, "add_generated_art", listOf("photo"), listOf("Robot"))), preview.changes)
        assertEquals(AgentApplyResult.Applied, executor.apply(s, handles, 0, approvedRemovals = emptySet()))
        // Preview and apply add the same prepared result: the photo is read once.
        assertEquals(1, reads)
        assertEquals(1, session.history.undoCount)
        assertIs<StitchObject>(session.project.find("agent-agent-p-T-photo"))
        assertTrue(session.stepHistory(redo = false))
        assertSame(empty, session.project)
        println("AGENT_PHOTO $mode PASS: preview before/after, sewout ok, apply = 1 undo step, undo restores")
    }

    @Test fun silhouettePreviewApplyUndo() = previewApplyUndo("silhouette")
    @Test fun twoColorPreviewApplyUndo() = previewApplyUndo("color")

    @Test fun preparationProblemsKeepTheirScriptIndex() = runBlocking {
        suspend fun problem(vararg commands: String) =
            assertIs<AgentPreviewResult.Rejected>(executor().preview(script(commands.joinToString(",")), handles, 0)).problems.single()
        // 20mm is PICTURE_MIN_WIDTH_MM: 0.1mm below is refused before the photo is read.
        assertEquals("WIDTH_OUT_OF_RANGE" to 1, problem(add(), prepare(width = 19.9)).let { it.code to it.index })
        assertEquals(AgentProblem("UNKNOWN_HANDLE", 1, "add_generated_art", "r2"), problem(prepare(), add("r2")))
        // Attached to the prompt, but the app has no picture for it any more.
        assertEquals("ATTACHMENT_NOT_FOUND", assertIs<AgentPreviewResult.Rejected>(executor().preview(script(prepare("a1") + "," + add()),
            handles.copy(attachments = mapOf("a1" to "a7")), 0)).problems.single().code)
        assertSame(empty, session.project)
    }

    @Test fun thresholdIsForSilhouetteAndOutlineOnly() = runBlocking {
        for (mode in listOf("color", "bw")) assertEquals("FILTER_NOT_SUPPORTED",
            assertIs<AgentPreviewResult.Rejected>(executor().preview(script(prepare(mode = mode, filter = "threshold") + "," + add()), handles, 0))
                .problems.single().code, mode)
        // Refused before the photo is read.
        assertEquals(0, reads)
        assertEquals("INVALID_ARGUMENTS", assertIs<AgentPreviewResult.Rejected>(executor().preview(
            script(prepare(filter = "sharpen") + "," + add()), handles, 0)).problems.single().code)
        val ready = assertIs<AgentPreviewResult.Ready>(executor().preview(script(prepare(filter = "threshold") + "," + add()), handles, 0))
        assertNull(ready.preview.blockingFindings)
    }

    @Test fun onlyAPhotoAttachedToThisPromptIsAccepted() = runBlocking {
        for (id in listOf("a2", uri, "/sdcard/Pictures/x.jpg", "https://example.com/x.jpg")) {
            val problems = assertIs<AgentPreviewResult.Rejected>(executor().preview(script(prepare(id)), handles, 0), id).problems
            assertEquals(listOf(AgentProblem("UNKNOWN_HANDLE", 0, "prepare_photo", id)), problems)
        }
        assertEquals(0, reads)
    }

    internal companion object {
        /** 8-bit RGBA, non-interlaced PNG (what tools/image-to-design/generate-inputs.py writes) to ARGB. */
        fun decodePng(bytes: ByteArray): Triple<Int, Int, IntArray> {
            val data = java.io.ByteArrayOutputStream(); var at = 8; var w = 0; var h = 0
            fun int(i: Int) = (0..3).fold(0) { v, k -> v shl 8 or (bytes[i + k].toInt() and 0xFF) }
            while (at < bytes.size) {
                val length = int(at); val type = String(bytes, at + 4, 4, Charsets.US_ASCII)
                if (type == "IHDR") { w = int(at + 8); h = int(at + 12); require(bytes[at + 16] == 8.toByte() && bytes[at + 17] == 6.toByte()) }
                if (type == "IDAT") data.write(bytes, at + 8, length)
                at += 12 + length
            }
            val raw = java.util.zip.InflaterInputStream(data.toByteArray().inputStream()).readBytes()
            val stride = w * 4; val out = ByteArray(h * stride)
            for (y in 0 until h) {
                val filter = raw[y * (stride + 1)].toInt()
                for (x in 0 until stride) {
                    val v = raw[y * (stride + 1) + 1 + x].toInt() and 0xFF
                    val a = if (x >= 4) out[y * stride + x - 4].toInt() and 0xFF else 0
                    val b = if (y > 0) out[(y - 1) * stride + x].toInt() and 0xFF else 0
                    val c = if (x >= 4 && y > 0) out[(y - 1) * stride + x - 4].toInt() and 0xFF else 0
                    val p = a + b - c; val pa = kotlin.math.abs(p - a); val pb = kotlin.math.abs(p - b); val pc = kotlin.math.abs(p - c)
                    out[y * stride + x] = (v + when (filter) { 1 -> a; 2 -> b; 3 -> (a + b) / 2
                        4 -> if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c; else -> 0 }).toByte()
                }
            }
            return Triple(w, h, IntArray(w * h) { i ->
                val o = i * 4; fun ch(k: Int) = out[o + k].toInt() and 0xFF
                (ch(3) shl 24) or (ch(0) shl 16) or (ch(1) shl 8) or ch(2)
            })
        }
    }
}
