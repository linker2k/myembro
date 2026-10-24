// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.agent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.linker2k.myembro.engine.edit.Project
import io.github.linker2k.myembro.engine.edit.placedBounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.SecureRandom

/** "Ask AI" state for one editor: the pending prompt, the pasted reply and its result. Kept by the editor ViewModel. */
internal class AgentAssistController(private val executor: AgentScriptExecutor = NotConnectedAgentScriptExecutor) {
    enum class Stage { CLOSED, ASK, REPLY, RESULT }

    sealed interface Outcome {
        data class Unreadable(val result: AgentParseResult) : Outcome
        /** The reply answers a prompt that is not the pending one (or none is pending). */
        data class OtherPrompt(val promptId: String) : Outcome
        data class Read(val script: AgentScript, val result: AgentPreviewResult) : Outcome
    }

    var stage by mutableStateOf(Stage.CLOSED); private set
    var request by mutableStateOf("")
    var reply by mutableStateOf("")
    var summary by mutableStateOf<AgentProjectSummary?>(null); private set
    /** Recorded once the prompt leaves the app (shared or copied). */
    var pending by mutableStateOf<AgentPrompt?>(null); private set
    var outcome by mutableStateOf<Outcome?>(null); private set
    var samplePreview by mutableStateOf<AgentPreview?>(null)
    private var draftId = newPromptId()

    val prompt: AgentPrompt? get() = summary?.let { AgentPromptBuilder.build(it, request, draftId) }

    suspend fun openAsk(project: Project) {
        stage = Stage.ASK
        summary = null
        summary = withContext(Dispatchers.Default) { AgentProjectSummary.of(project) { it.placedBounds() } }
    }

    /** The prompt was shared or copied; one pending prompt per editor, a new one replaces it. */
    fun sent() {
        pending = prompt ?: return
        draftId = newPromptId()
        stage = Stage.CLOSED
    }

    fun openReply(text: String? = null) {
        if (text != null) reply = text
        stage = Stage.REPLY
    }

    suspend fun check(project: Project) {
        val parsed = AgentScriptParser.parse(reply)
        outcome = when {
            parsed !is AgentParseResult.Parsed -> Outcome.Unreadable(parsed)
            parsed.script.promptId != pending?.promptId -> Outcome.OtherPrompt(parsed.script.promptId)
            else -> Outcome.Read(parsed.script, executor.preview(project, parsed.script, pending!!.handles))
        }
        stage = Stage.RESULT
    }

    fun close() { stage = Stage.CLOSED; samplePreview = null }

    fun cancelPending() { pending = null; reply = ""; outcome = null; close() }

    private companion object {
        val random = SecureRandom()
        const val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        fun newPromptId() = "p-" + (1..4).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
    }
}
