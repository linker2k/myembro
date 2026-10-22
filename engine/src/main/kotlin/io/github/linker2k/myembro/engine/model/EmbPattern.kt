// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.model

/** Machine command attached to a needle position. */
enum class Command { STITCH, JUMP, TRIM, STOP, COLOR_CHANGE, END }

/**
 * One needle position in absolute coordinates.
 * Units are 0.1 mm (the native unit of PES/PEC and DST); +y points down.
 */
data class Stitch(val x: Int, val y: Int, val command: Command)

data class EmbThread(
    /** 0xAARRGGBB */
    val color: Int,
    val description: String? = null,
    val catalogNumber: String? = null,
    val brand: String? = null,
    val chart: String? = null,
) {
    val red: Int get() = (color shr 16) and 0xFF
    val green: Int get() = (color shr 8) and 0xFF
    val blue: Int get() = color and 0xFF
    val hex: String get() = "#%06x".format(color and 0xFFFFFF)

    /**
     * pyembroidery's thread equality: same RGB (alpha ignored) and same identifying metadata.
     * Two catalog threads that happen to share an RGB value are still different threads.
     */
    fun sameThread(other: EmbThread): Boolean =
        (color and 0xFFFFFF) == (other.color and 0xFFFFFF) &&
            description == other.description && catalogNumber == other.catalogNumber &&
            brand == other.brand && chart == other.chart
}

data class Bounds(val minX: Int, val minY: Int, val maxX: Int, val maxY: Int) {
    val width: Int get() = maxX - minX
    val height: Int get() = maxY - minY
}

/**
 * A run of stitches sewn with a single thread, delimited by COLOR_CHANGE (STOP pauses the
 * machine but keeps the thread). [start] inclusive, [end] exclusive.
 */
data class ColorBlock(val threadIndex: Int, val thread: EmbThread, val start: Int, val end: Int) {
    fun stitchCount(stitches: List<Stitch>): Int =
        (start until end).count { stitches[it].command == Command.STITCH }
}

class EmbPattern(
    val stitches: List<Stitch>,
    val threads: List<EmbThread>,
    val metadata: Map<String, String> = emptyMap(),
    /** Problems found while reading (e.g. truncated data); the pattern holds what was recovered. */
    val warnings: List<String> = emptyList(),
) {
    val stitchCount: Int by lazy { stitches.count { it.command == Command.STITCH } }
    val jumpCount: Int by lazy { stitches.count { it.command == Command.JUMP } }
    val trimCount: Int by lazy { stitches.count { it.command == Command.TRIM } }
    val colorChangeCount: Int by lazy { stitches.count { it.command == Command.COLOR_CHANGE } }
    val stopCount: Int by lazy { stitches.count { it.command == Command.STOP } }

    /** Extents of every needle-down position; null for an empty pattern. */
    val bounds: Bounds? by lazy {
        val sewn = stitches.filter { it.command == Command.STITCH }
        if (sewn.isEmpty()) null
        else Bounds(sewn.minOf { it.x }, sewn.minOf { it.y }, sewn.maxOf { it.x }, sewn.maxOf { it.y })
    }

    /** Total thread length on the top side, in 0.1 mm. Only STITCH→STITCH segments are sewn. */
    val threadLength: Double by lazy {
        var total = 0.0
        for (i in 1 until stitches.size) {
            val a = stitches[i - 1]
            val b = stitches[i]
            if (a.command != Command.STITCH || b.command != Command.STITCH) continue
            total += Math.hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble())
        }
        total
    }

    /** Color blocks that contain at least one stitch (empty blocks are hidden from the UI). */
    val colorBlocks: List<ColorBlock> by lazy {
        val blocks = mutableListOf<ColorBlock>()
        var start = 0
        var index = 0
        stitches.forEachIndexed { i, s ->
            if (s.command == Command.COLOR_CHANGE) {
                blocks += ColorBlock(index, threadAt(index), start, i + 1)
                start = i + 1
                index++
            }
        }
        if (start < stitches.size) blocks += ColorBlock(index, threadAt(index), start, stitches.size)
        blocks.filter { b -> (b.start until b.end).any { stitches[it].command == Command.STITCH } }
    }

    /** Thread for the given color block, falling back to a default palette when the file has none. */
    fun threadAt(index: Int): EmbThread =
        threads.getOrNull(index) ?: DEFAULT_PALETTE[index % DEFAULT_PALETTE.size]

    /**
     * Rough sewing time in seconds: stitches at [stitchesPerMinute], plus a fixed
     * cost per trim and per color change or stop (rethreading / operator action).
     */
    fun estimatedSeconds(
        stitchesPerMinute: Int = 650,
        secondsPerTrim: Int = 4,
        secondsPerColorChange: Int = 30,
    ): Int = stitchCount * 60 / stitchesPerMinute + trimCount * secondsPerTrim +
        (colorChangeCount + stopCount) * secondsPerColorChange

    companion object {
        val DEFAULT_PALETTE = listOf(
            EmbThread(0xFF0A55A3.toInt(), "Blue"),
            EmbThread(0xFFED171F.toInt(), "Red"),
            EmbThread(0xFF70BC1F.toInt(), "Lime Green"),
            EmbThread(0xFFFEBA35.toInt(), "Orange"),
            EmbThread(0xFF913697.toInt(), "Magenta"),
            EmbThread(0xFF000000.toInt(), "Black"),
            EmbThread(0xFF2584BB.toInt(), "Sky Blue"),
            EmbThread(0xFFB27624.toInt(), "Light Brown"),
        )
    }
}

/**
 * Mutable builder mirroring pyembroidery's relative-move API so readers can be ported
 * line by line.
 */
class PatternBuilder {
    private var stitches = ArrayList<Stitch>()
    val threads = ArrayList<EmbThread>()
    val metadata = LinkedHashMap<String, String>()
    val warnings = ArrayList<String>()
    private var x = 0
    private var y = 0

    fun add(command: Command, dx: Int = 0, dy: Int = 0) {
        x += dx
        y += dy
        stitches += Stitch(x, y, command)
    }

    fun stitch(dx: Int, dy: Int) = add(Command.STITCH, dx, dy)
    fun move(dx: Int, dy: Int) = add(Command.JUMP, dx, dy)
    fun trim() = add(Command.TRIM)
    fun colorChange(dx: Int = 0, dy: Int = 0) = add(Command.COLOR_CHANGE, dx, dy)
    fun end() = add(Command.END)

    /**
     * Port of pyembroidery `interpolate_trims`: formats like DST have no trim command, so a
     * run of [jumpsToRequireTrim] jumps after sewing is taken to mean "trim here".
     * Zero-length jump runs are dropped when [clipping] is set.
     *
     * Upstream inserts and deletes in place (quadratic on jump-heavy files); this streams into
     * a new list, where every insert or delete touches only the tail jump run.
     */
    fun interpolateTrims(jumpsToRequireTrim: Int?, distanceToRequireTrim: Int?, clipping: Boolean) {
        val out = ArrayList<Stitch>(stitches.size + 16)
        var px = 0
        var py = 0
        var jumpCount = 0
        var jumpStart = 0 // index in [out] of the first jump of the current run
        var jumpDx = 0
        var jumpDy = 0
        var jumping = false
        var trimmed = true
        for (s in stitches) {
            val dx = s.x - px
            val dy = s.y - py
            px = s.x
            py = s.y
            when (s.command) {
                Command.STITCH -> { trimmed = false; jumping = false }
                Command.COLOR_CHANGE, Command.TRIM -> { trimmed = true; jumping = false }
                else -> {}
            }
            out += s
            if (s.command != Command.JUMP) continue
            if (!jumping) {
                jumpDx = 0; jumpDy = 0; jumpCount = 0
                jumpStart = out.size - 1
                jumping = true
            }
            jumpCount++
            jumpDx += dx
            jumpDy += dy
            if (!trimmed) {
                val farEnough = distanceToRequireTrim != null &&
                    (Math.abs(jumpDy) > distanceToRequireTrim || Math.abs(jumpDx) > distanceToRequireTrim)
                if (jumpCount == jumpsToRequireTrim || farEnough) {
                    val prev = if (jumpStart == 0) Stitch(0, 0, Command.TRIM) else out[jumpStart - 1]
                    out.add(jumpStart, Stitch(prev.x, prev.y, Command.TRIM))
                    jumpStart++
                    trimmed = true
                }
            }
            if (clipping && jumpDx == 0 && jumpDy == 0) {
                out.subList(jumpStart, out.size).clear()
            }
        }
        stitches = out
    }

    /**
     * Port of pyembroidery `interpolate_duplicate_color_as_stop`: PES/PEC have no STOP command,
     * so writers encode a stop as a color change to the same color. Turn those back into STOPs
     * and drop the duplicate thread.
     */
    fun interpolateDuplicateColorAsStop() {
        var threadIndex = 0
        var initColor = true
        var lastChange = -1
        for (i in stitches.indices) {
            when (stitches[i].command) {
                Command.STITCH -> if (initColor) {
                    if (threadIndex >= threads.size) return
                    if (lastChange >= 0 && threadIndex != 0 &&
                        threads[threadIndex - 1].sameThread(threads[threadIndex])
                    ) {
                        threads.removeAt(threadIndex)
                        stitches[lastChange] = stitches[lastChange].copy(command = Command.STOP)
                    } else {
                        threadIndex++
                    }
                    initColor = false
                }
                Command.COLOR_CHANGE -> { initColor = true; lastChange = i }
                else -> {}
            }
        }
    }

    fun build(): EmbPattern = EmbPattern(stitches.toList(), threads.toList(), metadata.toMap(), warnings.toList())
}
