// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.relay

import io.github.linker2k.myembro.engine.agent.AgentScenario
import io.github.linker2k.myembro.engine.edit.TextObject
import io.github.linker2k.myembro.engine.lettering.HersheyFont
import io.github.linker2k.myembro.ui.editor.EditorAgentExecutor
import io.github.linker2k.myembro.ui.editor.EditorSession
import io.github.linker2k.myembro.ui.settings.EmbroiderySettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*

/**
 * Relay edit phase, app side, against a fake relay (MockWebServer WebSocket): each edit call runs on the
 * editor's own EditorSession through EditorAgentExecutor, as Ask AI Apply does. No network, no LLM.
 */
class RelayEditTest {
    private val server = MockWebServer().apply { start() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val base = AgentScenario.builtin("myembro_logo.pes", "logo", "H100")
    private val session = EditorSession(base)
    private var published = 0
    private val executor = EditorAgentExecutor({ session }, { EmbroiderySettings(machineModel = "se400") }, { listOf(base.hoop) },
        publish = { block -> published++; block() })
    private var answer: suspend (List<String>) -> Boolean? = { error("no removal expected") }
    private val asked = mutableListOf<List<String>>()

    /** The editor as EditorViewModel offers it, minus the main-thread hops. */
    private val editorDocument = object : RelayDocument {
        override suspend fun read() = RelayProject(session.project, session.history.revision)
        override val editor = object : RelayEditor {
            override suspend fun query(tool: String, args: JsonObject) = executor.relayQuery(tool, args)
            override suspend fun edit(tool: String, args: JsonObject) = executor.relayEdit(tool, args) { names -> asked += names; answer(names) }
        }
    }

    @AfterTest fun stop() { scope.cancel(); server.shutdown() }

    private class Relay : WebSocketListener() {
        val opened = LinkedBlockingQueue<WebSocket>()
        val messages = LinkedBlockingQueue<JsonObject>()
        override fun onOpen(webSocket: WebSocket, response: Response) { opened += webSocket }
        override fun onMessage(webSocket: WebSocket, text: String) { messages += Json.parseToJsonElement(text).jsonObject }
        fun socket() = assertNotNull(opened.poll(5, TimeUnit.SECONDS), "app did not connect")
        fun next() = assertNotNull(messages.poll(30, TimeUnit.SECONDS), "no message from the app")
    }

    /** A connected fake relay; [call] sends one tools/call and returns (isError, the app's JSON text). */
    private inner class Connected(document: RelayDocument?) {
        private val relay = Relay().also { server.enqueue(MockResponse().withWebSocketUpgrade(it)) }
        private val socket: WebSocket
        val hello: JsonObject
        private var n = 0
        init {
            RelayClient(OkHttpClient(), server.url("/app").toString().replaceFirst("http", "ws"), "k", "0.3.0-test",
                RelayToolbox({ document }, listOf(ProjectRelayTools(render = { _, w -> png(w) }))), scope).start()
            socket = relay.socket(); hello = relay.next()
        }
        fun call(tool: String, args: String = "{}"): Pair<Boolean, JsonObject> {
            val id = "c${n++}"
            socket.send("""{"type":"call","id":"$id","tool":"$tool","args":$args}""")
            val r = relay.next()
            assertEquals(id, r.getValue("id").jsonPrimitive.content)
            val c = r.getValue("content").jsonArray.single().jsonObject
            // Edit and read results are JSON; refusals before any tool runs are plain text.
            val body = if (c.getValue("type").jsonPrimitive.content == "image") c else c.getValue("text").jsonPrimitive.content
                .let { t -> runCatching { Json.parseToJsonElement(t).jsonObject }.getOrElse { buildJsonObject { put("text", t) } } }
            return r.getValue("isError").jsonPrimitive.boolean to body
        }
    }

    private fun png(width: Int) = buildJsonObject { put("type", "image"); put("data", "iVBORw0KGgo="); put("mimeType", "image/png"); put("w", width) }
    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
    private fun objects(r: JsonObject) = r.getValue("project").jsonObject.getValue("objects").jsonArray.map { it.jsonObject }

    private fun addText(relay: Connected, text: String = "Hi", id: String = "hi"): Pair<Boolean, JsonObject> {
        // The font and its exact revision come from list_fonts on the editor's kernel, as a chatbot reads them.
        val (fontError, fonts) = relay.call("list_fonts", """{"text":"$text","filters":[],"installedOnly":true}""")
        assertFalse(fontError, fonts.toString())
        val font = fonts.getValue("fonts").jsonArray.map { it.jsonObject }.first { it.str("fontId") == HersheyFont.DEFAULT_ID }
        return relay.call("add_text", """{"objectId":"$id","text":"$text","fontId":"${HersheyFont.DEFAULT_ID}","fontRevision":"${font.str("fontRevision")}",""" +
            """"heightMm":10,"threadColor":"#D0021B","singleLineBold":false}""")
    }

    @Test fun helloOffersReadsAndEveryAskAiEditButNotPhotosOrSeparateActions() {
        val names = Connected(editorDocument).hello.getValue("tools").jsonArray.map { it.jsonObject.str("name") }
        assertEquals(listOf("get_project", "render_preview", "list_fonts", "list_builtin_designs", "get_settings", "add_design", "add_text",
            "replace_text", "set_object_transforms", "transform_objects", "mirror_objects", "center_objects", "align_objects",
            "distribute_objects", "duplicate_object", "reorder_object", "set_object_visibility", "recolor_object", "remove_objects",
            "set_project_frame"), names)
        val tools = Connected(editorDocument).hello.getValue("tools").jsonArray.map { it.jsonObject }.associateBy { it.str("name") }
        // The app sets transactionId for each turn: it is not in any advertised schema.
        for (t in tools.values) {
            val schema = t.getValue("inputSchema").jsonObject
            assertFalse("transactionId" in schema["properties"]!!.jsonObject, t.str("name"))
            assertFalse(JsonPrimitive("transactionId") in schema["required"]!!.jsonArray, t.str("name"))
        }
        assertEquals(buildJsonObject { put("readOnlyHint", false); put("destructiveHint", true); put("idempotentHint", false); put("openWorldHint", false) },
            tools.getValue("remove_objects").getValue("annotations"))
        assertEquals(JsonPrimitive(false), tools.getValue("add_text").getValue("annotations").jsonObject["destructiveHint"])
    }

    @Test fun addTextAnswersRevisionProjectAndSewoutAndIsOneUndoStep() {
        val relay = Connected(editorDocument)
        val (isError, r) = addText(relay)
        assertFalse(isError, r.toString())
        assertTrue(r.getValue("ok").jsonPrimitive.boolean)
        assertEquals("1", r.str("revision"))
        assertEquals(listOf("hi"), r.getValue("changedIds").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("PASS", r.getValue("sewout").jsonObject.str("verdict"))
        val hi = objects(r).single { it.str("id") == "hi" }
        assertEquals("Hi", hi.str("text"))
        assertEquals(JsonArray(listOf(JsonPrimitive("#D0021B"))), hi.getValue("threadColors"))
        assertTrue(hi.getValue("placedBoundsMm").jsonObject.getValue("heightMm").jsonPrimitive.double > 0)
        assertEquals("Hi", (session.project.find("hi") as TextObject).text)
        assertEquals(1 to 1, published to session.history.undoCount)

        // A second call is a second turn; one undo takes back only that call.
        val (moveError, moved) = relay.call("set_object_transforms",
            """{"transforms":[{"objectId":"hi","xMm":35,"yMm":0,"rotationDeg":0,"scaleXPercent":100,"scaleYPercent":100}]}""")
        assertFalse(moveError, moved.toString())
        assertEquals("2", moved.str("revision")); assertEquals(2, session.history.undoCount)
        val afterAdd = session.project.find("hi")!!.transform
        assertTrue(session.stepHistory(redo = false))
        assertNotEquals(afterAdd, session.project.find("hi")!!.transform)
        assertTrue(session.stepHistory(redo = false))
        assertSame(base, session.project)
        // get_project sees the editor's live revision and says it can edit.
        val (_, project) = relay.call("get_project")
        assertEquals(JsonPrimitive(true), project["editable"])
        assertEquals(session.history.revision.toString(), project.getValue("project").jsonObject.str("revision"))
    }

    @Test fun aBlockedSewoutOrBadArgumentsChangeNothing() {
        val relay = Connected(editorDocument)
        // 99 mm off center puts the logo outside the 100 mm hoop: the turn's sewout check blocks it.
        val (isError, r) = relay.call("set_object_transforms",
            """{"transforms":[{"objectId":"logo","xMm":99,"yMm":0,"rotationDeg":0,"scaleXPercent":100,"scaleYPercent":100}]}""")
        assertTrue(isError)
        assertEquals("SEWOUT_BLOCKED", r.str("code"))
        val sewout = r.getValue("sewout").jsonObject
        assertEquals("BLOCK", sewout.str("verdict"))
        assertTrue(sewout.getValue("findings").jsonArray.isNotEmpty())
        assertEquals("0", r.str("revision"))
        for (bad in listOf("""{"objectIds":["logo"],"alignment":"LEFT","transactionId":"mine"}""", """{"objectIds":["logo"],"alignment":"SIDEWAYS"}""",
            """{"objectIds":["nope"],"alignment":"LEFT"}""")) {
            val (e, out) = relay.call("align_objects", bad)
            assertTrue(e, bad); assertFalse(out.getValue("ok").jsonPrimitive.boolean, bad)
        }
        assertSame(base, session.project); assertEquals(0, session.history.undoCount)
    }

    @Test fun removeRunsOnlyAfterTheUserSaysYes() {
        val relay = Connected(editorDocument)
        assertFalse(addText(relay).first)
        val added = session.project
        answer = { false }
        val (declinedError, declined) = relay.call("remove_objects", """{"objectIds":["hi"]}""")
        assertTrue(declinedError); assertEquals("USER_DECLINED", declined.str("code"))
        assertTrue(RelayConfirmation.ON_PHONE in declined.str("message"))
        assertEquals(listOf(listOf("Hi")), asked)
        assertSame(added, session.project); assertEquals(1, session.history.undoCount)

        // No answer within the wait is a no (a 50 ms wait here instead of 90 s); the notification hook comes and goes.
        val hooks = mutableListOf<String>()
        val confirmation = RelayConfirmation(timeoutMs = 50, onAsk = { hooks += "ask $it" }, onDone = { hooks += "done" })
        answer = confirmation::ask
        val (timedOutError, timedOut) = relay.call("remove_objects", """{"objectIds":["hi"]}""")
        assertTrue(timedOutError); assertEquals("NO_ANSWER", timedOut.str("code"))
        assertTrue(RelayConfirmation.ON_PHONE in timedOut.str("message"))
        assertEquals(listOf("ask [Hi]", "done"), hooks)
        assertNull(confirmation.request.value)
        assertSame(added, session.project); assertEquals(1, session.history.undoCount)

        // A removal that cannot run is refused before anyone is asked.
        asked.clear(); answer = { true }
        assertTrue(relay.call("remove_objects", """{"objectIds":["nope"]}""").first)
        assertEquals(emptyList(), asked)

        val (isError, r) = relay.call("remove_objects", """{"objectIds":["hi"]}""")
        assertFalse(isError, r.toString())
        assertEquals(listOf(listOf("Hi")), asked)
        assertEquals(listOf("logo"), objects(r).map { it.str("id") })
        assertEquals("PASS", r.getValue("sewout").jsonObject.str("verdict"))
        assertEquals(2, session.history.undoCount)
        assertTrue(session.stepHistory(redo = false))
        assertSame(added, session.project)
    }

    @Test fun confirmationShowsOneQuestionAndTakesTheAnswer() = runBlocking {
        val confirmation = RelayConfirmation()
        val answered = async { confirmation.ask(listOf("Star")) }
        val shown = withTimeout(5_000) { confirmation.request.first { it != null } }!!
        assertEquals(listOf("Star"), shown.names)
        confirmation.answer(shown, true)
        assertEquals(true, answered.await())
        assertNull(confirmation.request.value)
    }

    @Test fun photoToolsGoToTheOpenEditorsPhotoToolsOnly() {
        val calls = mutableListOf<String>()
        val photoTools = object : RelayTools {
            override val definitions = RelayPhotoTools.definitions
            override suspend fun call(tool: String, args: JsonObject): RelayResult { calls += tool; return RelayResult.text("""{"ok":true,"attachments":[]}""") }
        }
        val withPhotos = object : RelayDocument by editorDocument {
            override val editor = object : RelayEditor by editorDocument.editor { override val photos = photoTools }
        }
        fun connect(document: RelayDocument?) = Relay().also { server.enqueue(MockResponse().withWebSocketUpgrade(it)) }.also {
            RelayClient(OkHttpClient(), server.url("/app").toString().replaceFirst("http", "ws"), "k", "t", RelayToolbox({ document }), scope).start()
        }
        val relay = connect(withPhotos)
        val socket = relay.socket()
        val names = relay.next().getValue("tools").jsonArray.map { it.jsonObject.str("name") }
        assertEquals(RelayPhotoTools.NAMES, names.takeLast(4).toSet())
        socket.send("""{"type":"call","id":"p","tool":"list_attachments","args":{}}""")
        assertFalse(relay.next().getValue("isError").jsonPrimitive.boolean)
        assertEquals(listOf("list_attachments"), calls)
        // The viewer has no photo store: the photo tools say to open the editor.
        val viewer = connect(object : RelayDocument { override suspend fun read() = RelayProject(base, 0); override val editor: RelayEditor? = null })
        val v = viewer.socket(); viewer.next()
        v.send("""{"type":"call","id":"v","tool":"view_attachment","args":{"attachmentId":"a","maxEdgePx":512}}""")
        assertEquals(ProjectRelayTools.OPEN_EDITOR, viewer.next().getValue("content").jsonArray.single().jsonObject.str("text"))
        assertEquals(listOf("list_attachments"), calls)
    }

    @Test fun theViewerOnlyReads() {
        val viewer = object : RelayDocument {
            override suspend fun read() = RelayProject(base, 0)
            override val editor: RelayEditor? = null
        }
        val relay = Connected(viewer)
        val (projectError, project) = relay.call("get_project")
        assertFalse(projectError); assertEquals(JsonPrimitive(false), project["editable"])
        assertEquals(listOf("logo"), objects(project).map { it.str("id") })
        val (previewError, preview) = relay.call("render_preview", """{"widthPx":512}""")
        assertFalse(previewError); assertEquals(512, preview.getValue("w").jsonPrimitive.int)
        for (tool in listOf("add_text" to """{"objectId":"x","text":"Hi","fontId":"f","fontRevision":"r","heightMm":10,"threadColor":"#000000","singleLineBold":false}""",
            "list_fonts" to """{"text":"","filters":[],"installedOnly":true}""", "remove_objects" to """{"objectIds":["logo"]}""",
            "align_objects" to """{"objectIds":["logo"],"alignment":"LEFT"}""")) {
            val (e, out) = relay.call(tool.first, tool.second)
            assertTrue(e, tool.first); assertEquals(ProjectRelayTools.OPEN_EDITOR, out.str("text"))
        }
        // Nothing open at all.
        val (noneError, _) = Connected(null).call("get_project")
        assertTrue(noneError)
    }
}
