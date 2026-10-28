// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import io.github.linker2k.myembro.engine.agent.AgentScenario
import io.github.linker2k.myembro.engine.agent.AttachmentStore
import io.github.linker2k.myembro.engine.agent.PhotoRaster
import io.github.linker2k.myembro.engine.agent.ToolDispatcher
import io.github.linker2k.myembro.engine.edit.Transform
import io.github.linker2k.myembro.engine.edit.TextObject
import io.github.linker2k.myembro.engine.edit.placedBounds
import io.github.linker2k.myembro.engine.lettering.HersheyFont
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.ui.agent.*
import io.github.linker2k.myembro.ui.settings.EmbroiderySettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.*

/**
 * Mock chatbot use: the app's prompts for three Korean requests (and five with an attached photo) were given once to Claude Code
 * (`claude -p`) and Codex CLI (`codex exec`); their raw replies are stored under
 * src/test/resources/agent/sim and run here through the app parser and the dispatcher dry-run.
 * No LLM is called by the test. `AGENT_SIM_WRITE=1` rewrites the prompt files after a prompt change
 * (then the replies must be collected again: tools/agent_sim_collect.sh).
 */
class AgentChatbotSimulationTest {
    private val dir = File("src/test/resources/agent/sim")
    // Preserve the recorded prompt corpus's physical logo placement (old +0.5-unit Y center).
    // The new import convention shifts that odd-extent local midpoint down by 1 unit.
    private val logo = AgentScenario.builtin("myembro_logo.pes", "logo", "H100").let { p ->
        p.copy(objects = p.objects.map { it.withTransform(it.transform.copy(y = it.transform.y + 1.0)) })
    }
    private val project = logo.copy(objects = logo.objects.map { it.withId("logo-uuid") }.let { objs ->
        objs + TextObject("text-uuid", "myembro", text = "myembro", fontId = HersheyFont.DEFAULT_ID, heightMm = 8.0,
            thread = EmbThread(0xFF1E5AA8.toInt()), transform = Transform(y = 420.0)) })
    private val fonts = listOf(
        AgentFontInfo("gowun-batang", "고운바탕", true, true, 5.0, 60.0),
        AgentFontInfo("jua", "둥근고딕", true, true, 5.0, 60.0),
        AgentFontInfo("noto-sans-kr", "담백한 고딕", true, true, 26.25, 60.0),
        AgentFontInfo(HersheyFont.DEFAULT_ID, "Hershey Sans 1-stroke", false, true, 5.0, 60.0),
    )
    private val designs = Json.parseToJsonElement(File("src/main/assets/samples/manifest.json").readText()).jsonObject
        .getValue("samples").jsonArray.map { it.jsonObject }.map { o ->
            fun v(k: String) = o.getValue(k).jsonPrimitive
            AgentDesignInfo(v("file").content.substringBeforeLast('.'), v("title").content, v("widthMm").double, v("heightMm").double)
        }
    private val summary = AgentProjectSummary.of(project, fonts, designs) { it.placedBounds() }
    private val requests = listOf("로고 아래에 안녕 15mm", "글자 빨간색으로", "전체 가운데", "이 사진을 실루엣 40mm로 로고 옆에",
        "사진을 외곽선으로 50mm, 후프 가운데", "사진을 3색 컬러로 60mm", "사진이 흐릿해. 대비 높여서 실루엣으로",
        "사진을 흑백 분리해서 외곽선으로 30mm 오른쪽 아래")
    private val photoRequests = 3..7
    /** Requests 4–8 have an attached photo (the size of the repository's robot.png): only its id and size are in the prompt. */
    private val attachments = photoRequests.associateWith { listOf(AgentAttachment("a1", 128, 128)) }
    private fun prompt(i: Int) = AgentPromptBuilder.build(summary, requests[i], "p-SIM${i + 1}", attachments = attachments[i].orEmpty())

    @Test fun promptFilesAreTheAppsPrompts() {
        requests.indices.forEach { i ->
            val file = File(dir, "prompt-${i + 1}.txt")
            if (System.getenv("AGENT_SIM_WRITE") == "1") file.writeText(prompt(i).text)
            assertEquals(prompt(i).text, file.readText(), "regenerate with AGENT_SIM_WRITE=1, then collect replies again")
        }
        for (n in photoRequests.map { it + 1 }) {
            val photo = File(dir, "prompt-$n.txt").readText()
            assertTrue("- photo a1 attached (128x128 px)" in photo && "- ${AgentTools.PHOTO_SIGNATURE}" in photo, "prompt-$n")
            assertTrue("filter:original|clean|contrast|threshold" in photo, "prompt-$n")
            // Only the id and the pixel size: no picker URI, file name or image data.
            assertFalse("content://" in photo || "robot" in photo || "base64" in photo || "data:image" in photo, "prompt-$n")
        }
        (1..3).forEach { assertFalse("prepare_photo" in File(dir, "prompt-$it.txt").readText(), "prompt-$it") }
    }

    /** The repository's robot.png as the attached photo a1, the same store as AgentPhotoAttachTest. */
    private val store = AttachmentStore { id, request ->
        if (id != "a1") null else {
            val (w, h, argb) = AgentPhotoAttachTest.decodePng(File("../engine/src/test/resources/image-to-design/robot.png").readBytes())
            PhotoRaster(request.width, request.height, IntArray(request.width * request.height) { i ->
                val p = argb[(i / request.width) * h / request.height * w + (i % request.width) * w / request.width]
                if (request.compositeWhite && (p ushr 24) < 128) -1 else p
            })
        }
    }

    private data class Row(val client: String, val turn: String, val parse: String, val validate: String, val sewout: String, val tools: String)

    /** [turn] is the reply file suffix: "1" is the first answer to prompt 1, "1b" the answer after the user's reply. */
    private fun run(client: String, turn: String): Row = runBlocking {
        val i = turn.take(1).toInt() - 1
        val reply = File(dir, "$client-$turn.txt").readText()
        val parsed = AgentScriptParser.parse(reply)
        if (parsed !is AgentParseResult.Parsed) {
            // Asking back is allowed by the prompt ("ask a short question instead of guessing").
            val noScript = (parsed as? AgentParseResult.Invalid)?.problems?.singleOrNull()?.code == AgentScriptProblem.Code.NO_SCRIPT
            return@runBlocking Row(client, turn, if (noScript && '?' in reply) "QUESTION" else "FAIL ${parsed.javaClass.simpleName}", "-", "-", "-")
        }
        val script = parsed.script
        val session = EditorSession(project)
        val executor = EditorAgentExecutor({ session }, { EmbroiderySettings(machineModel = "se400") }, { listOf(project.hoop) },
            publish = { it() }, kernelFactory = { ToolDispatcher(frames = it, attachmentStore = store) })
        val tools = script.commands.joinToString("+") { it.tool }
        val pid = if (script.promptId == "p-SIM${i + 1}") "" else " (promptId ${script.promptId})"
        when (val r = executor.preview(script, prompt(i).handles, 0)) {
            is AgentPreviewResult.Ready -> Row(client, turn, "OK$pid", "OK", if (r.preview.blockingFindings == null) "PASS" else "BLOCK", tools)
            is AgentPreviewResult.Rejected -> Row(client, turn, "OK$pid", r.problems.joinToString { p ->
                "${p.code}" + (p.index?.let { "@${it + 1}" } ?: "") + (p.message.takeIf { it.isNotBlank() }?.let { ": ${it.take(80)}" } ?: "") }, "-", tools)
            AgentPreviewResult.NotConnected -> error("connected executor expected")
        }.also { assertSame(project, session.project, "dry-run must not change the project") }
    }

    @Test fun storedRepliesThroughParserAndDryRun() {
        val rows = listOf("claude", "codex").flatMap { c -> (listOf("1", "1b", "2", "3") + photoRequests.map { "${it + 1}" }).map { t -> run(c, t) } }
        val table = buildString {
            appendLine("| client | request | parse | validate (dry-run) | sewout | tools |")
            appendLine("|---|---|---|---|---|---|")
            rows.forEach { appendLine("| ${it.client} | ${it.turn}. ${requests[it.turn.take(1).toInt() - 1]}${if (it.turn.endsWith("b")) " (2nd turn)" else ""} | ${it.parse} | ${it.validate} | ${it.sewout} | ${it.tools} |") }
        }
        File("build/agent-sim").mkdirs(); File("build/agent-sim/results.md").writeText(table)
        println(table)
        val photo = rows.filter { it.turn.take(1).toInt() - 1 in photoRequests }
        val photoTable = buildString {
            appendLine("| client | request | parse | validate (dry-run) | sewout | tools |")
            appendLine("|---|---|---|---|---|---|")
            photo.forEach { appendLine("| ${it.client} | ${it.turn}. ${requests[it.turn.toInt() - 1]} | ${it.parse} | ${it.validate} | ${it.sewout} | ${it.tools} |") }
        }
        File("build/agent-sim/photo-results.md").writeText(photoTable)
        println(photoTable)
        // Stored outcomes; a change in prompt, parser or dispatcher shows up here. add_text is implemented
        // (agent/dispatcher 0258c70) and its arguments pass the schema, but this JVM test has only the engine's
        // BundledFontResolver: the bundled TTF "jua" lives in Android resources, so FONT_NOT_FOUND here is the
        // environment, not the prompt or parser. On the phone AndroidTextFontResolver resolves it.
        assertEquals(listOf(
            "claude 1 QUESTION -", "claude 1b OK FONT_NOT_FOUND@2: Font is not installed: jua", "claude 2 OK OK PASS", "claude 3 OK OK PASS",
            "claude 4 OK OK PASS", "claude 5 OK OK PASS", "claude 6 OK OK PASS", "claude 7 OK OK PASS", "claude 8 OK OK PASS",
            "codex 1 QUESTION -", "codex 1b OK FONT_NOT_FOUND@2: Font is not installed: jua", "codex 2 OK OK PASS", "codex 3 OK OK PASS",
            // Codex asks back on 4 (room beside the logo) and 8 (is 30mm the width or the offset?).
            "codex 4 QUESTION -", "codex 5 OK OK PASS", "codex 6 OK OK PASS", "codex 7 OK OK PASS", "codex 8 QUESTION -",
        ), rows.map { listOf(it.client, it.turn, it.parse, it.validate, it.sewout.takeIf { s -> s != "-" }).filterNotNull().joinToString(" ") })
    }
}
