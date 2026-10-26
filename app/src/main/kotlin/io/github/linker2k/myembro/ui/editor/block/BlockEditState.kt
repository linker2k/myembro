// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor.block

import io.github.linker2k.myembro.engine.edit.BlockStitch
import io.github.linker2k.myembro.engine.edit.ShapeObject
import kotlin.math.hypot

/**
 * Block editor state over a ShapeObject's regions. The engine's regions are read-only in this draft, so the
 * chosen settings live here until embro-model's region command exists; nothing here changes stitches yet.
 */
enum class BlockType { FILL, SATIN, RUNNING }

/** Fill textures as the PE-Design style menu shows them; PATTERN uses an Ink/Stitch meander tile. */
enum class BlockTexture { BASIC, PATTERN, CONTOUR, CIRCULAR, GRADIENT }

/** Three steps only; the mm spacing for each is unverified and is set from Ink/Stitch ranges at engine hookup. */
enum class BlockDensity { DENSE, NORMAL, SPARSE }

data class BlockStitchDraft(
    val type: BlockType = BlockType.FILL,
    val texture: BlockTexture = BlockTexture.BASIC,
    /** Ink/Stitch meander_pattern id; the upstream default tile. */
    val tile: String = "N3-11a",
    val autoAngle: Boolean = true,
    val angleDeg: Int = 0,
    val density: BlockDensity = BlockDensity.NORMAL,
) {
    init { require(angleDeg in 0..179) { "angleDeg $angleDeg outside 0..179" } }
}

/** One ring or line in centered object-local millimeters (+y down). */
class RegionPath(val xs: DoubleArray, val ys: DoubleArray, val closed: Boolean)

/** One region in sewing order: its outline (holes as extra closed rings), thread color and chosen stitch. */
data class BlockRegion(val id: String, val paths: List<RegionPath>, val color: Int, val stitch: BlockStitchDraft) {
    /** Even-odd inside test over all closed rings (holes excluded); lines and satin edges hit within [lineMm]. */
    fun hit(x: Double, y: Double, lineMm: Double): Boolean {
        var inside = false
        for (p in paths) {
            val n = p.xs.size
            if (p.closed && n >= 3) {
                var j = n - 1
                for (i in 0 until n) {
                    if ((p.ys[i] > y) != (p.ys[j] > y) &&
                        x < (p.xs[j] - p.xs[i]) * (y - p.ys[i]) / (p.ys[j] - p.ys[i]) + p.xs[i]) inside = !inside
                    j = i
                }
            } else for (i in 1 until n) if (segmentDistance(x, y, p.xs[i - 1], p.ys[i - 1], p.xs[i], p.ys[i]) <= lineMm) return true
        }
        return inside
    }
}

/** Object-local bounds of every region path, mm. */
data class RegionBounds(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double)

/**
 * Regions in sewing order (first = sewn first, drawn below; last = on top), the selection, and an undo
 * history where every apply or reorder is exactly one step.
 */
class BlockEditState(regions: List<BlockRegion>) {
    var regions: List<BlockRegion> = regions; private set
    var selected: Set<String> = emptySet(); private set
    private val undo = ArrayDeque<List<BlockRegion>>()
    private val redo = ArrayDeque<List<BlockRegion>>()
    val undoSteps get() = undo.size
    val canRedo get() = redo.isNotEmpty()
    val bounds: RegionBounds? = regions.flatMap { it.paths }.filter { it.xs.isNotEmpty() }.takeIf { it.isNotEmpty() }?.let { ps ->
        RegionBounds(ps.minOf { it.xs.min() }, ps.minOf { it.ys.min() }, ps.maxOf { it.xs.max() }, ps.maxOf { it.ys.max() })
    }

    /** Topmost region at (x, y) mm; [multi] adds or removes it instead of replacing. */
    fun tap(x: Double, y: Double, multi: Boolean, lineMm: Double = 1.0) {
        val hit = regions.lastOrNull { it.hit(x, y, lineMm) }?.id
        selected = when {
            hit == null -> if (multi) selected else emptySet()
            multi -> if (hit in selected) selected - hit else selected + hit
            else -> setOf(hit)
        }
    }
    fun selectAll() { selected = regions.map { it.id }.toSet() }
    fun clearSelection() { selected = emptySet() }

    /** The draft the sheet opens with: the first selected region's stitch. */
    fun draft(): BlockStitchDraft = regions.firstOrNull { it.id in selected }?.stitch ?: BlockStitchDraft()

    /** Writes [draft] to every selected region (same angle for all) as one undo step. */
    fun apply(draft: BlockStitchDraft) {
        if (selected.isEmpty()) return
        commit(regions.map { if (it.id in selected) it.copy(stitch = draft) else it })
    }

    /** Moves the selected regions one place later (front) or earlier (back) in sewing order, as one step. */
    fun reorder(forward: Boolean) {
        val list = regions.toMutableList()
        val indices = list.indices.filter { list[it].id in selected }.let { if (forward) it.reversed() else it }
        var moved = false
        for (i in indices) {
            val j = if (forward) i + 1 else i - 1
            if (j !in list.indices || list[j].id in selected) continue
            list[i] = list[j].also { list[j] = list[i] }; moved = true
        }
        if (moved) commit(list)
    }

    fun undo() { undo.removeLastOrNull()?.let { redo.addLast(regions); regions = it } }
    fun redo() { redo.removeLastOrNull()?.let { undo.addLast(regions); regions = it } }

    private fun commit(next: List<BlockRegion>) {
        if (next == regions) return
        undo.addLast(regions); redo.clear(); regions = next
    }

    companion object {
        /** The shape's actually emitted regions, in its sewing order, with their resolved stitch type. */
        fun of(shape: ShapeObject) = BlockEditState(shape.regions.map { r ->
            val type = when (r.stitch.type) {
                BlockStitch.Type.FILL -> BlockType.FILL
                BlockStitch.Type.SATIN -> BlockType.SATIN
                BlockStitch.Type.RUNNING -> BlockType.RUNNING
            }
            BlockRegion(r.id, r.paths.map { p -> RegionPath(DoubleArray(p.points.size) { p.points[it].x },
                DoubleArray(p.points.size) { p.points[it].y }, p.closed) }, r.color.color, BlockStitchDraft(type = type))
        })
    }
}

private fun segmentDistance(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
    val dx = bx - ax; val dy = by - ay
    val len = dx * dx + dy * dy
    val t = if (len == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len).coerceIn(0.0, 1.0)
    return hypot(px - (ax + t * dx), py - (ay + t * dy))
}
