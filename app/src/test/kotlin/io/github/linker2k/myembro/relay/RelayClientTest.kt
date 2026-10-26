// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.relay

import io.github.linker2k.myembro.backup.BackupPreferences
import io.github.linker2k.myembro.engine.agent.AgentScenario
import io.github.linker2k.myembro.engine.edit.Project
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Relay contract v0 (edit phase), app side, against a fake relay (MockWebServer WebSocket). No network, no LLM. */
class RelayClientTest {
    private val server = MockWebServer().apply { start() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val project = AgentScenario.builtin("myembro_logo.pes", "logo", "H100")
    private val url = server.url("/app").toString().replaceFirst("http", "ws")

    @AfterTest fun stop() { scope.cancel(); server.shutdown() }

    /** The relay's end of one socket. */
    private class Relay : WebSocketListener() {
        val opened = LinkedBlockingQueue<WebSocket>()
        val messages = LinkedBlockingQueue<JsonObject>()
        override fun onOpen(webSocket: WebSocket, response: Response) { opened += webSocket }
        override fun onMessage(webSocket: WebSocket, text: String) { messages += Json.parseToJsonElement(text).jsonObject }
        fun socket() = assertNotNull(opened.poll(5, TimeUnit.SECONDS), "app did not connect")
        fun next() = assertNotNull(messages.poll(5, TimeUnit.SECONDS), "no message from the app")
    }

    private fun relay() = Relay().also { server.enqueue(MockResponse().withWebSocketUpgrade(it)) }
    private fun client(tools: RelayTools, backoff: RelayClient.Backoff = RelayClient.Backoff(10, 40), pingMs: Long = 30_000,
        silenceMs: Long = 90_000, states: MutableList<RelayClient.State> = mutableListOf()) =
        RelayClient(OkHttpClient(), url, "k", "0.3.0-test", tools, scope, backoff, pingMs, silenceMs) { synchronized(states) { states += it } }.start()
    private val open = object : RelayDocument {
        override suspend fun read() = RelayProject(project, 3)
        override val editor: RelayEditor? = null
    }
    private val editor = RelayToolbox({ open })
    private fun tools(render: (Project, Int) -> JsonObject, document: RelayDocument? = open) =
        RelayToolbox({ document }, listOf(ProjectRelayTools(render = render)))
    private fun call(id: String, tool: String, args: String = "{}") = """{"type":"call","id":"$id","tool":"$tool","args":$args}"""
    private fun text(result: JsonObject) = result.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content

    @Test fun helloOffersTheRelayToolsAsToolsJsonDefinesThem() {
        val relay = relay(); client(editor)
        relay.socket()
        val hello = relay.next()
        // Contract v1: the key only in the Authorization header, never in the URL.
        val upgrade = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/app", upgrade.path)
        assertEquals("Bearer k", upgrade.getHeader("Authorization"))
        assertEquals(setOf("type", "appVersion", "tools"), hello.keys)
        assertEquals("hello", hello.getValue("type").jsonPrimitive.content)
        assertEquals("0.3.0-test", hello.getValue("appVersion").jsonPrimitive.content)
        val all = hello.getValue("tools").jsonArray.map { it.jsonObject }
        assertEquals(listOf("get_project", "render_preview"), all.take(2).map { it.getValue("name").jsonPrimitive.content })
        // The photo group (RelayPhotoTools, its own definitions) comes last, with MCP annotation names.
        val photos = all.takeLast(RelayPhotoTools.NAMES.size)
        assertEquals(RelayPhotoTools.NAMES, photos.map { it.getValue("name").jsonPrimitive.content }.toSet())
        for (p in photos) assertTrue("readOnlyHint" in p.getValue("annotations").jsonObject && "readOnly" !in p.getValue("annotations").jsonObject)
        val tools = all.dropLast(RelayPhotoTools.NAMES.size)
        // The checked-in definitions, unchanged except for annotations in MCP's standard names and the app-owned transactionId.
        val catalog = Json.parseToJsonElement(File("../docs/agent/tools.json").readText()).jsonObject.getValue("tools").jsonArray.map { it.jsonObject }
        for (t in tools) {
            val source = catalog.single { it.getValue("name") == t.getValue("name") }
            assertEquals(setOf("name", "description", "inputSchema", "annotations"), t.keys)
            assertEquals(source - "annotations" - "inputSchema" - "description", t - "annotations" - "inputSchema" - "description")
            // remove_objects also says the phone asks first; every other description is the catalog's.
            val extra = if (t.getValue("name").jsonPrimitive.content == "remove_objects") " ${RelayProtocol.REMOVE_ON_PHONE}" else ""
            assertEquals(source.getValue("description").jsonPrimitive.content + extra, t.getValue("description").jsonPrimitive.content)
            val schema = source.getValue("inputSchema").jsonObject
            assertEquals(JsonObject(schema + ("properties" to JsonObject(schema.getValue("properties").jsonObject - "transactionId")) +
                ("required" to JsonArray(schema.getValue("required").jsonArray.filter { it.jsonPrimitive.content != "transactionId" }))),
                t.getValue("inputSchema"))
            assertEquals(RelayProtocol.mcpAnnotations(source.getValue("annotations").jsonObject), t.getValue("annotations"))
        }
        for (t in tools.take(5)) assertEquals(buildJsonObject { put("readOnlyHint", true); put("idempotentHint", true); put("openWorldHint", false) },
            t.getValue("annotations"))
        val width = tools[1].getValue("inputSchema").jsonObject.getValue("properties").jsonObject.getValue("widthPx").jsonObject
        assertEquals(Triple("integer", 256, 1024), Triple(width.getValue("type").jsonPrimitive.content,
            width.getValue("minimum").jsonPrimitive.int, width.getValue("maximum").jsonPrimitive.int))
    }

    @Test fun catalogAnnotationsMapToMcpHints() {
        fun a(readOnly: Boolean, confirm: Boolean) = buildJsonObject { put("readOnly", readOnly); put("needsUserConfirmation", confirm); put("mapsTo", "X") }
        assertEquals(buildJsonObject { put("readOnlyHint", false); put("destructiveHint", true); put("idempotentHint", false); put("openWorldHint", false) },
            RelayProtocol.mcpAnnotations(a(false, true)))
        assertEquals(buildJsonObject { put("readOnlyHint", false); put("destructiveHint", false); put("idempotentHint", false); put("openWorldHint", false) },
            RelayProtocol.mcpAnnotations(a(false, false)))
    }

    @Test fun renderPreviewDrawsTheOpenProjectAtTheAskedWidth() = runBlocking {
        val drawn = mutableListOf<Pair<Project, Int>>()
        val image = buildJsonObject { put("type", "image"); put("data", "iVBORw0KGgo="); put("mimeType", "image/png") }
        val tools = tools({ p, w -> drawn += p to w; image })
        fun args(s: String) = Json.parseToJsonElement(s).jsonObject
        assertEquals(RelayResult(false, listOf(image)), tools.call("render_preview", args("""{"widthPx":512}""")))
        assertEquals(listOf(project to 512), drawn)
        // tools.json bounds are inclusive 256..1024; outside, or not one integer, nothing is drawn.
        for (bad in listOf("""{"widthPx":255}""", """{"widthPx":1025}""", """{"widthPx":512.5}""", """{"widthPx":"512"}""", "{}",
            """{"widthPx":512,"path":"/sdcard"}"""))
            assertTrue(tools.call("render_preview", args(bad)).isError, bad)
        for (w in listOf(256, 1024)) assertFalse(tools.call("render_preview", args("""{"widthPx":$w}""")).isError)
        assertEquals(listOf(512, 256, 1024), drawn.map { it.second })
        assertEquals(RelayResult.error(ProjectRelayTools.NO_PROJECT), tools({ _, _ -> error("no") }, null)
            .call("render_preview", args("""{"widthPx":512}""")))
        assertEquals(RelayResult.error("myembro could not render the preview."),
            tools({ _, _ -> error("bitmap") }).call("render_preview", args("""{"widthPx":512}""")))
    }

    @Test fun getProjectRoundTripRunsTheDispatcherOnTheOpenProject() {
        val relay = relay(); client(editor)
        val socket = relay.socket(); relay.next()
        socket.send(call("c-1", "get_project"))
        val result = relay.next()
        assertEquals(setOf("type", "id", "isError", "content"), result.keys)
        assertEquals("result" to "c-1", result.getValue("type").jsonPrimitive.content to result.getValue("id").jsonPrimitive.content)
        assertFalse(result.getValue("isError").jsonPrimitive.boolean)
        val output = Json.parseToJsonElement(text(result)).jsonObject
        assertTrue(output.getValue("ok").jsonPrimitive.boolean)
        val p = output.getValue("project").jsonObject
        assertEquals("3", p.getValue("revision").jsonPrimitive.content)
        assertEquals(project.objects.map { it.id }, p.getValue("objects").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        // Arguments outside the schema are the dispatcher's INVALID_ARGUMENTS, as an error result.
        socket.send(call("c-2", "get_project", """{"path":"/sdcard"}"""))
        val bad = relay.next()
        assertTrue(bad.getValue("isError").jsonPrimitive.boolean)
        assertEquals("INVALID_ARGUMENTS", Json.parseToJsonElement(text(bad)).jsonObject.getValue("code").jsonPrimitive.content)
    }

    @Test fun toolsTheHelloDidNotOfferAreRefusedWithoutRunning() {
        val ran = AtomicInteger()
        val counting = object : RelayTools {
            override val definitions = editor.definitions
            override suspend fun call(tool: String, args: JsonObject): RelayResult { ran.incrementAndGet(); return editor.call(tool, args) }
        }
        val relay = relay(); client(counting)
        val socket = relay.socket(); relay.next()
        // Save, share, USB, file deletion, restore, settings, history and the emoji/sewout tools are never offered.
        val refused = listOf("prepare_emoji", "list_emoji", "sewout_check", "prepare_pes", "save_project", "save_pes", "share_pes", "send_usb",
            "delete_designs", "restore_backup", "set_settings", "new_project", "undo", "commit_edit_turn", "", "GET_PROJECT")
        refused.forEachIndexed { i, tool -> socket.send(call("x$i", tool)) }
        val results = List(refused.size) { relay.next() }.associateBy { it.getValue("id").jsonPrimitive.content }
        refused.indices.forEach { i ->
            val r = results.getValue("x$i")
            assertTrue(r.getValue("isError").jsonPrimitive.boolean, refused[i])
            assertEquals("Tool ${refused[i]} is not available over the myembro AI connection.", text(r))
        }
        assertEquals(0, ran.get())
        // render_preview is allowed (out of range here: the host refuses it); arguments that are not an object never reach it.
        socket.send(call("r", "render_preview", """{"widthPx":2048}"""))
        assertTrue(relay.next().getValue("isError").jsonPrimitive.boolean)
        assertEquals(1, ran.get())
        socket.send(call("a", "get_project", "[]"))
        assertEquals("Arguments must be a JSON object.", text(relay.next()))
        assertEquals(1, ran.get())
    }

    @Test fun withoutAnOpenEditorGetProjectIsAnError() = runBlocking {
        val r = RelayToolbox({ null }).call("get_project", JsonObject(emptyMap()))
        assertEquals(RelayResult.error(ProjectRelayTools.NO_PROJECT), r)
    }

    @Test fun pingIsAnsweredWithPong() {
        val relay = relay(); client(editor)
        val socket = relay.socket(); relay.next()
        socket.send("""{"type":"ping"}""")
        assertEquals(buildJsonObject { put("type", "pong") }, relay.next())
        // Unknown or malformed messages get no reply; the next call still works.
        socket.send("""{"type":"what"}"""); socket.send("not json")
        socket.send(call("c", "get_project"))
        assertEquals("c", relay.next().getValue("id").jsonPrimitive.content)
    }

    @Test fun reconnectsWithAFreshHelloAfterTheRelayDrops() {
        val states = mutableListOf<RelayClient.State>()
        val first = relay(); val second = relay()
        client(editor, states = states)
        first.socket().also { first.next() }.close(1000, "replaced")
        val again = second.socket()
        assertEquals("hello", second.next().getValue("type").jsonPrimitive.content)
        again.send(call("after", "get_project"))
        assertFalse(second.next().getValue("isError").jsonPrimitive.boolean)
        assertEquals(2, server.requestCount)
        synchronized(states) { assertEquals(listOf(RelayClient.State.CONNECTING, RelayClient.State.CONNECTED, RelayClient.State.WAITING,
            RelayClient.State.CONNECTING, RelayClient.State.CONNECTED), states.toList()) }
    }

    @Test fun retriesWhenTheRelayIsDownAndWhenItGoesSilent() {
        // Refused upgrade first (relay down), then a relay that never answers: the app pings, then gives up and reconnects.
        server.enqueue(MockResponse().setResponseCode(503))
        val silent = relay(); val third = relay()
        client(editor, pingMs = 100, silenceMs = 400)
        silent.socket(); assertEquals("hello", silent.next().getValue("type").jsonPrimitive.content)
        assertEquals(buildJsonObject { put("type", "ping") }, silent.next())
        third.socket(); assertEquals("hello", third.next().getValue("type").jsonPrimitive.content)
        assertEquals(3, server.requestCount)
    }

    @Test fun backoffDoublesFromOneSecondToASixtySecondCap() {
        val b = RelayClient.Backoff()
        assertEquals(listOf(1_000L, 2_000, 4_000, 8_000, 16_000, 32_000, 60_000, 60_000), (0..7).map(b::delayMs))
        assertEquals(60_000, b.delayMs(1_000))
        assertTrue(60_000 < RelayProtocol.SILENCE_MS)
    }

    @Test fun relayAddressBecomesTheAppSocketUrl() {
        assertEquals("wss://relay.example.workers.dev/app", RelayProtocol.appUrl(" wss://relay.example.workers.dev/ ", "a+b&c"))
        assertEquals("wss://r.example/app", RelayProtocol.appUrl("https://r.example", "k"))
        assertEquals("wss://r.example/base/app", RelayProtocol.appUrl("wss://r.example/base/app", "k"))
        assertEquals("ws://10.0.2.2:8787/app", RelayProtocol.appUrl("ws://10.0.2.2:8787", "k"))
        assertEquals("Bearer a+b&c", RelayProtocol.authorization(" a+b&c "))
        // The key never goes out in clear to another host, and nothing without a key or a host.
        for (bad in listOf("ws://r.example", "http://192.168.0.5:8787", "ftp://r.example", "r.example", "", "wss://"))
            assertNull(RelayProtocol.appUrl(bad, "k"), bad)
        assertNull(RelayProtocol.appUrl("wss://r.example", " "))
    }

    @Test fun theKeyStaysOutOfBackupsAndCleartextIsOnlyLocal() {
        assertFalse(RelayPreferences.FILE in BackupPreferences.names)
        val excluded = "<exclude domain=\"sharedpref\" path=\"${RelayPreferences.FILE}.xml\" />"
        assertEquals(1, File("src/main/res/xml/backup_rules.xml").readText().split(excluded).size - 1)
        assertEquals(2, File("src/main/res/xml/data_extraction_rules.xml").readText().split(excluded).size - 1)
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue("android:fullBackupContent=\"@xml/backup_rules\"" in manifest && "android:dataExtractionRules=\"@xml/data_extraction_rules\"" in manifest)
        assertTrue("android:networkSecurityConfig=\"@xml/network_security_config\"" in manifest)
        val network = File("src/main/res/xml/network_security_config.xml").readText()
        assertTrue("<base-config cleartextTrafficPermitted=\"false\" />" in network)
        assertEquals(RelayProtocol.CLEARTEXT_HOSTS, Regex("<domain includeSubdomains=\"false\">([^<]+)</domain>").findAll(network).map { it.groupValues[1] }.toSet())
    }
}
