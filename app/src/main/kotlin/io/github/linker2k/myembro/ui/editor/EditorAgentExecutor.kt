// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import io.github.linker2k.myembro.engine.agent.BundledFontResolver
import io.github.linker2k.myembro.engine.agent.ScriptApprovals
import io.github.linker2k.myembro.engine.agent.ToolDispatcher
import io.github.linker2k.myembro.engine.agent.ToolTurnController
import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.edit.Project
import io.github.linker2k.myembro.engine.edit.commands.EditSession
import io.github.linker2k.myembro.engine.sewout.SewoutMachines
import io.github.linker2k.myembro.ui.agent.*
import io.github.linker2k.myembro.ui.settings.EmbroiderySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * Phone path A on the editor's own session (docs/agent/dispatcher.md): preview = dispatcher dry-run,
 * apply = one engine turn, so the toolbar undo removes the whole script. prepare_photo runs before
 * the turn (it changes nothing); tools to do separately in the app are never run.
 */
internal class EditorAgentExecutor(
    private val session: () -> EditorSession,
    private val settings: () -> EmbroiderySettings,
    private val hoops: () -> List<HoopSpec>,
    /** Runs [block] with editing blocked, then refreshes the editor state on the main thread. */
    private val publish: suspend (suspend () -> AgentApplyResult) -> AgentApplyResult,
    /** The phone binds its fonts, designs, emoji, settings and library store here (androidAgentKernel). */
    private val kernelFactory: (Map<String, HoopSpec>) -> ToolDispatcher = { ToolDispatcher(frames = it) },
    /** Exact font revision for a font id, as the kernel's font resolver reports it. */
    private val fontRevision: (String) -> String? = BundledFontResolver()::revision,
) : AgentScriptExecutor {
    /** One kernel per frame set, so the session's controller and its receipts are reused. */
    private val kernels = mutableMapOf<Map<String, HoopSpec>, ToolDispatcher>()

    /** Prepared photo result ids per kernel, script and command, so preview and apply add the same result. */
    private val prepared = mutableMapOf<Triple<ToolDispatcher, String, ToolTurnController.Call>, String>()

    /** [run] is [calls] with prepared result ids put in: what the engine turn executes. */
    private class Setup(val calls: AgentScriptCalls, val run: List<ToolTurnController.Call>, val kernel: ToolDispatcher,
        val export: JsonObject, val base: Project)

    private fun setup(script: AgentScript, handles: AgentHandles): Pair<Setup?, List<AgentProblem>> {
        val calls = AgentScriptCalls.of(script, handles, "${AgentScriptCalls.NEW_PREFIX}${script.promptId}", fontRevision)
        val base = session().project
        val frames = (hoops() + base.hoop).associateBy(::frameId)
        val machine = settings().machineProfile?.id?.takeIf { id -> SewoutMachines.all.any { it.id == id } }
            ?: return null to listOf(AgentProblem("MACHINE_UNKNOWN", null, null, ""))
        if (calls.problems.isNotEmpty()) return null to calls.problems
        val export = buildJsonObject {
            put("machineId", machine); put("frameId", frameId(base.hoop)); put("pesVersion", settings().pesVersion.name)
        }
        val kernel = kernels.getOrPut(frames) { kernelFactory(frames) }
        // prepare_photo changes nothing: it runs before the turn, and the turn only adds its result.
        val results = calls.prepares.associate { p ->
            p.handle to (prepared[Triple(kernel, calls.transactionId, p.call)] ?: run {
                val result = kernel.dispatch(base, "0", p.call.name, p.call.arguments)
                if (!result.ok) return null to listOf(AgentProblem(result.code ?: "REJECTED", p.index, p.call.name,
                    result.output["message"]?.jsonPrimitive?.contentOrNull.orEmpty()))
                result.output.getValue("resultId").jsonPrimitive.content.also { prepared[Triple(kernel, calls.transactionId, p.call)] = it }
            })
        }
        val (run, missing) = calls.withResults(results)
        if (missing != null) return null to listOf(missing)
        return Setup(calls, run, kernel, export, base) to emptyList()
    }

    override suspend fun preview(script: AgentScript, handles: AgentHandles, expectedRevision: Long): AgentPreviewResult {
        // Font revisions load fonts: never on the main thread.
        val (setup, problems) = withContext(Dispatchers.Default) { setup(script, handles) }
        setup ?: return AgentPreviewResult.Rejected(problems)
        if (setup.run.isEmpty()) return AgentPreviewResult.Ready(AgentPreview(script.summary, emptyList(), null,
            emptyList(), setup.calls.separate, setup.base, setup.base))
        val result = withContext(Dispatchers.Default) {
            session().runCommandScript(setup.kernel, setup.calls.transactionId, expectedRevision, setup.run, setup.export, dryRun = true)
        }
        val blocked = result.code == "SEWOUT_BLOCKED"
        if (!result.ok && !blocked) return AgentPreviewResult.Rejected(listOf(problem(result, setup.calls)))
        // The dry run returns only a summary; the same calls on a throwaway session give the picture.
        val after = withContext(Dispatchers.Default) {
            setup.kernel.bind(EditSession(setup.base))
                .executeScript(setup.calls.transactionId, 0, setup.run, setup.export,
                    approvals = approvals(setup, 0, setup.calls.removals(setup.base).map { it.callIndex })).takeIf { it.ok }?.project
        }
        return AgentPreviewResult.Ready(AgentPreview(script.summary, setup.calls.changes(setup.base),
            if (blocked) findings(result.output) else null, setup.calls.removals(setup.base), setup.calls.separate,
            before = setup.base, after = after))
    }

    override suspend fun apply(script: AgentScript, handles: AgentHandles, expectedRevision: Long, approvedRemovals: Set<Int>): AgentApplyResult {
        // Font revisions load fonts: never on the main thread.
        val (setup, problems) = withContext(Dispatchers.Default) { setup(script, handles) }
        setup ?: return AgentApplyResult.Rejected(problems)
        // One turn, nothing partial: a script that also asks for a separate action is not applied at all.
        if (setup.calls.separate.isNotEmpty()) return AgentApplyResult.Rejected(setup.calls.separate.map {
            AgentProblem("SCRIPT_TOOL_FORBIDDEN", script.commands.indexOfFirst { c -> c.tool == it }, it, "") })
        if (setup.run.isEmpty()) return AgentApplyResult.Applied
        return publish {
            val result = withContext(Dispatchers.Default) {
                session().runCommandScript(setup.kernel, setup.calls.transactionId, expectedRevision, setup.run, setup.export,
                    approvals = approvals(setup, expectedRevision, approvedRemovals))
            }
            if (result.ok) AgentApplyResult.Applied
            else AgentApplyResult.Rejected(listOf(problem(result, setup.calls)))
        }
    }

    /** Per-item approvals for the ticked removals only, bound to the whole script and its base revision. */
    private fun approvals(setup: Setup, revision: Long, indices: Collection<Int>) =
        indices.sorted().map { ScriptApprovals.request(setup.calls.transactionId, revision, setup.run, it) }

    private fun problem(result: ToolDispatcher.Result, calls: AgentScriptCalls): AgentProblem {
        val index = result.output["failedCallIndex"]?.jsonPrimitive?.intOrNull
        val call = index?.let { calls.calls.getOrNull(it) }
        return AgentProblem(result.code ?: "REJECTED", index?.let { calls.indices.getOrNull(it) }, call?.name,
            result.output["message"]?.jsonPrimitive?.contentOrNull.orEmpty())
    }

    private fun findings(output: JsonObject): List<String> {
        val sewout = output["sewout"]?.jsonObject
        val details = sewout?.get("details")?.jsonObject ?: sewout?.get("checks")?.jsonObject
        val messages = listOf("report", "placement").flatMap { key ->
            details?.get(key)?.jsonObject?.get("findings")?.jsonArray.orEmpty()
                .map { it.jsonObject }.filter { it["severity"]?.jsonPrimitive?.content == "BLOCKER" }
                .map { it["message"]?.jsonPrimitive?.content.orEmpty() }
        }.distinct()
        return messages.ifEmpty { listOfNotNull(sewout?.get("message")?.jsonPrimitive?.contentOrNull ?: output["message"]?.jsonPrimitive?.contentOrNull) }
    }

    companion object {
        /** Frame handle the dispatcher resolves: the hoop's own name, or its size when unnamed. */
        fun frameId(hoop: HoopSpec) = hoop.name.ifBlank { "${hoop.width}x${hoop.height}" }
    }
}
