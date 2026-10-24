// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.agent

import io.github.linker2k.myembro.engine.edit.Project

/**
 * Validates a parsed script against the command layer and prepares it on a private candidate
 * (docs/agent/mobile-standalone.md steps 7–8). It never applies: the user does, from the preview.
 * The command dispatcher (agent/commands) plugs in here.
 */
internal interface AgentScriptExecutor {
    suspend fun preview(project: Project, script: AgentScript, handles: AgentHandles): AgentPreviewResult
}

internal sealed interface AgentPreviewResult {
    /** No dispatcher is connected yet; nothing was validated or changed. */
    data object NotConnected : AgentPreviewResult
    data class Rejected(val problems: List<String>) : AgentPreviewResult
    data class Ready(val preview: AgentPreview) : AgentPreviewResult
}

internal data class AgentChange(val kind: Kind, val text: String) {
    enum class Kind { ADD, CHANGE, REMOVE }
}

internal data class AgentPreview(
    val summary: String,
    val changes: List<AgentChange>,
    /** Null when the candidate passes the sewout check; otherwise the blocking findings. */
    val blockingFindings: List<String>?,
    /** Names of objects removed by the script; Apply needs an explicit tick when not empty. */
    val removals: List<String>,
    /** Save/share/USB requests, each run later from its own existing confirmation. */
    val followUps: List<String>,
    /** True for layout samples, which can never be applied. */
    val sample: Boolean = false,
)

/** Placeholder until the dispatcher exists. */
internal object NotConnectedAgentScriptExecutor : AgentScriptExecutor {
    override suspend fun preview(project: Project, script: AgentScript, handles: AgentHandles) = AgentPreviewResult.NotConnected
}

/** Fake data that shows the preview layout before any dispatcher is connected. */
internal object AgentPreviewSample {
    fun of(script: AgentScript?) = AgentPreview(
        summary = script?.summary?.takeIf { it.isNotBlank() } ?: "Add “Mina” under the logo in red",
        changes = listOf(
            AgentChange(AgentChange.Kind.ADD, "Text “Mina” · f1 · 12 mm · #D0021B"),
            AgentChange(AgentChange.Kind.CHANGE, "o1, Mina aligned to the center"),
            AgentChange(AgentChange.Kind.REMOVE, "o2 “Star”"),
        ),
        blockingFindings = null,
        removals = listOf("Star"),
        followUps = listOf("send_usb"),
        sample = true,
    )
}
