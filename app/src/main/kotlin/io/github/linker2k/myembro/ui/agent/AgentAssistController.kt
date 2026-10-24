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
internal class AgentAssistController(
    private val executor: AgentScriptExecutor = NotConnectedAgentScriptExecutor,
    /** The editor session revision; a prompt describes exactly one. */
    private val revision: () -> Long = { 0L },
    /** Fonts and built-in designs listed in the prompt. */
    private val catalog: suspend () -> Pair<List<AgentFontInfo>, List<AgentDesignInfo>> = { emptyList<AgentFontInfo>() to emptyList() },
) {
    enum class Stage { CLOSED, ASK, REPLY, RESULT }

    sealed interface Outcome {
        data class Unreadable(val result: AgentParseResult) : Outcome
        /** The reply answers a prompt that is not the pending one (or none is pending). */
        data class OtherPrompt(val promptId: String) : Outcome
        data class Read(val script: AgentScript, val result: AgentPreviewResult) : Outcome
        data class Applied(val script: AgentScript, val followUps: List<String>) : Outcome
        data class ApplyFailed(val script: AgentScript, val problems: List<AgentProblem>) : Outcome
    }

    private data class Pending(val prompt: AgentPrompt, val revision: Long)

    var stage by mutableStateOf(Stage.CLOSED); private set
    var request by mutableStateOf("")
    var reply by mutableStateOf("")
    var summary by mutableStateOf<AgentProjectSummary?>(null); private set
    private var pendingPrompt by mutableStateOf<Pending?>(null)
    /** Recorded once the prompt leaves the app (shared or copied). */
    val pending: AgentPrompt? get() = pendingPrompt?.prompt
    var outcome by mutableStateOf<Outcome?>(null); private set
    var applying by mutableStateOf(false); private set
    var samplePreview by mutableStateOf<AgentPreview?>(null)
    private var draftId = newPromptId()
    private var draftRevision = 0L

    val prompt: AgentPrompt? get() = summary?.let { AgentPromptBuilder.build(it, request, draftId) }

    suspend fun openAsk(project: Project) {
        stage = Stage.ASK
        summary = null
        draftRevision = revision()
        val (fonts, designs) = catalog()
        summary = withContext(Dispatchers.Default) { AgentProjectSummary.of(project, fonts, designs) { it.placedBounds() } }
    }

    /** The prompt was shared or copied; one pending prompt per editor, a new one replaces it. */
    fun sent() {
        pendingPrompt = Pending(prompt ?: return, draftRevision)
        draftId = newPromptId()
        stage = Stage.CLOSED
    }

    fun openReply(text: String? = null) {
        if (text != null) reply = text
        stage = Stage.REPLY
    }

    suspend fun check() {
        val parsed = AgentScriptParser.parse(reply)
        val waiting = pendingPrompt
        outcome = when {
            parsed !is AgentParseResult.Parsed -> Outcome.Unreadable(parsed)
            waiting == null || parsed.script.promptId != waiting.prompt.promptId -> Outcome.OtherPrompt(parsed.script.promptId)
            else -> Outcome.Read(parsed.script, executor.preview(parsed.script, waiting.prompt.handles, waiting.revision))
        }
        stage = Stage.RESULT
    }

    /** Commits the previewed script as one undo step; the pending prompt is used up on success. */
    suspend fun apply(removalConfirmed: Boolean) {
        val read = outcome as? Outcome.Read ?: return
        val waiting = pendingPrompt ?: return
        if (applying) return
        applying = true
        try {
            outcome = when (val result = executor.apply(read.script, waiting.prompt.handles, waiting.revision, removalConfirmed)) {
                is AgentApplyResult.Applied -> Outcome.Applied(read.script, result.followUps).also { pendingPrompt = null; reply = "" }
                is AgentApplyResult.Rejected -> Outcome.ApplyFailed(read.script, result.problems)
            }
        } finally { applying = false }
    }

    fun close() { stage = Stage.CLOSED; samplePreview = null }

    fun cancelPending() { pendingPrompt = null; reply = ""; outcome = null; close() }

    private companion object {
        val random = SecureRandom()
        const val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        fun newPromptId() = "p-" + (1..4).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
    }
}
