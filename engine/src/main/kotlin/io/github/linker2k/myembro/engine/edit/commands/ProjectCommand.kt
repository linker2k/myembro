// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit.commands

import io.github.linker2k.myembro.engine.edit.DesignObject
import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.edit.TextObject
import io.github.linker2k.myembro.engine.edit.Transform
import io.github.linker2k.myembro.engine.model.EmbThread

/**
 * Unvalidated command input. Unlike Transform, this can represent invalid caller input so apply
 * can return a typed rejection instead of throwing while the caller constructs the command.
 * Coordinates are centered native 0.1mm units, angles degrees, scales signed multipliers.
 */
data class TransformValues(
    val x: Double = 0.0,
    val y: Double = 0.0,
    val rotationDeg: Double = 0.0,
    val scaleX: Double = 1.0,
    val scaleY: Double = 1.0,
) {
    constructor(transform: Transform) : this(transform.x, transform.y, transform.rotationDeg,
        transform.scaleX, transform.scaleY)

    internal fun isValid(): Boolean = listOf(x, y, rotationDeg, scaleX, scaleY).all { it.isFinite() } &&
        scaleX != 0.0 && scaleY != 0.0

    internal fun toTransform(): Transform = Transform(x, y, rotationDeg, scaleX, scaleY)
}

/**
 * Explicit value edits only: no selection, UUID allocation, history, I/O, font preparation or
 * export authorization. As with Project/History, callers must not mutate supplied arrays/lists.
 */
sealed interface ProjectCommand {
    data class SetBlockStitch(val id: String, val regionIds: Set<String>,
        val change: io.github.linker2k.myembro.engine.edit.BlockStitchPatch) : ProjectCommand
    data class Add(val obj: DesignObject) : ProjectCommand
    /** Prepared text content only; target ID, pose, visibility and sewing index are retained. */
    data class ReplaceText(val id: String, val text: TextObject) : ProjectCommand
    data class SetTransforms(val byId: Map<String, TransformValues>) : ProjectCommand {
        constructor(id: String, transform: Transform) : this(mapOf(id to TransformValues(transform)))
    }
    data class Remove(val ids: Set<String>) : ProjectCommand
    /** Null transform retains the source pose, exactly as Project.duplicate does. */
    data class Duplicate(val sourceId: String, val newId: String,
        val transform: TransformValues? = null) : ProjectCommand
    /** Final index in the current list, including the object being moved. */
    data class Reorder(val id: String, val index: Int) : ProjectCommand
    data class SetVisible(val id: String, val visible: Boolean) : ProjectCommand
    data class Recolor(val id: String, val threadIndex: Int, val thread: EmbThread) : ProjectCommand
    data class SetHoop(val hoop: HoopSpec) : ProjectCommand
    data class Batch(val commands: List<ProjectCommand>) : ProjectCommand
}
