// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor.block

/**
 * UI draft only: a stand-in for the engine's ShapeObject regions (agent/block-model), so the block editor can
 * be laid out before the engine exists. No stitches come from here; replace with ShapeObject when it lands.
 */
enum class BlockType { FILL, SATIN, RUNNING }

/** Fill textures as the PE-Design style menu shows them; PATTERN uses a meander tile. */
enum class BlockTexture { BASIC, PATTERN, CONTOUR, CIRCULAR, GRADIENT }

/** Three steps only; the mm spacing for each is unverified and is set from Ink/Stitch ranges at engine hookup. */
enum class BlockDensity { DENSE, NORMAL, SPARSE }

data class BlockStitchDraft(
    val type: BlockType = BlockType.FILL,
    val texture: BlockTexture = BlockTexture.BASIC,
    val tile: Int = 0,
    val autoAngle: Boolean = true,
    val angleDeg: Int = 0,
    val density: BlockDensity = BlockDensity.NORMAL,
) {
    init { require(angleDeg in 0..179) { "angleDeg $angleDeg outside 0..179" } }
}

/** One region: a placeholder outline in unit coordinates (0..1 of the object box), a thread color and its stitch. */
data class FakeRegion(val id: Int, val shape: FakeShape, val color: Long, val stitch: BlockStitchDraft = BlockStitchDraft())

sealed interface FakeShape {
    data class Ellipse(val cx: Float, val cy: Float, val rx: Float, val ry: Float) : FakeShape
    data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) : FakeShape
    fun contains(x: Float, y: Float): Boolean = when (this) {
        is Ellipse -> ((x - cx) / rx).let { it * it } + ((y - cy) / ry).let { it * it } <= 1f
        is Rect -> x in left..right && y in top..bottom
    }
}

/**
 * Regions in sewing order (first = sewn first, drawn below), the selection, and an undo history where every
 * apply or reorder is exactly one step.
 */
class BlockEditState(regions: List<FakeRegion>) {
    var regions: List<FakeRegion> = regions; private set
    var selected: Set<Int> = emptySet(); private set
    private val undo = ArrayDeque<List<FakeRegion>>()
    private val redo = ArrayDeque<List<FakeRegion>>()
    val undoSteps get() = undo.size
    val canRedo get() = redo.isNotEmpty()

    /** Topmost region under (x, y) in unit coordinates; [multi] adds or removes it instead of replacing. */
    fun tap(x: Float, y: Float, multi: Boolean) {
        val hit = regions.lastOrNull { it.shape.contains(x, y) }?.id
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

    private fun commit(next: List<FakeRegion>) {
        if (next == regions) return
        undo.addLast(regions); redo.clear(); regions = next
    }

    companion object {
        /** A synthetic flower-like object: petals, a center and a stem rectangle. */
        fun sample() = BlockEditState(listOf(
            FakeRegion(1, FakeShape.Rect(0.46f, 0.55f, 0.54f, 0.98f), 0xFF3E8E41),
            FakeRegion(2, FakeShape.Ellipse(0.30f, 0.35f, 0.20f, 0.16f), 0xFFE57373),
            FakeRegion(3, FakeShape.Ellipse(0.70f, 0.35f, 0.20f, 0.16f), 0xFFE57373),
            FakeRegion(4, FakeShape.Ellipse(0.50f, 0.17f, 0.16f, 0.15f), 0xFFF06292),
            FakeRegion(5, FakeShape.Ellipse(0.50f, 0.48f, 0.16f, 0.13f), 0xFFF06292),
            FakeRegion(6, FakeShape.Ellipse(0.50f, 0.33f, 0.10f, 0.10f), 0xFFFFC107, BlockStitchDraft(type = BlockType.SATIN)),
        ))
    }
}
