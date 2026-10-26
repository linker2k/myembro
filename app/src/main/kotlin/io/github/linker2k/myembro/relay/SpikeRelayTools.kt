// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.relay

import io.github.linker2k.myembro.engine.agent.ToolDispatcher
import io.github.linker2k.myembro.engine.agent.ToolSchema
import io.github.linker2k.myembro.engine.edit.Project
import io.github.linker2k.myembro.ui.agent.ProjectPreviewRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int

/** The project open in the editor and its history revision, the same pair the Ask AI prompt reads. */
data class RelayProject(val project: Project, val revision: Long)

/**
 * The spike tools on the existing paths: get_project is the dispatcher's own tool on the editor's project;
 * render_preview checks its arguments against tools.json, then draws one captured project snapshot with
 * [ProjectPreviewRenderer] (docs/agent/render-preview.md) off the main thread.
 */
class SpikeRelayTools(
    private val current: suspend () -> RelayProject?,
    private val dispatcher: ToolDispatcher = ToolDispatcher(),
    private val render: (Project, Int) -> JsonObject = ProjectPreviewRenderer::renderPreview,
    private val schema: ToolSchema = ToolSchema.bundled(),
) : RelayTools {
    override suspend fun call(tool: String, args: JsonObject): RelayResult = when (tool) {
        RelayProtocol.GET_PROJECT -> {
            val open = current() ?: return RelayResult.error(NO_PROJECT)
            val result = dispatcher.dispatch(open.project, open.revision.toString(), tool, args)
            RelayResult.text(result.output.toString(), isError = !result.ok)
        }
        RelayProtocol.RENDER_PREVIEW -> {
            schema.validate(tool, args)?.let { return RelayResult.error("Invalid arguments: $it") }
            val open = current() ?: return RelayResult.error(NO_PROJECT)
            // A failed render is an error result, never a partial picture.
            withContext(Dispatchers.Default) { runCatching { render(open.project, args.getValue("widthPx").jsonPrimitive.int) } }
                .fold({ RelayResult(false, listOf(it)) }, { RelayResult.error("myembro could not render the preview.") })
        }
        else -> RelayResult.error("Tool $tool is not available over the myembro AI connection.")
    }

    companion object {
        const val NO_PROJECT = "No design is open in the myembro editor. Open one and try again."
    }
}

/** The editor that is open now, if any; set by EditorViewModel, read by the relay service. */
object RelayProjectSource {
    @Volatile private var owner: Any? = null
    @Volatile private var read: (suspend () -> RelayProject)? = null

    fun attach(owner: Any, read: suspend () -> RelayProject) { this.owner = owner; this.read = read }
    fun detach(owner: Any) { if (this.owner === owner) { this.owner = null; read = null } }
    suspend fun current(): RelayProject? = read?.invoke()
}
