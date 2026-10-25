// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.agent

import kotlinx.serialization.json.*
import java.security.MessageDigest

/** Trusted UI metadata, never tool arguments. callIndex is zero-based.
 * The host supplies only items explicitly checked by the user, never the whole preview automatically. */
data class ScriptApproval(val callIndex: Int, val targetIds: List<String>, val argumentsHash: String) {
    fun json() = buildJsonObject {
        put("callIndex",callIndex)
        put("targetIds",JsonArray(targetIds.map(::JsonPrimitive)))
        put("argumentsHash",argumentsHash)
    }
}

object ScriptApprovals {
    /** Binds each deletion to the entire script, transaction and base revision: editing any call invalidates it. */
    fun request(transactionId: String, revision: Long, calls: List<ToolTurnController.Call>, index: Int): ScriptApproval {
        val call=calls[index]
        require(call.name == "remove_objects") { "Only undoable object removal can be approved inside a script" }
        val ids=call.arguments.jsonObject.getValue("objectIds").jsonArray.map { it.jsonPrimitive.content }.sorted()
        val body=buildJsonObject {
            put("transactionId",transactionId);put("revision",revision);put("callIndex",index)
            put("calls",JsonArray(calls.map { buildJsonObject { put("name",it.name);put("arguments",it.arguments) } }))
        }
        val hash=MessageDigest.getInstance("SHA-256").digest(canonical(body).toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return ScriptApproval(index,ids,hash)
    }
    private fun canonical(value: JsonElement): JsonElement = when(value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
        is JsonArray -> JsonArray(value.map(::canonical))
        else -> value
    }
}
