// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.engine.model.Stitch

/** Distances are in 0.1 mm, including travel from the hoop origin, with no return home. */
data class OrderSuggestion(
    val objectIds: List<String>,
    val travelDistanceBefore: Double,
    val travelDistanceAfter: Double,
) {
    val improved: Boolean get() = travelDistanceAfter < travelDistanceBefore
}

private data class ObjectRoute(
    val obj: DesignObject,
    val start: Stitch,
    val end: Stitch,
    val thread: EmbThread?,
)

private fun distance(a: Stitch, b: Stitch): Double =
    Math.hypot(b.x.toDouble() - a.x, b.y.toDouble() - a.y)

/**
 * Suggests a deterministic nearest-neighbor route inside contiguous same-thread object runs.
 * Whole objects move; their internal stitches/color blocks are never reversed or split.
 * Different thread identities (including catalog metadata), multicolor, hidden and empty
 * objects are barriers and stay in place. This can change layering, so application is opt-in.
 * The incoming and outgoing travel of each run are included in the comparison: a suggestion
 * never increases inter-object travel. This is a heuristic, not a global shortest-path solver.
 */
fun Project.suggestOrder(): OrderSuggestion {
    val routes = objects.map { obj ->
        val flat = Project(hoop, listOf(obj)).flatten()
        if (flat.stitchCount == 0) null else ObjectRoute(
            obj, flat.stitches.first(), flat.stitches[flat.stitches.lastIndex - 1],
            flat.threads.singleOrNull(),
        )
    }
    val proposed = routes.toMutableList()
    fun travel(items: List<ObjectRoute?>): Double {
        var last = Stitch(0, 0, Command.JUMP)
        var total = 0.0
        for (route in items.filterNotNull()) {
            total += distance(last, route.start)
            last = route.end
        }
        return total
    }
    fun cost(run: List<ObjectRoute>, previous: Stitch, next: Stitch?): Double {
        var last = previous
        var total = 0.0
        for (route in run) {
            total += distance(last, route.start)
            last = route.end
        }
        return total + (next?.let { distance(last, it) } ?: 0.0)
    }
    var index = 0
    var previous = Stitch(0, 0, Command.JUMP)
    while (index < proposed.size) {
        val first = proposed[index]
        if (first?.thread == null) {
            if (first != null) previous = first.end
            index++
            continue
        }
        var end = index + 1
        while (end < proposed.size && proposed[end]?.thread?.let { first.thread.sameThread(it) } == true) end++
        val original = proposed.subList(index, end).filterNotNull()
        val remaining = original.toMutableList()
        val candidate = ArrayList<ObjectRoute>(remaining.size)
        var last = previous
        while (remaining.isNotEmpty()) {
            // minBy preserves input order on ties, keeping the result deterministic.
            val nearest = remaining.minBy { distance(last, it.start) }
            candidate += nearest
            remaining.remove(nearest)
            last = nearest.end
        }
        var nextIndex = end
        while (nextIndex < proposed.size && proposed[nextIndex] == null) nextIndex++
        val next = proposed.getOrNull(nextIndex)?.start
        if (cost(candidate, previous, next) + 1e-9 < cost(original, previous, next)) {
            candidate.forEachIndexed { offset, route -> proposed[index + offset] = route }
        }
        previous = proposed[end - 1]!!.end
        index = end
    }
    val before = travel(routes)
    val after = travel(proposed)
    return OrderSuggestion(
        objects.indices.map { proposed[it]?.obj?.id ?: objects[it].id }, before, after,
    )
}

/** Returns a new sewing order only when it improves travel. [flatten] itself never reorders. */
fun Project.optimizeOrder(preserveOriginalOrder: Boolean = false): Project {
    if (preserveOriginalOrder) return this
    val suggestion = suggestOrder()
    if (!suggestion.improved) return this
    val byId = objects.associateBy { it.id }
    require(byId.size == objects.size) { "Cannot optimize duplicate object ids" }
    return copy(objects = suggestion.objectIds.map { byId.getValue(it) })
}
