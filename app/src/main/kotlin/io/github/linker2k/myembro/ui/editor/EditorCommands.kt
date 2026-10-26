// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.edit.commands.*
import io.github.linker2k.myembro.engine.model.EmbThread

/** Geometry/snap calculations stay in their existing helpers; the shared reducer applies poses. */
internal fun Project.poseCommand(next: Project): ProjectCommand {
    require(hoop == next.hoop && objects.map { it.id }.toSet() == next.objects.map { it.id }.toSet())
    val transforms = next.objects.filter { find(it.id)!!.transform != it.transform }
        .associate { it.id to TransformValues(it.transform) }
    val commands = mutableListOf<ProjectCommand>(ProjectCommand.SetTransforms(transforms))
    var order = objects.map { it.id }.toMutableList()
    next.objects.forEachIndexed { index, obj ->
        if (order[index] != obj.id) {
            commands += ProjectCommand.Reorder(obj.id, index)
            order.remove(obj.id); order.add(index, obj.id)
        }
    }
    return ProjectCommand.Batch(commands)
}

/** Preserve the toolbar's historical clamping/no-op policy; tool commands remain strict. */
internal fun Project.reorderCommand(id: String, index: Int): ProjectCommand =
    if (find(id) == null) ProjectCommand.Batch(emptyList())
    else ProjectCommand.Reorder(id, index.coerceIn(0, objects.lastIndex))

internal fun Project.duplicateCommand(copies: Map<String, String>): ProjectCommand = ProjectCommand.Batch(
    copies.mapNotNull { (id, newId) -> find(id)?.let { obj ->
        ProjectCommand.Duplicate(id, newId, TransformValues(obj.transform.copy(
            x = obj.transform.x + 50, y = obj.transform.y + 50)))
    } }
)

internal fun Project.recolorCommand(id: String, index: Int, thread: EmbThread): ProjectCommand {
    val count = when (val obj = find(id)) {
        is ShapeObject -> 0 // Do not emit unsupported region recolor commands.
        is TextObject -> 1
        is StitchObject -> maxOf(obj.source.threads.size, obj.source.colorBlocks.maxOfOrNull { it.threadIndex + 1 } ?: 0)
        null -> 0
    }
    return if (index in 0 until count) ProjectCommand.Recolor(id, index, thread)
        else ProjectCommand.Batch(emptyList())
}

/** Input has already passed engine prepareText in LetteringRenderer; allocate IDs only in the host. */
internal fun Project.letteringCommand(incoming: TextObject, editingId: String?,
    allocateId: () -> String = { java.util.UUID.randomUUID().toString() }): ProjectCommand =
    if (editingId != null) ProjectCommand.ReplaceText(editingId, incoming)
    else ProjectCommand.Add(incoming.withId(if (find(incoming.id) == null) incoming.id else allocateId()))

/** ReplaceText retains pose; inspector pose edits are an explicit part of the same undo unit. */
internal fun textPropertiesCommand(id: String, prepared: TextObject): ProjectCommand = ProjectCommand.Batch(listOf(
    ProjectCommand.ReplaceText(id, prepared), ProjectCommand.SetTransforms(id, prepared.transform)))
