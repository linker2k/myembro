// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.sewout

import kotlin.math.*

/** Needle targets and thread passages are separate: crossing a cell is not a needle puncture. */
internal class DensityGrid(private val options: SewoutOptions, private val offsetX: Double = 0.0, private val offsetY: Double = 0.0) {
    private val size = options.gridMm * 10
    private data class Cell(var needles: Int = 0, var passes: Int = 0, var length: Double = 0.0,
        val stages: MutableSet<Int> = linkedSetOf(), val first: Int)
    private val cells = linkedMapOf<Pair<Long, Long>, Cell>()
    private var traced = 0
    var complete = true
        private set
    private fun cell(x: Long, y: Long, index: Int): Cell? {
        val key = x to y
        return cells[key] ?: if (cells.size < options.maxGridCells) Cell(first = index).also { cells[key] = it }
            else { complete = false; null }
    }
    fun needle(p: NeedlePoint, stage: Int, index: Int) {
        cell(floor((p.x-offsetX)/size).toLong(), floor((p.y-offsetY)/size).toLong(), index)?.let { it.needles++; it.stages += stage }
    }
    /** Exact parametric grid traversal; diagonal corner hits do not create spurious side cells. */
    fun segment(a: NeedlePoint, b: NeedlePoint, stage: Int, index: Int) {
        if (traced >= options.maxTracedCells) { complete = false; return }
        val dx = b.x-a.x; val dy = b.y-a.y
        val length = hypot(dx, dy)
        if (length == 0.0) return
        var x = floor(a.x/size).toLong(); var y = floor(a.y/size).toLong()
        val stepX = if (dx > 0) 1 else -1; val stepY = if (dy > 0) 1 else -1
        var nextX = if (dx == 0.0) Double.POSITIVE_INFINITY else ((x + if (dx > 0) 1 else 0)*size-a.x)/dx
        var nextY = if (dy == 0.0) Double.POSITIVE_INFINITY else ((y + if (dy > 0) 1 else 0)*size-a.y)/dy
        val deltaX = if (dx == 0.0) Double.POSITIVE_INFINITY else size/abs(dx)
        val deltaY = if (dy == 0.0) Double.POSITIVE_INFINITY else size/abs(dy)
        var t = 0.0
        while (t < 1) {
            if (++traced > options.maxTracedCells) { complete = false; return }
            val end = min(1.0, min(nextX, nextY)).coerceAtLeast(t)
            if (end-t > 1e-12) cell(x, y, index)?.let {
                it.passes++; it.length += length*(end-t)/10; it.stages += stage
            }
            if (end >= 1.0) break
            val advanceX = nextX <= nextY
            val advanceY = nextY <= nextX
            if (advanceX) { x += stepX; nextX += deltaX }
            if (advanceY) { y += stepY; nextY += deltaY }
            t = end
        }
    }
    fun result(): List<DensityCell> = cells.map { (key, cell) -> DensityCell(key.first, key.second,
        CheckArea(key.first*size+offsetX, key.second*size+offsetY, (key.first+1)*size+offsetX, (key.second+1)*size+offsetY),
        cell.needles, cell.passes, cell.length, cell.stages.toSet(), cell.first) }
}
