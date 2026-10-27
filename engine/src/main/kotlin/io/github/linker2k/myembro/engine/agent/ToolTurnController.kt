// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.agent

import io.github.linker2k.myembro.engine.edit.Project
import io.github.linker2k.myembro.engine.edit.commands.*
import kotlinx.serialization.json.*

/** Shared phone/headless orchestration. Only EditSession owns published state and undo history.
 * Tool preparation is private; the full resolved command body is declared to EditSession at commit.
 * Keep one controller per document. Host owns consent and immutable payloads, never JSON booleans. */
class ToolTurnController(val session: EditSession, private val kernel: ToolDispatcher = ToolDispatcher()) {
    data class Call(val name: String, val arguments: JsonElement)
    private class Draft(val id: String, val base: EditSessionSnapshot) {
        var candidate = base.project
        val commands = mutableListOf<ProjectCommand>()
        val calls = mutableListOf<Call>()
    }
    private data class Receipt(val baseRevision: Long, val body: List<Call>?, val export: JsonObject?, val result: ToolDispatcher.Result)
    private val documents=DocumentTools(session,kernel.documentStore)
    private var pending: Draft? = null
    private val receipts = mutableMapOf<String, Receipt>()

    /** Interactive tools: begin/stage/commit or cancel. Mutations outside an explicit turn are rejected. */
    @Synchronized fun dispatch(name: String, arguments: JsonElement, confirmed: Boolean = false, confirmToken: String? = null): ToolDispatcher.Result {
        if (!kernel.knowsTool(name)) return abortError("UNKNOWN_TOOL", name)
        kernel.validateRequest(name, arguments)?.let { return abortError("INVALID_ARGUMENTS", it) }
        val a = arguments.jsonObject
        return when(name) {
            "begin_edit_turn" -> {
                val revision=revision(a) ?: return error("INVALID_ARGUMENTS", "Invalid expectedRevision")
                begin(a.string("transactionId"), revision)
            }
            "commit_edit_turn" -> {
                val revision=revision(a) ?: return error("INVALID_ARGUMENTS", "Invalid expectedRevision")
                commit(a.string("transactionId"),revision)
            }
            "cancel_edit_turn" -> cancel(a.string("transactionId"))
            "undo", "redo" -> {
                if(pending!=null) return error("TURN_IN_PROGRESS", "Cancel or commit the current turn first")
                val revision=revision(a) ?: return error("INVALID_ARGUMENTS", "Invalid expectedRevision")
                history(if(name=="undo") session.undo(revision) else session.redo(revision))
            }
            "get_project" -> snapshotResult()
            "save_project", "save_pes" -> {
                if(pending!=null || session.snapshot().turnInProgress) error("TURN_IN_PROGRESS","Commit or cancel before saving")
                else documents.save(name,a,confirmToken)
            }
            else -> {
                if(name !in ToolDispatcher.SUPPORTED) return abortError("NOT_IMPLEMENTED",name)
                val d=pending
                if(d==null) {
                    // Read-only checks may run outside a turn; prepare_pes releases bytes only for a committed snapshot.
                    if(name in setOf("list_blocks","sewout_check","prepare_pes","list_fonts","get_settings","list_builtin_designs","list_designs","list_emoji","prepare_emoji","prepare_photo")) {
                        val s=session.snapshot();documents.publish(kernel.dispatch(s.project,s.revision.toString(),name,arguments,confirmed))
                    } else error("NO_ACTIVE_TURN","begin_edit_turn is required")
                } else stage(d,Call(name,arguments),confirmed)
            }
        }
    }

    /** A host-received SVG picture prepared against the live hoop; changes nothing (ToolDispatcher.prepareSvgPicture). */
    @Synchronized fun prepareSvgPicture(svg: ByteArray, widthMm: Double, name: String): ToolDispatcher.Result =
        kernel.prepareSvgPicture(session.project, svg, widthMm, name)
    private fun begin(id: String, revision: Long): ToolDispatcher.Result {
        if(id.isBlank()) return error("INVALID_TRANSACTION_ID","Blank transaction id")
        receipts[id]?.let { return if(it.baseRevision==revision) liveReceipt(it) else error("TRANSACTION_ID_REUSED","Base revision changed") }
        pending?.let { return when {
            it.id!=id -> error("TURN_IN_PROGRESS","Another turn is being prepared")
            it.base.revision!=revision -> error("TRANSACTION_ID_REUSED","Base revision changed")
            else -> staged(it)
        } }
        val base=session.snapshot()
        if(base.turnInProgress) return error("TURN_IN_PROGRESS","Engine turn in progress")
        if(base.revision!=revision) return error("REVISION_MISMATCH","Read current project revision")
        return Draft(id,base).also { pending=it }.let(::staged)
    }
    private fun stage(d: Draft, call: Call, confirmed: Boolean): ToolDispatcher.Result {
        val transaction=(call.arguments as? JsonObject)?.get("transactionId")?.jsonPrimitive?.content
        if(transaction!=null && transaction!=d.id) return error("WRONG_TRANSACTION","Call belongs to another turn")
        if(session.revision!=d.base.revision) return abortError("REVISION_MISMATCH","Session changed while preparing")
        val result=kernel.dispatch(d.candidate,d.base.revision.toString(),call.name,call.arguments,confirmed)
        if(!result.ok) return abort(result)
        d.calls+=call
        result.preparedCommand?.let { d.commands+=it;d.candidate=result.project }
        // Never expose bytes or an unpublished candidate as the host's live Project.
        return result.copy(project=session.project,pesBytes=null,preparedCommand=null)
    }
    private fun commit(id: String, revision: Long): ToolDispatcher.Result {
        receipts[id]?.let { return if(it.baseRevision==revision) liveReceipt(it) else error("TRANSACTION_ID_REUSED","Base revision changed") }
        val d=pending ?: return error("NO_ACTIVE_TURN","No turn to commit")
        if(d.id!=id) return error("WRONG_TRANSACTION","Turn belongs to another caller")
        if(revision!=d.base.revision) return abortError("REVISION_MISMATCH","Commit revision does not match begin")
        pending=null
        val result=turn(session.executeTurn(id,revision,d.commands.toList()))
        receipts[id]=Receipt(revision,null,null,result)
        return result
    }
    private fun cancel(id: String): ToolDispatcher.Result {
        receipts[id]?.let { return liveReceipt(it) }
        val d=pending ?: return error("NO_ACTIVE_TURN","No turn to cancel")
        if(d.id!=id) return error("WRONG_TRANSACTION","Turn belongs to another caller")
        pending=null
        // No command was published or passed to engine history during preparation.
        val result=success("cancelled", false)
        receipts[id]=Receipt(d.base.revision,null,null,result)
        return result
    }

    /** Phone command-script path A and CLI share this method: whole-script preparation/check then one commit.
     * dryRun never reserves an id or changes live revision/history/redo/receipts, and returns no PES bytes.
     * Only item approvals supplied by the trusted preview host can authorize undoable object removal. */
    @Synchronized fun executeScript(transactionId: String, expectedRevision: Long, calls: List<Call>,
        exportSettings: JsonObject, dryRun: Boolean = false, approvals: List<ScriptApproval> = emptyList()): ToolDispatcher.Result {
        if(pending!=null || session.snapshot().turnInProgress) return error("TURN_IN_PROGRESS","A turn is already active")
        if(transactionId.isBlank()) return error("INVALID_TRANSACTION_ID","Blank transaction id")
        if(!dryRun) receipts[transactionId]?.let { receipt ->
            return if(receipt.baseRevision==expectedRevision && receipt.body==calls && receipt.export==exportSettings) liveReceipt(receipt)
                else error("TRANSACTION_ID_REUSED","Script body or revision changed")
        }
        val base=session.snapshot()
        if(base.revision!=expectedRevision) return error("REVISION_MISMATCH","Read current project revision")
        val d=Draft(transactionId,base)
        val results=mutableListOf<JsonObject>()
        fun failed(result: ToolDispatcher.Result, index: Int, remember: Boolean = true): ToolDispatcher.Result {
            val output=buildJsonObject {
                result.output.forEach { (key,value)->put(key,value) }
                put("failedCallIndex",index);put("calls",JsonArray(results));put("previewProject",ToolDispatcher.summary(base.project,base.revision.toString()))
                put("changedIds",JsonArray(emptyList()));put("dryRun",dryRun)
            }
            val rejected=result.copy(project=session.project,output=output,pesBytes=null,preparedCommand=null)
            if(!dryRun && remember) receipts[transactionId]=Receipt(expectedRevision,calls.toList(),exportSettings,rejected)
            return rejected
        }
        if(exportSettings.keys!=setOf("machineId","frameId","pesVersion")) return failed(error("INVALID_ARGUMENTS","Invalid export settings"),calls.size)
        val byIndex = approvals.associateBy { it.callIndex }
        if(byIndex.size != approvals.size) return failed(error("INVALID_APPROVAL","Duplicate approval index"),0,false)
        for(approval in approvals) {
            val expected = runCatching { ScriptApprovals.request(transactionId,expectedRevision,calls,approval.callIndex) }.getOrNull()
            if(approval != expected) return failed(error("INVALID_APPROVAL","Approval does not match script, revision or targets"),approval.callIndex,false)
        }
        val approvalRequests = mutableListOf<JsonObject>()
        for((index,call) in calls.withIndex()) {
            if(!kernel.knowsTool(call.name)) return failed(error("UNKNOWN_TOOL",call.name),index)
            kernel.validateRequest(call.name,call.arguments)?.let { return failed(error("INVALID_ARGUMENTS",it),index) }
            // External side effects never belong to a script Apply, even with forged item approvals.
            if(kernel.needsConfirmation(call.name) && call.name != "remove_objects")
                return failed(error("SCRIPT_TOOL_FORBIDDEN","Use a separate confirmation screen for ${call.name}"),index)
            // Lifecycle/history inside a script would violate the single atomic turn boundary.
            if(call.name !in ToolDispatcher.SUPPORTED) return failed(error("NOT_IMPLEMENTED","Tool is unavailable inside a script: ${call.name}"),index)
            val supplied=(call.arguments as? JsonObject)?.get("transactionId")
            if(supplied!=null && supplied!=JsonPrimitive(transactionId)) return failed(error("WRONG_TRANSACTION","All script edits must use the outer transactionId"),index)
            if(call.name == "remove_objects") {
                val request = ScriptApprovals.request(transactionId,expectedRevision,calls,index)
                approvalRequests += request.json()
                if(!dryRun && byIndex[index] != request) {
                    val missing=error("CONFIRMATION_REQUIRED","Select this object removal in the trusted preview",buildJsonObject { put("approvalRequest",request.json()) })
                    return failed(missing,index,false)
                }
            }
            val result=kernel.dispatch(d.candidate,expectedRevision.toString(),call.name,call.arguments,call.name == "remove_objects")
            results+=result.output
            if(!result.ok) return failed(result,index)
            result.preparedCommand?.let { d.commands+=it;d.candidate=result.project }
        }
        val checkArgs=buildJsonObject { put("projectRevision",expectedRevision.toString());exportSettings.forEach { (key,value)->put(key,value) } }
        val checked=kernel.dispatch(d.candidate,expectedRevision.toString(),"prepare_pes",checkArgs)
        val changed=apply(base.project,ProjectCommand.Batch(d.commands.toList())) as? CommandResult.Applied
            ?: return failed(error("COMMAND_REJECTED","Prepared command body failed"),calls.size)
        val preview=buildJsonObject {
            put("dryRun",dryRun);put("calls",JsonArray(results));put("previewProject",ToolDispatcher.summary(d.candidate,expectedRevision.toString()))
            put("changedIds",JsonArray(changed.changedIds.map(::JsonPrimitive)));put("hoopChanged",base.project.hoop!=d.candidate.hoop)
            put("sewout",checked.output);put("needsUserConfirmation",approvalRequests.isNotEmpty());put("approvalRequests",JsonArray(approvalRequests))
        }
        if(!checked.ok || checked.pesBytes==null) {
            val result=ToolDispatcher.Result(session.project,buildJsonObject {
                put("ok",false);put("code","SEWOUT_BLOCKED");put("message","Final candidate did not pass sewing checks")
                preview.forEach { (key,value)->put(key,value) }
            })
            if(!dryRun) receipts[transactionId]=Receipt(expectedRevision,calls.toList(),exportSettings,result)
            return result
        }
        if(dryRun) return ToolDispatcher.Result(session.project,buildJsonObject { put("ok",true);preview.forEach { (key,value)->put(key,value) } })
        val committed=turn(session.executeTurn(transactionId,expectedRevision,d.commands.toList()))
        val result=committed.copy(output=buildJsonObject { committed.output.forEach { (key,value)->put(key,value) };preview.forEach { (key,value)->put(key,value) } },pesBytes=if(committed.ok) checked.pesBytes else null,generatedPes=checked.generatedPes)
        val published=documents.publish(result)
        receipts[transactionId]=Receipt(expectedRevision,calls.toList(),exportSettings,published)
        return published
    }

    private fun revision(a: JsonObject) = a.string("expectedRevision").toLongOrNull()?.takeIf { it>=0 }
    private fun turn(result: TurnResult): ToolDispatcher.Result = when(result) {
        is TurnResult.Committed -> success("committed",result.changed)
        is TurnResult.Cancelled -> success("cancelled",false)
        is TurnResult.Rejected -> result.failure?.let { failure ->
            error(if(failure.kind!=null || failure.code==CommandError.SEWOUT_BLOCKED) failure.code.name else result.code.name,failure.message).let { r ->
                if(failure.kind==null) r else r.copy(output=JsonObject(r.output+("kind" to JsonPrimitive(failure.kind.name))))
            }
        } ?: error(result.code.name,result.code.name)
        is TurnResult.InProgress -> error("INCOMPLETE_TURN","Unexpected unfinished engine turn")
    }
    private fun history(result: SessionHistoryResult) = when(result) {
        is SessionHistoryResult.Applied -> success("history",result.changed)
        is SessionHistoryResult.Rejected -> error(result.code.name,result.code.name)
    }
    private fun snapshotResult(): ToolDispatcher.Result {
        val s=session.snapshot()
        return ToolDispatcher.Result(s.project,buildJsonObject {
            put("ok",true);put("project",ToolDispatcher.summary(s.project,s.revision.toString()))
            put("undoCount",s.undoCount);put("redoCount",s.redoCount);put("turnInProgress",s.turnInProgress || pending!=null)
            put("canUndo",s.canUndo && pending==null);put("canRedo",s.canRedo && pending==null)
        })
    }
    private fun success(status: String,changed: Boolean) = snapshotResult().let { it.copy(output=JsonObject(it.output+mapOf("status" to JsonPrimitive(status),"changed" to JsonPrimitive(changed)))) }
    private fun staged(d: Draft) = success("preparing",false).let { it.copy(output=JsonObject(it.output+("preparedCommands" to JsonPrimitive(d.commands.size)))) }
    private fun error(code: String,message: String,details: JsonObject = buildJsonObject {}) = ToolDispatcher.Result(session.project,buildJsonObject { put("ok",false);put("code",code);put("message",message);put("details",details) })
    private fun abortError(code: String,message: String) = abort(error(code,message))
    private fun abort(result: ToolDispatcher.Result): ToolDispatcher.Result {
        pending?.let { d -> pending=null;receipts[d.id]=Receipt(d.base.revision,null,null,result.copy(project=session.project,pesBytes=null,preparedCommand=null)) }
        return result.copy(project=session.project,pesBytes=null,preparedCommand=null)
    }
    private fun liveReceipt(receipt: Receipt) = receipt.result.copy(project=session.project,pesBytes=null,preparedCommand=null,
        output=buildJsonObject { receipt.result.output.forEach { (key,value)->put(key,value) };put("replayed",true);put("currentRevision",session.revision.toString());put("project",ToolDispatcher.summary(session.project,session.revision.toString())) })
    companion object { val SUPPORTED = ToolDispatcher.SUPPORTED+setOf("begin_edit_turn","commit_edit_turn","cancel_edit_turn","undo","redo","save_project","save_pes") }
}
