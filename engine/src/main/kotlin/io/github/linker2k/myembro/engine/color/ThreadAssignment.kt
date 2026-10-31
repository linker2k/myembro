// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.color

import io.github.linker2k.myembro.engine.io.MyThreads
import io.github.linker2k.myembro.engine.io.PecThreads
import io.github.linker2k.myembro.engine.model.EmbThread

/** One source RGB's painted area; repeated RGBs are combined before allocation. Alpha is ignored. */
data class DesignColor(val rgb: Int, val area: Double) {
    init { require(area.isFinite() && area >= 0) { "Color area must be finite and nonnegative" } }
}

/** User-approved greedy allocation, 2026-11-01. No stitch/geometry or hoop rules are changed. */
object ThreadAssignment {
    data class Assigned(val rgb: Int, val area: Double, val lightness: Double, val thread: EmbThread, val deltaE2000: Double)
    data class Shortage(val sourceColors: Int, val availableThreads: Int, val reusedColors: Int) {
        val message get() = "THREAD_CANDIDATES_EXHAUSTED: $sourceColors design colors exceed $availableThreads available threads; $reusedColors colors reuse a thread."
    }
    data class Result(val assignments: List<Assigned>, val warnings: List<Shortage>) {
        private val byRgb = assignments.associate { it.rgb to it.thread }
        fun threadFor(rgb: Int): EmbThread = byRgb.getValue(rgb and 0xffffff)
    }
    internal data class Prepared(val rgb: Int, val area: Double, val lab: ColorDifference.Lab)
    internal fun order(colors: List<Prepared>): List<Prepared> =
        colors.sortedWith(compareByDescending<Prepared> { it.lab.l }.thenByDescending { it.area })

    /** Stable candidate order breaks distance ties, like the existing PEC chart sort.
     * Grayscale callers may retain their existing neutral fallback; owned threads still take priority.
     */
    fun assign(colors: List<DesignColor>, myThreads: MyThreads = MyThreads(),
        fallback: List<EmbThread> = PecThreads.SET.filterNotNull()): Result {
        val areas = linkedMapOf<Int,Double>()
        for (c in colors) { val rgb=c.rgb and 0xffffff;areas[rgb]=(areas[rgb] ?: 0.0)+c.area }
        if (areas.isEmpty()) return Result(emptyList(),emptyList())
        val candidates=myThreads.candidates(fallback).distinctBy { it.color and 0xffffff }
        require(candidates.isNotEmpty()) { "No thread candidates available" }
        val labs=candidates.map { ColorDifference.lab(it.color) }
        val remaining=candidates.indices.toMutableList()
        val sorted=order(areas.map { (rgb,area)->Prepared(rgb,area,ColorDifference.lab(rgb)) })
        val result=sorted.map { color ->
            val choices=remaining.ifEmpty { candidates.indices.toList() }
            val index=choices.minBy { ColorDifference.ciede2000(color.lab,labs[it]) }
            remaining.remove(index)
            Assigned(color.rgb,color.area,color.lab.l,candidates[index],ColorDifference.ciede2000(color.lab,labs[index]))
        }
        val warnings=if(sorted.size>candidates.size) listOf(Shortage(sorted.size,candidates.size,sorted.size-candidates.size)) else emptyList()
        return Result(result,warnings)
    }
}
