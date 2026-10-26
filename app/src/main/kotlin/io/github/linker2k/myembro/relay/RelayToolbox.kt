// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.relay

import io.github.linker2k.myembro.engine.agent.ToolDispatcher
import io.github.linker2k.myembro.engine.agent.ToolSchema
import io.github.linker2k.myembro.engine.edit.Project
import io.github.linker2k.myembro.ui.agent.ProjectPreviewRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** The project on screen and its history revision, the same pair the Ask AI prompt reads. */
data class RelayProject(val project: Project, val revision: Long)

/** The screen the relay reaches: the editor, or the viewer, which only reads. */
interface RelayDocument {
    /** Null while nothing is loaded. */
    suspend fun read(): RelayProject?
    /** Null on a read-only screen (the viewer). */
    val editor: RelayEditor?
}

/** The open editor's own kernel and session (EditorAgentExecutor), the path Ask AI Apply takes. */
interface RelayEditor {
    /** A read tool (list_fonts, list_builtin_designs, get_settings) on the editor's kernel: the dispatcher's output. */
    suspend fun query(tool: String, args: JsonObject): JsonObject
    /** One edit tool as one turn: one undo step, sewout-checked; the [RelayEdits.result] shape. */
    suspend fun edit(tool: String, args: JsonObject): JsonObject
    /** What get_project adds in the editor: usable fonts and the current sewing check. */
    suspend fun overview(): JsonObject = JsonObject(emptyMap())
    /**
     * Installed fonts in the short relay form ([RelayCatalog.fontRow]): with [text], only the ones that write
     * all of it; [filters] KOREAN/LATIN keep fonts with that script. From cached coverage, not the strict
     * list_fonts sweep (8.8 s and 291,209 chars for one call on a phone).
     */
    suspend fun fonts(text: String?, filters: Set<String> = emptySet()): JsonArray = JsonArray(emptyList())
    /** The photo tools on this editor's kernel and session (RelayPhotoTools), null where there are none. */
    val photos: RelayTools? get() = null
}

/**
 * A set of relay tools: what the hello advertises and how a call runs on the open screen. Another file
 * adds its own group to [RelayToolGroups.default] (RelayPhotoTools.kt for the photo tools).
 */
interface RelayToolGroup {
    val definitions: List<JsonObject>
    /** Only called for a name in [definitions]; [document] is null when no editor or viewer is open. */
    suspend fun call(tool: String, args: JsonObject, document: RelayDocument?): RelayResult
}

object RelayToolGroups {
    fun default(): List<RelayToolGroup> = listOf(ProjectRelayTools(), PhotoRelayTools())
}

/** All groups behind one socket: the hello lists their tools and each call goes to the group that offered it. */
class RelayToolbox(
    private val current: suspend () -> RelayDocument?,
    private val groups: List<RelayToolGroup> = RelayToolGroups.default(),
) : RelayTools {
    override val definitions = groups.flatMap { it.definitions }
    private val owners = groups.flatMap { g -> g.definitions.map { it.getValue("name").jsonPrimitive.content to g } }.toMap()
    override val names = owners.keys

    init { require(owners.size == definitions.size) { "Two relay groups offer the same tool" } }

    override suspend fun call(tool: String, args: JsonObject): RelayResult =
        owners[tool]?.call(tool, args, current()) ?: RelayResult.error("Tool $tool is not available over the myembro AI connection.")
}

/**
 * The project tools on the existing paths: get_project and render_preview read whichever screen is open
 * (render_preview checks its arguments against tools.json, then draws one captured snapshot with
 * [ProjectPreviewRenderer] off the main thread); reads that need fonts, designs or frames and every edit go
 * to the open editor, so they never run in the viewer.
 */
class ProjectRelayTools(
    private val dispatcher: ToolDispatcher = ToolDispatcher(),
    private val render: (Project, Int) -> JsonObject = ProjectPreviewRenderer::renderPreview,
    private val schema: ToolSchema = ToolSchema.bundled(),
) : RelayToolGroup {
    override val definitions = RelayProtocol.definitions(
        listOf(RelayProtocol.GET_PROJECT, RelayProtocol.RENDER_PREVIEW) + RelayProtocol.EDITOR_READS + RelayProtocol.EDITS)

    override suspend fun call(tool: String, args: JsonObject, document: RelayDocument?): RelayResult {
        document ?: return RelayResult.error(NO_PROJECT)
        return when (tool) {
            RelayProtocol.GET_PROJECT -> {
                val open = document.read() ?: return RelayResult.error(NO_PROJECT)
                val result = dispatcher.dispatch(open.project, open.revision.toString(), tool, args)
                if (!result.ok) return RelayResult.text(result.output.toString(), isError = true)
                // One call answers what adding text needs: free space, fonts and the last check.
                val output = JsonObject(result.output + ("editable" to JsonPrimitive(document.editor != null)) +
                    ("freeSpaceMm" to RelayCatalog.freeSpace(open.project)) + document.editor?.overview().orEmpty())
                RelayResult.text(output.toString())
            }
            RelayProtocol.RENDER_PREVIEW -> {
                val args = if ("widthPx" in args) args else JsonObject(args + ("widthPx" to JsonPrimitive(512)))
                schema.validate(tool, args)?.let { return RelayResult.error("Invalid arguments: $it") }
                val open = document.read() ?: return RelayResult.error(NO_PROJECT)
                // A failed render is an error result, never a partial picture.
                withContext(Dispatchers.Default) { runCatching { render(open.project, args.getValue("widthPx").jsonPrimitive.int) } }
                    .fold({ RelayResult(false, listOf(it)) }, { RelayResult.error("myembro could not render the preview.") })
            }
            else -> {
                val editor = document.editor ?: return RelayResult.error(OPEN_EDITOR)
                val output = if (tool == "list_fonts") {
                    val strict = RelayCatalog.complete(tool, args, open(document) ?: return RelayResult.error(NO_PROJECT)) { null }
                    val a = strict.calls.singleOrNull()?.arguments?.jsonObject ?: return RelayResult.error(strict.error ?: "Invalid arguments")
                    schema.validate(tool, a)?.let { return RelayResult.error("Invalid arguments: $it") }
                    val text = a.getValue("text").jsonPrimitive.content
                    buildJsonObject {
                        put("ok", true); if (text.isNotEmpty()) put("text", text)
                        put("fonts", editor.fonts(text.ifEmpty { null }, a.getValue("filters").jsonArray.map { it.jsonPrimitive.content }.toSet()))
                    }
                } else if (tool in RelayProtocol.EDITOR_READS) {
                    val strict = RelayCatalog.complete(tool, args, open(document) ?: return RelayResult.error(NO_PROJECT)) { null }
                    editor.query(tool, strict.calls.singleOrNull()?.arguments?.jsonObject ?: return RelayResult.error(strict.error ?: "Invalid arguments"))
                } else editor.edit(tool, args)
                RelayResult.text(output.toString(), isError = output["ok"]?.jsonPrimitive?.booleanOrNull != true)
            }
        }
    }

    private suspend fun open(document: RelayDocument) = document.read()?.project

    companion object {
        const val NO_PROJECT = "No design is open in myembro. Open one in the editor and try again."
        const val OPEN_EDITOR = "This design is open in the myembro viewer, which only reads. Open it in the editor to change it."
    }
}

/**
 * RelayPhotoTools (docs/agent/relay-photo.md) on the open editor: only photos the user handed to AI there.
 * Definitions keep the catalog's arguments; annotations are projected to MCP names like the others.
 */
class PhotoRelayTools : RelayToolGroup {
    override val definitions = RelayProtocol.offer(RelayPhotoTools.definitions)

    override suspend fun call(tool: String, args: JsonObject, document: RelayDocument?): RelayResult {
        document ?: return RelayResult.error(ProjectRelayTools.NO_PROJECT)
        val photos = document.editor?.photos ?: return RelayResult.error(ProjectRelayTools.OPEN_EDITOR)
        // The relay offers add_generated_art without transactionId; each call is its own turn.
        val strict = if (tool == "add_generated_art" && "transactionId" !in args)
            JsonObject(args + ("transactionId" to JsonPrimitive("relay-${java.util.UUID.randomUUID()}"))) else args
        return photos.call(tool, RelayCatalog.withDefaults(tool, strict))
    }
}

/** The screen that is open now, if any: the editor or the viewer attaches itself while it is shown. */
object RelayProjectSource {
    @Volatile private var owner: Any? = null
    @Volatile private var document: RelayDocument? = null

    fun attach(owner: Any, document: RelayDocument) { this.owner = owner; this.document = document }
    fun detach(owner: Any) { if (this.owner === owner) { this.owner = null; document = null } }
    fun current(): RelayDocument? = document
}
