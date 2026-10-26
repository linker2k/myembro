// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor.block

import io.github.linker2k.myembro.engine.edit.ShapeObject
import kotlin.math.hypot

/**
 * Block editor state over a ShapeObject's regions: outlines for drawing and hit tests, the selection, and the
 * sheet's choices. Applying goes through the engine's SetBlockStitch (EditorViewModel.applyBlockStitch).
 */
enum class BlockType { FILL, SATIN, RUNNING }

/**
 * Fill textures as the PE-Design style menu shows them; PATTERN uses an Ink/Stitch meander tile.
 * Gradient is left out of this version: it needs color stops, and the engine rejects it.
 */
enum class BlockTexture { BASIC, PATTERN, CONTOUR, CIRCULAR }

/**
 * What a region allows, from the engine's BlockStitchCapabilities: each unavailable stitch type with the
 * engine's reason code, and the fill textures the engine implements.
 */
data class BlockOptions(val unavailable: Map<BlockType, String> = emptyMap(), val textures: Set<BlockTexture> = BlockTexture.entries.toSet()) {
    val types: Set<BlockType> get() = BlockType.entries.toSet() - unavailable.keys
    /** Options several selected regions share: a type any of them refuses is refused (first reason kept). */
    infix fun and(other: BlockOptions) = BlockOptions(other.unavailable + unavailable, textures intersect other.textures)
    fun allows(d: BlockStitchDraft) = d.type in types && (d.type != BlockType.FILL || d.texture in textures)
}

/** Three steps; only NORMAL has an Ink/Stitch basis so far (see BlockApply.ROW_SPACING_MM). */
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
 * A ShapeObject's regions in sewing order (first = sewn first, drawn below; last = on top) and the selection.
 * Settings change only through the engine command, so this state is rebuilt from each committed shape.
 */
class BlockEditState(val regions: List<BlockRegion>) {
    var selected: Set<String> = emptySet(); private set
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

    /** Keeps a selection across a rebuild from the committed shape (ids that still exist). */
    fun restore(ids: Set<String>) { selected = ids.filterTo(linkedSetOf()) { id -> regions.any { it.id == id } } }

    /** What every selected region allows; null while [byRegion] is still loading. */
    fun options(byRegion: Map<String, BlockOptions>?): BlockOptions? = byRegion?.let { map ->
        regions.filter { it.id in selected }.map { map[it.id] ?: BlockOptions() }.reduceOrNull(BlockOptions::and) ?: BlockOptions() }

    /** The draft the sheet opens with: the first selected region's stitch. */
    fun draft(): BlockStitchDraft = regions.firstOrNull { it.id in selected }?.stitch ?: BlockStitchDraft()

    companion object {
        /** The shape's actually emitted regions, in its sewing order, with their resolved stitch type. */
        fun of(shape: ShapeObject) = BlockEditState(shape.regions.map { r ->
            BlockRegion(r.id, r.paths.map { p -> RegionPath(DoubleArray(p.points.size) { p.points[it].x },
                DoubleArray(p.points.size) { p.points[it].y }, p.closed) }, r.color.color, BlockApply.draft(r.stitch))
        })
    }
}

private fun segmentDistance(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
    val dx = bx - ax; val dy = by - ay
    val len = dx * dx + dy * dy
    val t = if (len == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len).coerceIn(0.0, 1.0)
    return hypot(px - (ax + t * dx), py - (ay + t * dy))
}
