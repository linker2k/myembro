// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit.commands

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.model.EmbThread

/**
 * Pure structural reducer. Wraps existing model operations without changing their compatibility
 * behavior. In particular, this entry rejects bad targets/indices instead of silently no-oping
 * or clamping them. A valid edit may still be outside the hoop: export checks remain separate.
 * No localPattern/placedPattern call, random ID, file access, history mutation or registry lookup.
 */
fun apply(project: Project, command: ProjectCommand): CommandResult {
    val blank = project.objects.filter { it.id.isBlank() }.mapTo(linkedSetOf()) { it.id }
    if (blank.isNotEmpty()) return reject(project, CommandError.INVALID_ID, blank, emptyList(), "Project contains a blank object id")
    val duplicates = project.objects.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
    if (duplicates.isNotEmpty()) return reject(project, CommandError.DUPLICATE_ID, duplicates, emptyList(), "Project contains duplicate object ids")
    return when (val result = reduce(project, command, emptyList())) {
        is CommandResult.Rejected -> result.copy(project = project)
        is CommandResult.Applied -> applied(project, result.project)
    }
}

private fun reject(project: Project, code: CommandError, ids: Set<String>, path: List<Int>, message: String) =
    CommandResult.Rejected(project, code, ids, path, message)

private fun applied(before: Project, candidate: Project): CommandResult.Applied {
    if (candidate == before) return CommandResult.Applied(before, false, emptySet())
    val old = before.objects.withIndex().associate { it.value.id to (it.index to it.value) }
    val next = candidate.objects.withIndex().associate { it.value.id to (it.index to it.value) }
    val changedIds = (old.keys + next.keys).filterTo(linkedSetOf()) { old[it] != next[it] }
    return CommandResult.Applied(candidate, true, changedIds)
}

private fun reduce(project: Project, command: ProjectCommand, path: List<Int>): CommandResult {
    fun error(code: CommandError, ids: Set<String>, message: String) = reject(project, code, ids, path, message)
    fun targets(ids: Set<String>): CommandResult.Rejected? {
        val blank = ids.filterTo(linkedSetOf()) { it.isBlank() }
        if (blank.isNotEmpty()) return error(CommandError.INVALID_ID, blank, "Object id must not be blank")
        val missing = ids.filterTo(linkedSetOf()) { project.find(it) == null }
        return if (missing.isEmpty()) null else error(CommandError.MISSING_OBJECT, missing, "Object does not exist")
    }
    val next = when (command) {
        is ProjectCommand.Add -> {
            val obj = command.obj
            if (obj.id.isBlank()) return error(CommandError.INVALID_ID, setOf(obj.id), "Object id must not be blank")
            if (project.find(obj.id) != null) return error(CommandError.DUPLICATE_ID, setOf(obj.id), "Object id already exists")
            project.add(obj)
        }
        is ProjectCommand.ReplaceText -> {
            targets(setOf(command.id))?.let { return it }
            val original = project.find(command.id) as? TextObject
                ?: return error(CommandError.INVALID_OBJECT_TYPE, setOf(command.id), "Target is not editable text")
            // Host prepared geometry stays cached; this reducer never renders or resolves fonts.
            project.replace(command.text.withId(original.id).withTransform(original.transform).withVisible(original.visible))
        }
        is ProjectCommand.SetTransforms -> {
            targets(command.byId.keys)?.let { return it }
            val invalid = command.byId.filterValues { !it.isValid() }.keys
            if (invalid.isNotEmpty()) return error(CommandError.INVALID_TRANSFORM, invalid, "Transform must be finite with nonzero scales")
            command.byId.entries.fold(project) { p, (id, values) ->
                val obj = p.find(id)!!
                val transform = values.toTransform()
                if (obj.transform == transform) p else p.replace(obj.withTransform(transform))
            }
        }
        is ProjectCommand.Remove -> {
            targets(command.ids)?.let { return it }
            command.ids.fold(project) { p, id -> p.remove(id) }
        }
        is ProjectCommand.Duplicate -> {
            targets(setOf(command.sourceId))?.let { return it }
            if (command.newId.isBlank()) return error(CommandError.INVALID_ID, setOf(command.newId), "New object id must not be blank")
            if (project.find(command.newId) != null) return error(CommandError.DUPLICATE_ID, setOf(command.newId), "Object id already exists")
            if (command.transform?.isValid() == false) return error(CommandError.INVALID_TRANSFORM, setOf(command.newId), "Transform must be finite with nonzero scales")
            val duplicated = project.duplicate(command.sourceId, command.newId)
            if (command.transform == null) duplicated else duplicated.update(command.newId) { it.withTransform(command.transform.toTransform()) }
        }
        is ProjectCommand.Reorder -> {
            targets(setOf(command.id))?.let { return it }
            if (command.index !in project.objects.indices) return error(CommandError.INVALID_REORDER_INDEX, setOf(command.id), "Index must be in the current object list")
            project.reorder(command.id, command.index)
        }
        is ProjectCommand.SetVisible -> {
            targets(setOf(command.id))?.let { return it }
            val obj = project.find(command.id)!!
            if (obj.visible == command.visible) project else project.replace(obj.withVisible(command.visible))
        }
        is ProjectCommand.Recolor -> {
            targets(setOf(command.id))?.let { return it }
            val obj = project.find(command.id)!!
            val count = when (obj) {
                is ShapeObject -> return error(CommandError.INVALID_OBJECT_TYPE, setOf(command.id), "Shape recoloring is not implemented in this draft")
                is TextObject -> 1 // Existing editor recolor changes only the base thread, not character overrides.
                is StitchObject -> maxOf(obj.source.threads.size, obj.source.colorBlocks.maxOfOrNull { it.threadIndex + 1 } ?: 0)
            }
            if (command.threadIndex !in 0 until count) return error(CommandError.INVALID_THREAD_INDEX, setOf(command.id), "Thread index does not exist")
            project.replace(recolor(obj, command.threadIndex, command.thread))
        }
        is ProjectCommand.SetHoop -> if (project.hoop == command.hoop) project else project.copy(hoop = command.hoop)
        is ProjectCommand.Batch -> {
            var candidate = project
            for ((index, child) in command.commands.withIndex()) {
                when (val result = reduce(candidate, child, path + index)) {
                    is CommandResult.Rejected -> return result
                    is CommandResult.Applied -> candidate = result.project
                }
            }
            candidate
        }
    }
    return applied(project, next)
}

/** Same value operation as the existing app EditorAppearance.recolored; no app dependency. */
private fun recolor(obj: DesignObject, index: Int, thread: EmbThread): DesignObject = when (obj) {
    is ShapeObject -> error("Shape recoloring requires a region-aware command")
    is TextObject -> if (obj.thread == thread) obj else obj.copy(thread = thread)
    is StitchObject -> if (obj.source.threadAt(index) == thread) obj else {
        val count = maxOf(obj.source.threads.size, obj.source.colorBlocks.maxOfOrNull { it.threadIndex + 1 } ?: 0)
        val threads = (0 until count).map { if (it == index) thread else obj.source.threadAt(it) }
        obj.copy(source = EmbPattern(obj.source.stitches, threads, obj.source.metadata, obj.source.warnings),
            sourceBytes = null, sourceThreadsEdited = true)
    }
}
