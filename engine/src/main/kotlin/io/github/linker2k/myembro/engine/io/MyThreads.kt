// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.io

import io.github.linker2k.myembro.engine.model.EmbThread

/** Ownership is indexed by the existing Brother PEC chart; distance stays PecThreads.distance. */
data class MyThreads(val enabled: Boolean = false, val indices: Set<Int> = emptySet()) {
    val owned: List<EmbThread> get() = (1 until PecThreads.SET.size).filter { it in indices }.map(PecThreads::forIndex)
    val active: Boolean get() = enabled && owned.isNotEmpty()
    fun candidates(fallback: List<EmbThread> = PecThreads.SET.filterNotNull()): List<EmbThread> =
        if (active) owned else fallback
    fun nearest(rgb: Int): EmbThread = ranked(rgb).first().first
    fun ranked(rgb: Int, count: Int = 64): List<Pair<EmbThread, Int>> = candidates()
        .map { it to PecThreads.distance(it.color, rgb or (0xFF shl 24)) }.sortedBy { it.second }.take(count)
    fun missing(thread: EmbThread): Boolean = owned.isNotEmpty() && owned.none { it.sameColor(thread) }
}
