// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.model.EmbThread

/** Preview metadata only; no PES coordinate or sewing parameter depends on it. */
const val FABRIC_COLOR_KEY = "myembro.fabric_argb"
val EmbPattern.fabricColorArgb: Int? get() = metadata[FABRIC_COLOR_KEY]?.toIntOrNull()
data class ProjectThreadColor(val source: EmbThread, val replacement: EmbThread)

fun Project.threadColor(source: EmbThread): EmbThread =
    threadColors.firstOrNull { it.source.sameThread(source) }?.replacement ?: source

fun Project.applyThreadColors(pattern: EmbPattern): EmbPattern = if(threadColors.isEmpty()) pattern else
    EmbPattern(pattern.stitches,pattern.threads.map(::threadColor),pattern.metadata,pattern.warnings)

/** Sewing order, grouped by thread identity; repeated blocks and objects share one palette entry. */
fun Project.sourceThreads(): List<EmbThread> = objects.flatMap { it.localPattern().colorBlocks.map { block -> block.thread } }
    .fold(emptyList()) { acc, thread -> if(acc.any { it.sameThread(thread) }) acc else acc + thread }
fun Project.usedThreads(): List<EmbThread> = sourceThreads().map(::threadColor)
    .fold(emptyList()) { acc, thread -> if(acc.any { it.sameThread(thread) }) acc else acc + thread }

/** A final table-only edit: never rerenders TextObject/ShapeObject or modifies object import bytes. */
fun Project.recolorThread(current: EmbThread, replacement: EmbThread): Project {
    val sources=sourceThreads().filter { threadColor(it).sameThread(current) }
    if(sources.isEmpty() || current.sameThread(replacement)) return this
    val retained=threadColors.filterNot { entry -> sources.any { it.sameThread(entry.source) } }
    val edits=sources.filterNot { it.sameThread(replacement) }.map { ProjectThreadColor(it,replacement) }
    return copy(threadColors=retained+edits)
}
