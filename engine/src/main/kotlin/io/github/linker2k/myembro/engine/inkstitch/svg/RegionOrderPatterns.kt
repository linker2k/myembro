// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg

import io.github.linker2k.myembro.engine.model.*
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor as G
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.SewingOptions

/** Finished native fragments: never regenerate region routing when only sewing order changes. */
internal object RegionOrderPatterns {
    fun split(pattern: EmbPattern, owners: List<String?>, threads: Map<String,EmbThread>): Map<String,EmbPattern> {
        require(owners.size == pattern.stitches.size)
        val indices = threads.keys.associateWith { mutableListOf<Int>() }
        var active: String? = null
        pattern.stitches.forEachIndexed { i,s ->
            owners[i]?.let { active = it }
            if (s.command != Command.COLOR_CHANGE && s.command != Command.END) {
                check(s.command != Command.STITCH || owners[i] != null) { "Missing region needle provenance" }
                active?.let { indices.getValue(it).add(i) }
            }
        }
        val locks=pattern.metadata[G.LOCK_INDICES_KEY].orEmpty().split(',').mapNotNull(String::toIntOrNull).toSet()
        return indices.mapValues { (id, list) ->
            val body=list.map { pattern.stitches[it] }.toMutableList()
            val lockPositions=list.mapIndexedNotNull { out, source -> out.takeIf { source in locks } }.toMutableList()
            // A formerly continuous same-thread region retains its first sewn edge's original start.
            if(body.firstOrNull()?.command == Command.STITCH) {
                val previous=pattern.stitches.getOrNull(list.first()-1)
                body.add(0,(previous ?: body.first()).copy(command=Command.JUMP))
                for(i in lockPositions.indices) lockPositions[i]++
            }
            body.lastOrNull()?.let { body += it.copy(command=Command.END) }
            EmbPattern(body,listOf(threads.getValue(id)),mapOf(G.FINISHED_KEY to "v1",
                G.SEWING_OPTIONS_KEY to SewingOptions().toJson(),G.LOCK_INDICES_KEY to lockPositions.joinToString(",")))
        }
    }

    fun compose(order: List<String>, fragments: Map<String,EmbPattern>, baseline: EmbPattern): EmbPattern {
        val out=mutableListOf<Stitch>();val threads=mutableListOf<EmbThread>();val locks=mutableListOf<Int>()
        for(id in order) {
            val fragment=fragments.getValue(id)
            val body=fragment.stitches.takeWhile { it.command!=Command.END }
            if(body.none { it.command==Command.STITCH }) continue
            if(out.isNotEmpty()) {
                if(out.last().command!=Command.TRIM) out+=out.last().copy(command=Command.TRIM)
                if(!threads.last().sameThread(fragment.threads.single())) {
                    out+=out.last().copy(command=Command.COLOR_CHANGE);threads+=fragment.threads.single()
                }
            } else threads+=fragment.threads.single()
            val offset=out.size
            fragment.metadata[G.LOCK_INDICES_KEY].orEmpty().split(',').mapNotNull(String::toIntOrNull).forEach { locks+=offset+it }
            out+=body
        }
        out.lastOrNull()?.let { out+=it.copy(command=Command.END) }
        return EmbPattern(out,threads,baseline.metadata.filterKeys { !it.startsWith("myembro.inkstitch.") } +
            mapOf(G.FINISHED_KEY to "v1",G.SEWING_OPTIONS_KEY to SewingOptions().toJson(),G.LOCK_INDICES_KEY to locks.joinToString(",")),baseline.warnings)
    }
}
