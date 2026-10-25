// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.agent

import io.github.linker2k.myembro.engine.edit.Project

/**
 * Validates a parsed script against the command layer and prepares it on a private candidate
 * (docs/agent/mobile-standalone.md steps 7–9). [preview] never changes the project; [apply] commits
 * the whole script as one undo step. Tools to do separately in the app (transfer, saves, restore…) are never run.
 */
internal interface AgentScriptExecutor {
    suspend fun preview(script: AgentScript, handles: AgentHandles, expectedRevision: Long): AgentPreviewResult
    /** [approvedRemovals] are the call indices of the removals the user ticked; an unticked one blocks the whole script. */
    suspend fun apply(script: AgentScript, handles: AgentHandles, expectedRevision: Long, approvedRemovals: Set<Int>): AgentApplyResult
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
    data object Applied : AgentApplyResult
    data class Rejected(val problems: List<AgentProblem>) : AgentApplyResult
}

/** A remove_objects call in the script ([callIndex] among the dispatcher calls) and the names it removes. */
internal data class AgentRemoval(val callIndex: Int, val names: List<String>)

/** One line of the change list; the screen localizes [tool] and formats [detail]. */
internal data class AgentChange(val kind: Kind, val tool: String, val objects: List<String>, val detail: List<String> = emptyList()) {
    enum class Kind { ADD, CHANGE, REMOVE }
}

internal data class AgentPreview(
    val summary: String,
    val changes: List<AgentChange>,
    /** Null when the candidate passes the sewout check; otherwise the blocking findings (Apply is disabled). */
    val blockingFindings: List<String>?,
    /** Each removal needs its own tick (default off); any unticked removal means nothing is applied. */
    val removals: List<AgentRemoval>,
    /** Tools to do separately in the app ([AgentTools.separate]); when not empty, Apply is impossible. */
    val separate: List<String>,
    val before: Project? = null,
    val after: Project? = null,
    /** True for layout samples, which can never be applied. */
    val sample: Boolean = false,
)

/** Used where no editor session is available (and by tests). */
internal object NotConnectedAgentScriptExecutor : AgentScriptExecutor {
    override suspend fun preview(script: AgentScript, handles: AgentHandles, expectedRevision: Long) = AgentPreviewResult.NotConnected
    override suspend fun apply(script: AgentScript, handles: AgentHandles, expectedRevision: Long, approvedRemovals: Set<Int>) =
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
        removals = listOf(AgentRemoval(2, listOf("Star"))),
        separate = listOf("send_usb"),
        sample = true,
    )
}
