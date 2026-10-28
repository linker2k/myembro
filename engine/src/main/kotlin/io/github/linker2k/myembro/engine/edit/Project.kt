// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

import io.github.linker2k.myembro.engine.io.PatternReader
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.SewingOptions
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.ProcessingSummary
import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.engine.model.Stitch
import java.util.UUID
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
    /** Null preserves each preview's legacy theme background. Display only, never sewing geometry. */
    val fabricColorArgb: Int? = null,
    /** Final palette substitutions; geometry and color-change commands remain untouched. */
    val threadColors: List<ProjectThreadColor> = emptyList(),
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

    fun duplicate(id: String, newId: String = UUID.randomUUID().toString()): Project {
        val original = find(id) ?: return this
        require(find(newId) == null) { "Duplicate object id: $newId" }
        val index = objects.indexOf(original)
        return copy(objects = objects.toMutableList().apply { add(index + 1, original.withId(newId)) })
    }

    fun mirrorX(id: String): Project = update(id) { it.mirrorX() }
    fun mirrorY(id: String): Project = update(id) { it.mirrorY() }
    fun rotateBy(id: String, degrees: Double): Project = update(id) { it.rotateBy(degrees) }
    fun scaleBy(id: String, factorX: Double, factorY: Double = factorX): Project =
        update(id) { it.scaleBy(factorX, factorY) }

    /** Centers the placed geometry, including asymmetric/rounded stitch coordinates. */
    fun alignCenter(id: String): Project = update(id) { obj ->
        val b = obj.placedBounds() ?: return@update obj
        obj.withTransform(obj.transform.copy(
            x = obj.transform.x - (b.minX.toDouble() + b.maxX) / 2,
            y = obj.transform.y - (b.minY.toDouble() + b.maxY) / 2,
        ))
    }

    fun placedBounds(id: String): io.github.linker2k.myembro.engine.model.Bounds? = find(id)?.placedBounds()
    fun placedBounds(): io.github.linker2k.myembro.engine.model.Bounds? =
        objects.filter { it.visible }.mapNotNull { it.placedBounds() }.unionBounds()

    val densityWarnings: Set<String> get() = objects.filter { it.densityWarning }.map { it.id }.toSet()

    /**
     * All visible objects sewn in order as one machine pattern: each object's stitches with its
     * transform applied, a trim + jump between objects, and a color change only where the
     * thread actually changes.
     */
    fun flatten(sewingOptions: SewingOptions? = null): EmbPattern {
        val resolvedSewingOptions = sewingOptions ?: SewingOptions()
        val stitches = ArrayList<Stitch>()
        val threads = ArrayList<EmbThread>()
        var summary: ProcessingSummary? = null
        for (obj in objects.filter { it.visible }) {
            // Generated objects have already been finished. Imported sewing remains verbatim;
            // neither kind may be locked again merely because objects are being composed.
            val source = obj.placedPattern()
            val generated = source.metadata[GeneratedPatternProcessor.FINISHED_KEY] == "v1"
            val raw = if (generated) GeneratedPatternProcessor.finish(source, options = resolvedSewingOptions) else source
            ProcessingSummary.fromPattern(raw)?.let { summary = (summary ?: ProcessingSummary()) + it }
            // END terminates the source, even if malformed input has trailing stitches.
            val placed = EmbPattern(raw.stitches.takeWhile { it.command != Command.END }, raw.threads)
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
        val metadata = summary?.let { it.metadata() + (ProcessingSummary.COMPOSITE_KEY to "v1") }.orEmpty()
        val appearanceMetadata = fabricColorArgb?.let { mapOf(FABRIC_COLOR_KEY to it.toString()) }.orEmpty()
        return applyThreadColors(EmbPattern(stitches, threads, metadata + appearanceMetadata))
    }
}

/** Hoop sewing area in 0.1 mm, centered on (0, 0). */
data class HoopSpec(
    val name: String,
    val width: Int,
    val height: Int,
    /** Per-position rectangles in the frame's centered 0.1 mm coordinates. Empty = no verified layout supplied.
     * Coverage dimensions alone do not define continuous sewing area. No splitting is performed here.
     */
    val positions: List<io.github.linker2k.myembro.engine.model.Bounds> = emptyList(),
) {
    init { require(width > 0 && height > 0) { "Hoop dimensions must be positive" } }
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
    init {
        require(listOf(x, y, rotationDeg, scaleX, scaleY).all { it.isFinite() }) { "Transform must be finite" }
        require(scaleX != 0.0 && scaleY != 0.0) { "Scale must be nonzero" }
    }

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

    /** Implementations should override this to retain editable object data when duplicating. */
    fun withId(id: String): DesignObject = error("Duplication is unsupported for ${this::class.simpleName}")

    fun withTransform(t: Transform): DesignObject
    fun withVisible(v: Boolean): DesignObject

    /** [localPattern] with [transform] applied, in hoop coordinates. */
    fun placedPattern(): EmbPattern = placeDesignPattern(this)

}

/** An imported stitch file (PES/PEC/DST), re-centered on its bounding box. */
data class StitchObject(
    override val id: String,
    override val name: String,
    val source: EmbPattern,
    override val transform: Transform = Transform(),
    override val visible: Boolean = true,
    /** Optional original PES/PEC/DST bytes, retained verbatim by .myembro serialization. */
    val sourceBytes: ByteArray? = null,
    val sourceFileName: String? = null,
    /** Explicit user recoloring must survive reader migrations and library source resolution. */
    val sourceThreadsEdited: Boolean = false,
) : DesignObject {
    private val centered: EmbPattern by lazy { recenter(source) }

    override fun localPattern(): EmbPattern = centered
    override fun withId(id: String) = copy(id = id)
    override fun withTransform(t: Transform) = copy(transform = t)
    override fun withVisible(v: Boolean) = copy(visible = v)

    companion object {
        /** Import without losing the original bytes when saving the editable project. */
        fun fromBytes(id: String, name: String, bytes: ByteArray, fileName: String): StitchObject =
            StitchObject(id, name, PatternReader.read(bytes, fileName), sourceBytes = bytes.copyOf(), sourceFileName = fileName)

        fun recenter(p: EmbPattern): EmbPattern {
            val b = p.bounds ?: return p
            val (cx, cy) = integerHoopCenter(b)
            return GeneratedPatternProcessor.mapCoordinates(p) { x, y -> x - cx to y - cy }
        }
    }
}

// Shared original placement implementation; text caches its scale-only result separately.
internal fun placeDesignPattern(obj: DesignObject): EmbPattern = with(obj) {
        val source = localPattern()
        val generated = source.metadata.containsKey(GeneratedPatternProcessor.FINISHED_KEY)
        val gridPreserving = kotlin.math.abs(transform.scaleX) == 1.0 && kotlin.math.abs(transform.scaleY) == 1.0 &&
            transform.rotationDeg % 90.0 == 0.0 && transform.x % 1.0 == 0.0 && transform.y % 1.0 == 0.0
        if (generated && gridPreserving) return@with GeneratedPatternProcessor.mapCoordinates(source) { x, y ->
            val (px, py) = transform.apply(x.toDouble(), y.toDouble())
            px.roundToInt() to py.roundToInt()
        }
        val local = if (generated) GeneratedPatternProcessor.stripGeneratedLocks(source) else source
        val placed = local.stitches.map { s ->
            val (px, py) = transform.apply(s.x.toDouble(), s.y.toDouble())
            Stitch(px.roundToInt(), py.roundToInt(), s.command)
        }
        val output = EmbPattern(placed, local.threads, local.metadata, local.warnings)
        if (generated) GeneratedPatternProcessor.finish(output) else output
    }
