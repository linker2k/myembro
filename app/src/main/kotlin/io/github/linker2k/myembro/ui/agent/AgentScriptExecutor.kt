// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.agent

import io.github.linker2k.myembro.engine.edit.Project

/**
 * Validates a parsed script against the command layer and prepares it on a private candidate
 * (docs/agent/mobile-standalone.md steps 7–9). [preview] never changes the project; [apply] commits
 * the whole script as one undo step. Follow-up tools (save/share/USB) are never run by either.
 */
internal interface AgentScriptExecutor {
    suspend fun preview(script: AgentScript, handles: AgentHandles, expectedRevision: Long): AgentPreviewResult
    suspend fun apply(script: AgentScript, handles: AgentHandles, expectedRevision: Long, removalConfirmed: Boolean): AgentApplyResult
}

/** [index] is the command's position in the script (0-based), null for the whole script. */
internal data class AgentProblem(val code: String, val index: Int?, val tool: String?, val message: String)

internal sealed interface AgentPreviewResult {
    /** No dispatcher is connected; nothing was validated or changed. */
    data object NotConnected : AgentPreviewResult
    data class Rejected(val problems: List<AgentProblem>) : AgentPreviewResult
    data class Ready(val preview: AgentPreview) : AgentPreviewResult
}

internal sealed interface AgentApplyResult {
    data class Applied(val followUps: List<String>) : AgentApplyResult
    data class Rejected(val problems: List<AgentProblem>) : AgentApplyResult
}

/** One line of the change list; the screen localizes [tool] and formats [detail]. */
internal data class AgentChange(val kind: Kind, val tool: String, val objects: List<String>, val detail: List<String> = emptyList()) {
    enum class Kind { ADD, CHANGE, REMOVE }
}

internal data class AgentPreview(
    val summary: String,
    val changes: List<AgentChange>,
    /** Null when the candidate passes the sewout check; otherwise the blocking findings (Apply is disabled). */
    val blockingFindings: List<String>?,
    /** Names of objects removed by the script; Apply needs an explicit tick when not empty. */
    val removals: List<String>,
    /** Save/share/USB requests, each run later from its own existing confirmation. */
    val followUps: List<String>,
    val before: Project? = null,
    val after: Project? = null,
    /** True for layout samples, which can never be applied. */
    val sample: Boolean = false,
)

/** Used where no editor session is available (and by tests). */
internal object NotConnectedAgentScriptExecutor : AgentScriptExecutor {
    override suspend fun preview(script: AgentScript, handles: AgentHandles, expectedRevision: Long) = AgentPreviewResult.NotConnected
    override suspend fun apply(script: AgentScript, handles: AgentHandles, expectedRevision: Long, removalConfirmed: Boolean) =
        AgentApplyResult.Rejected(listOf(AgentProblem("NOT_CONNECTED", null, null, "")))
}

/** Fake data that shows the preview layout (debug builds only). */
internal object AgentPreviewSample {
    fun of(script: AgentScript?) = AgentPreview(
        summary = script?.summary?.takeIf { it.isNotBlank() } ?: "Add “Mina” under the logo in red",
        changes = listOf(
            AgentChange(AgentChange.Kind.CHANGE, "align_objects", listOf("Logo", "Mina"), listOf("CENTER_X")),
            AgentChange(AgentChange.Kind.CHANGE, "recolor_object", listOf("Logo"), listOf("1", "#D0021B")),
            AgentChange(AgentChange.Kind.REMOVE, "remove_objects", listOf("Star")),
        ),
        blockingFindings = null,
        removals = listOf("Star"),
        followUps = listOf("send_usb"),
        sample = true,
    )
}
