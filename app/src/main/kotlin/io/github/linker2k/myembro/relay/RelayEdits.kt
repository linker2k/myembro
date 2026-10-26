// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.relay

import io.github.linker2k.myembro.engine.agent.ToolDispatcher
import io.github.linker2k.myembro.engine.edit.Project
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

/** What one relay edit answers, so Claude can check the result before it calls render_preview. */
object RelayEdits {
    /**
     * ok, revision and the project after the call (objects with placed bounds in mm and thread colors, as
     * get_project shows them); the sewout verdict when the check ran (PASS, or BLOCK with its findings);
     * code and message when nothing was applied.
     */
    fun result(result: ToolDispatcher.Result, revision: Long, project: Project): JsonObject = buildJsonObject {
        val output = result.output
        put("ok", result.ok)
        if (!result.ok) { put("code", result.code ?: "REJECTED"); put("message", output["message"] ?: JsonPrimitive("")) }
        put("revision", revision.toString())
        put("changed", result.ok && output["changed"]?.jsonPrimitive?.booleanOrNull == true)
        put("changedIds", if (result.ok) output["changedIds"] ?: JsonArray(emptyList()) else JsonArray(emptyList()))
        put("project", ToolDispatcher.summary(project, revision.toString()))
        output["sewout"]?.jsonObject?.let { put("sewout", sewout(it)) }
    }

    fun failure(code: String, message: String, revision: Long, project: Project) = result(
        ToolDispatcher.Result(project, buildJsonObject { put("ok", false); put("code", code); put("message", message) }), revision, project)

    /** prepare_pes output of the turn's final candidate: PASS only for a complete passing check. */
    private fun sewout(check: JsonObject) = buildJsonObject {
        val passed = check["ok"]?.jsonPrimitive?.booleanOrNull == true
        put("verdict", if (passed) "PASS" else "BLOCK")
        val details = check["checks"]?.jsonObject ?: check["details"]?.jsonObject
        val findings = listOf("report", "placement").flatMap { key ->
            details?.get(key)?.jsonObject?.get("findings")?.jsonArray.orEmpty().map { it.jsonObject }
        }.distinct().toMutableList()
        details?.get("viewport")?.jsonObject?.get("errors")?.jsonArray.orEmpty().forEach { e ->
            findings += buildJsonObject { put("code", "PEC_VIEWPORT"); put("severity", "BLOCKER"); put("message", e) }
        }
        if (!passed && findings.isEmpty()) findings += buildJsonObject {
            put("code", check["code"] ?: JsonPrimitive("SEWOUT_BLOCKED")); put("severity", "BLOCKER"); put("message", check["message"] ?: JsonPrimitive(""))
        }
        put("findings", JsonArray(findings))
    }
}

/**
 * The editor's dialog for a relay remove_objects: the tool waits for the user's answer up to [timeoutMs]
 * (plan: 90 s); no answer is a no. One question at a time.
 */
class RelayConfirmation(private val timeoutMs: Long = 90_000) {
    class Request(val names: List<String>, internal val answer: CompletableDeferred<Boolean> = CompletableDeferred())

    private val state = MutableStateFlow<Request?>(null)
    /** The question on screen, null when none. */
    val request: StateFlow<Request?> = state
    private val one = Mutex()

    suspend fun ask(names: List<String>): Boolean = one.withLock {
        val r = Request(names)
        state.value = r
        try { withTimeoutOrNull(timeoutMs) { r.answer.await() } ?: false } finally { state.value = null }
    }

    fun answer(request: Request, yes: Boolean) { request.answer.complete(yes) }
}
