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
        data class Applied(val script: AgentScript) : Outcome
        /** Nothing was applied; [read] is the preview to go back to. */
        data class ApplyFailed(val read: Read, val problems: List<AgentProblem>) : Outcome
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
    /** Photos picked for the next prompt; only their ids and sizes go into it. */
    var attachments by mutableStateOf<List<AgentAttachment>>(emptyList()); private set
    /** The picked content for each attachment id, kept in the app for prepare_photo; never sent. */
    private var sources = mapOf<String, String>()
    private var nextAttachment = 1

    val prompt: AgentPrompt? get() = summary?.let { AgentPromptBuilder.build(it, request, draftId, attachments = attachments) }

    /** A photo from the app's picker: [source] is its content URI, [widthPx]×[heightPx] its upright size. */
    fun attach(source: String, widthPx: Int, heightPx: Int): AgentAttachment {
        val attachment = AgentAttachment("a${nextAttachment++}", widthPx, heightPx)
        attachments = attachments + attachment
        sources = sources + (attachment.id to source)
        return attachment
    }

    fun detach(id: String) { attachments = attachments.filter { it.id != id }; sources = sources - id }

    /** The picked photo behind an attachment id of the pending prompt (for prepare_photo once connected). */
    fun source(id: String): String? = pendingSources[id]
    private var pendingSources = mapOf<String, String>()

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
        pendingSources = sources.filterKeys { id -> attachments.any { it.id == id } }
        attachments = emptyList(); sources = emptyMap(); nextAttachment = 1
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
    suspend fun apply(approvedRemovals: Set<Int>) {
        val read = outcome as? Outcome.Read ?: return
        val waiting = pendingPrompt ?: return
        if (applying) return
        applying = true
        try {
            outcome = when (val result = executor.apply(read.script, waiting.prompt.handles, waiting.revision, approvedRemovals)) {
                AgentApplyResult.Applied -> Outcome.Applied(read.script).also { pendingPrompt = null; pendingSources = emptyMap(); reply = "" }
                is AgentApplyResult.Rejected -> Outcome.ApplyFailed(read, result.problems)
            }
        } finally { applying = false }
    }

    fun close() { stage = Stage.CLOSED; samplePreview = null }

    /** After a refused Apply: the same preview again, so the user can tick removals and retry. */
    fun backToPreview() { (outcome as? Outcome.ApplyFailed)?.let { outcome = it.read } }

    fun cancelPending() { pendingPrompt = null; pendingSources = emptyMap(); reply = ""; outcome = null; close() }

    private companion object {
        val random = SecureRandom()
        const val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        fun newPromptId() = "p-" + (1..4).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
    }
}
