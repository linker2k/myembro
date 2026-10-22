// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.engine.model.Stitch
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Editor model: a hoop holding positioned design objects. Immutable — every edit returns a new
 * [Project], which keeps undo/redo trivial. Units are 0.1 mm like the rest of the engine; the
 * hoop center is (0, 0).
 */
data class Project(
    val hoop: HoopSpec = HoopSpec.H100,
    val objects: List<DesignObject> = emptyList(),
) {
    fun find(id: String): DesignObject? = objects.firstOrNull { it.id == id }

    fun add(obj: DesignObject): Project = copy(objects = objects + obj)
    fun remove(id: String): Project = copy(objects = objects.filterNot { it.id == id })
    fun replace(obj: DesignObject): Project = copy(objects = objects.map { if (it.id == obj.id) obj else it })
    fun update(id: String, change: (DesignObject) -> DesignObject): Project =
        copy(objects = objects.map { if (it.id == id) change(it) else it })

    /** Moves [id] to [index] in sewing order (0 = sewn first). */
    fun reorder(id: String, index: Int): Project {
        val obj = find(id) ?: return this
        val rest = objects.filterNot { it.id == id }.toMutableList()
        rest.add(index.coerceIn(0, rest.size), obj)
        return copy(objects = rest)
    }

    /**
     * All visible objects sewn in order as one machine pattern: each object's stitches with its
     * transform applied, a trim + jump between objects, and a color change only where the
     * thread actually changes.
     */
    fun flatten(): EmbPattern {
        val stitches = ArrayList<Stitch>()
        val threads = ArrayList<EmbThread>()
        for (obj in objects.filter { it.visible }) {
            val placed = obj.placedPattern()
            placed.colorBlocks.forEach { block ->
                val thread = block.thread
                val sewn = (block.start until block.end).map { placed.stitches[it] }
                    .filter { it.command != Command.END && it.command != Command.COLOR_CHANGE }
                if (sewn.none { it.command == Command.STITCH }) return@forEach
                if (threads.isEmpty()) {
                    threads += thread
                } else {
                    val last = stitches.last()
                    stitches += Stitch(last.x, last.y, Command.TRIM)
                    if (!threads.last().sameThread(thread)) {
                        stitches += Stitch(last.x, last.y, Command.COLOR_CHANGE)
                        threads += thread
                    }
                }
                val first = sewn.first()
                stitches += Stitch(first.x, first.y, Command.JUMP)
                stitches += sewn
            }
        }
        val end = stitches.lastOrNull()
        stitches += Stitch(end?.x ?: 0, end?.y ?: 0, Command.END)
        return EmbPattern(stitches, threads)
    }
}

/** Hoop sewing area in 0.1 mm, centered on (0, 0). */
data class HoopSpec(val name: String, val width: Int, val height: Int) {
    companion object {
        val H100 = HoopSpec("100×100", 1000, 1000)
        val H130x180 = HoopSpec("130×180", 1300, 1800)
        val H160x260 = HoopSpec("160×260", 1600, 2600)
        val ALL = listOf(H100, H130x180, H160x260)
    }
}

/**
 * Placement of an object: scale and rotate about the object's own center (its local origin),
 * then translate. Negative scale mirrors.
 */
data class Transform(
    val x: Double = 0.0,
    val y: Double = 0.0,
    val rotationDeg: Double = 0.0,
    val scaleX: Double = 1.0,
    val scaleY: Double = 1.0,
) {
    fun apply(px: Double, py: Double): Pair<Double, Double> {
        val sx = px * scaleX
        val sy = py * scaleY
        val r = Math.toRadians(rotationDeg)
        return (sx * cos(r) - sy * sin(r) + x) to (sx * sin(r) + sy * cos(r) + y)
    }
}

/** Something placed in the hoop. Implementations keep their geometry centered on (0, 0). */
sealed interface DesignObject {
    val id: String
    val name: String
    val transform: Transform
    val visible: Boolean

    /** Stitches in local coordinates, centered on (0, 0). */
    fun localPattern(): EmbPattern

    fun withTransform(t: Transform): DesignObject
    fun withVisible(v: Boolean): DesignObject

    /** [localPattern] with [transform] applied, in hoop coordinates. */
    fun placedPattern(): EmbPattern {
        val local = localPattern()
        val placed = local.stitches.map { s ->
            val (px, py) = transform.apply(s.x.toDouble(), s.y.toDouble())
            Stitch(px.roundToInt(), py.roundToInt(), s.command)
        }
        return EmbPattern(placed, local.threads, local.metadata)
    }
}

/** An imported stitch file (PES/PEC/DST), re-centered on its bounding box. */
data class StitchObject(
    override val id: String,
    override val name: String,
    val source: EmbPattern,
    override val transform: Transform = Transform(),
    override val visible: Boolean = true,
) : DesignObject {
    private val centered: EmbPattern by lazy { recenter(source) }

    override fun localPattern(): EmbPattern = centered
    override fun withTransform(t: Transform) = copy(transform = t)
    override fun withVisible(v: Boolean) = copy(visible = v)

    companion object {
        fun recenter(p: EmbPattern): EmbPattern {
            val b = p.bounds ?: return p
            val cx = (b.minX + b.maxX) / 2
            val cy = (b.minY + b.maxY) / 2
            return EmbPattern(p.stitches.map { it.copy(x = it.x - cx, y = it.y - cy) }, p.threads, p.metadata)
        }
    }
}
