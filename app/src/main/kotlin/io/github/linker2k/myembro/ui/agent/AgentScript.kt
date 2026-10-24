// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.agent

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

internal data class AgentCommand(val tool: String, val args: JsonObject)

internal data class AgentScript(val promptId: String, val summary: String, val commands: List<AgentCommand>)

/** Why a reply could not be read. [path] is a JSON path such as `commands[2].args`, empty for the whole reply. */
internal data class AgentScriptProblem(val code: Code, val path: String = "", val detail: String = "") {
    enum class Code { NO_SCRIPT, MULTIPLE_SCRIPTS, BROKEN_JSON, UNSUPPORTED_VERSION, NOT_AN_OBJECT, MISSING_FIELD,
        UNKNOWN_FIELD, WRONG_TYPE, NO_COMMANDS }
}

internal sealed interface AgentParseResult {
    data class Parsed(val script: AgentScript) : AgentParseResult
    /** [line]/[column] are 1-based inside the extracted JSON when the parser reported an offset. */
    data class Broken(val line: Int?, val column: Int?, val excerpt: String, val message: String) : AgentParseResult
    data class Invalid(val problems: List<AgentScriptProblem>) : AgentParseResult
}

/**
 * Finds the one myembroScript in a chatbot reply and reads its envelope strictly. Tool names and
 * arguments are validated later by the [AgentScriptExecutor], against the command layer.
 */
internal object AgentScriptParser {
    const val VERSION = 1
    private const val MARKER = "\"myembroScript\""
    private val FENCE = Regex("```[ \\t]*([A-Za-z0-9_+-]*)[^\\n]*\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)
    private val OFFSET = Regex("offset (\\d+)")
    private val json = Json // strict: no lenient quoting, comments or trailing commas

    fun parse(reply: String): AgentParseResult {
        val text = reply.replace("\r\n", "\n").replace('\r', '\n')
        val candidate = when (val found = extract(text)) {
            is Extracted.One -> found.json
            is Extracted.Problem -> return AgentParseResult.Invalid(listOf(found.problem))
        }
        val root = try {
            json.parseToJsonElement(candidate)
        } catch (e: SerializationException) {
            return broken(candidate, e.message.orEmpty())
        } catch (e: IllegalArgumentException) {
            return broken(candidate, e.message.orEmpty())
        }
        return envelope(root)
    }

    private sealed interface Extracted {
        data class One(val json: String) : Extracted
        data class Problem(val problem: AgentScriptProblem) : Extracted
    }

    private fun extract(text: String): Extracted {
        val fenced = FENCE.findAll(text).map { it.groupValues[1].lowercase() to it.groupValues[2].trim() }.toList()
        val marked = fenced.filter { MARKER in it.second }
        if (marked.size > 1) return Extracted.Problem(AgentScriptProblem(AgentScriptProblem.Code.MULTIPLE_SCRIPTS, detail = "${marked.size}"))
        if (marked.size == 1) return Extracted.One(marked.single().second)
        val count = Regex(Regex.escape(MARKER)).findAll(text).count()
        if (count > 1) return Extracted.Problem(AgentScriptProblem(AgentScriptProblem.Code.MULTIPLE_SCRIPTS, detail = "$count"))
        if (count == 1) {
            // Unfenced: the object that encloses the marker, possibly cut short (then the parser reports where).
            val start = text.lastIndexOf('{', text.indexOf(MARKER))
            if (start >= 0) return Extracted.One(text.substring(start, objectEnd(text, start)).trim())
        }
        // A single JSON fence without the marker is still the reply; the envelope check names what is missing.
        val jsonFences = fenced.filter { it.first == "json" || it.second.startsWith("{") }
        if (jsonFences.size == 1) return Extracted.One(jsonFences.single().second)
        return Extracted.Problem(AgentScriptProblem(AgentScriptProblem.Code.NO_SCRIPT, detail = text.trim().lineSequence().take(3).joinToString("\n")))
    }

    /** End (exclusive) of the balanced object starting at [start], or the end of the text if it never closes. */
    private fun objectEnd(text: String, start: Int): Int {
        var depth = 0; var inString = false; var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') inString = false
            } else when (c) {
                '"' -> inString = true
                '{', '[' -> depth++
                '}', ']' -> if (--depth == 0) return i + 1
            }
        }
        return text.length
    }

    private fun broken(candidate: String, message: String): AgentParseResult.Broken {
        val offset = OFFSET.find(message)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, candidate.length)
        val firstLine = message.lineSequence().firstOrNull().orEmpty()
        if (offset == null) return AgentParseResult.Broken(null, null, "", firstLine)
        val before = candidate.substring(0, offset)
        val line = before.count { it == '\n' } + 1
        val column = offset - (before.lastIndexOf('\n') + 1) + 1
        return AgentParseResult.Broken(line, column, candidate.lines()[line - 1], firstLine)
    }

    private fun envelope(root: JsonElement): AgentParseResult {
        val problems = mutableListOf<AgentScriptProblem>()
        fun problem(code: AgentScriptProblem.Code, path: String, detail: String = "") { problems += AgentScriptProblem(code, path, detail) }
        val obj = root as? JsonObject ?: return AgentParseResult.Invalid(listOf(AgentScriptProblem(AgentScriptProblem.Code.NOT_AN_OBJECT)))
        (obj.keys - setOf("myembroScript", "promptId", "summary", "commands")).forEach { problem(AgentScriptProblem.Code.UNKNOWN_FIELD, it) }
        when (val version = obj["myembroScript"]) {
            null -> problem(AgentScriptProblem.Code.MISSING_FIELD, "myembroScript")
            else -> if ((version as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull != VERSION)
                return AgentParseResult.Invalid(listOf(AgentScriptProblem(AgentScriptProblem.Code.UNSUPPORTED_VERSION, "myembroScript", version.toString())))
        }
        fun string(key: String): String? = when (val v = obj[key]) {
            null -> null.also { problem(AgentScriptProblem.Code.MISSING_FIELD, key) }
            is JsonPrimitive -> if (v.isString) v.content else null.also { problem(AgentScriptProblem.Code.WRONG_TYPE, key) }
            else -> null.also { problem(AgentScriptProblem.Code.WRONG_TYPE, key) }
        }
        val promptId = string("promptId")
        val summary = string("summary")
        val commands = mutableListOf<AgentCommand>()
        when (val list = obj["commands"]) {
            null -> problem(AgentScriptProblem.Code.MISSING_FIELD, "commands")
            !is JsonArray -> problem(AgentScriptProblem.Code.WRONG_TYPE, "commands")
            else -> {
                if (list.isEmpty()) problem(AgentScriptProblem.Code.NO_COMMANDS, "commands")
                list.forEachIndexed { i, item ->
                    val path = "commands[$i]"
                    val command = item as? JsonObject ?: return@forEachIndexed problem(AgentScriptProblem.Code.WRONG_TYPE, path)
                    (command.keys - setOf("tool", "args")).forEach { problem(AgentScriptProblem.Code.UNKNOWN_FIELD, "$path.$it") }
                    val tool = (command["tool"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    val args = command["args"] as? JsonObject
                    if (command["tool"] == null) problem(AgentScriptProblem.Code.MISSING_FIELD, "$path.tool")
                    else if (tool.isNullOrBlank()) problem(AgentScriptProblem.Code.WRONG_TYPE, "$path.tool")
                    if (command["args"] == null) problem(AgentScriptProblem.Code.MISSING_FIELD, "$path.args")
                    else if (args == null) problem(AgentScriptProblem.Code.WRONG_TYPE, "$path.args")
                    if (!tool.isNullOrBlank() && args != null) commands += AgentCommand(tool, args)
                }
            }
        }
        return if (problems.isEmpty()) AgentParseResult.Parsed(AgentScript(promptId!!, summary!!, commands))
        else AgentParseResult.Invalid(problems)
    }
}
