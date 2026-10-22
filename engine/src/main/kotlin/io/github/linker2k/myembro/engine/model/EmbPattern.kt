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
}

data class Bounds(val minX: Int, val minY: Int, val maxX: Int, val maxY: Int) {
    val width: Int get() = maxX - minX
    val height: Int get() = maxY - minY
}

/** A run of stitches sewn with a single thread. [start] inclusive, [end] exclusive. */
data class ColorBlock(val threadIndex: Int, val thread: EmbThread, val start: Int, val end: Int) {
    fun stitchCount(stitches: List<Stitch>): Int =
        (start until end).count { stitches[it].command == Command.STITCH }
}

class EmbPattern(
    val stitches: List<Stitch>,
    val threads: List<EmbThread>,
    val metadata: Map<String, String> = emptyMap(),
) {
    val stitchCount: Int by lazy { stitches.count { it.command == Command.STITCH } }
    val jumpCount: Int by lazy { stitches.count { it.command == Command.JUMP } }
    val trimCount: Int by lazy { stitches.count { it.command == Command.TRIM } }
    val colorChangeCount: Int by lazy {
        stitches.count { it.command == Command.COLOR_CHANGE || it.command == Command.STOP }
    }

    /** Extents of every needle-down position; null for an empty pattern. */
    val bounds: Bounds? by lazy {
        val sewn = stitches.filter { it.command == Command.STITCH }
        if (sewn.isEmpty()) null
        else Bounds(sewn.minOf { it.x }, sewn.minOf { it.y }, sewn.maxOf { it.x }, sewn.maxOf { it.y })
    }

    /** Total thread length on the top side, in 0.1 mm. */
    val threadLength: Double by lazy {
        var total = 0.0
        for (i in 1 until stitches.size) {
            if (stitches[i].command != Command.STITCH) continue
            val a = stitches[i - 1]
            val b = stitches[i]
            total += Math.hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble())
        }
        total
    }

    val colorBlocks: List<ColorBlock> by lazy {
        val blocks = mutableListOf<ColorBlock>()
        var start = 0
        var index = 0
        stitches.forEachIndexed { i, s ->
            if (s.command == Command.COLOR_CHANGE || s.command == Command.STOP) {
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
     * cost per trim and per color change (rethreading).
     */
    fun estimatedSeconds(
        stitchesPerMinute: Int = 650,
        secondsPerTrim: Int = 4,
        secondsPerColorChange: Int = 30,
    ): Int = stitchCount * 60 / stitchesPerMinute + trimCount * secondsPerTrim +
        colorChangeCount * secondsPerColorChange

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
    private val stitches = ArrayList<Stitch>()
    val threads = ArrayList<EmbThread>()
    val metadata = LinkedHashMap<String, String>()
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
     */
    fun interpolateTrims(jumpsToRequireTrim: Int?, distanceToRequireTrim: Int?, clipping: Boolean) {
        var i = -1
        var ie = stitches.size - 1
        var px = 0
        var py = 0
        var jumpCount = 0
        var jumpStart = 0
        var jumpDx = 0
        var jumpDy = 0
        var jumping = false
        var trimmed = true
        while (i < ie) {
            i++
            val s = stitches[i]
            val dx = s.x - px
            val dy = s.y - py
            px = s.x
            py = s.y
            when (s.command) {
                Command.STITCH -> { trimmed = false; jumping = false }
                Command.COLOR_CHANGE, Command.TRIM -> { trimmed = true; jumping = false }
                else -> {}
            }
            if (s.command != Command.JUMP) continue
            if (!jumping) {
                jumpDx = 0; jumpDy = 0; jumpCount = 0
                jumpStart = i
                jumping = true
            }
            jumpCount++
            jumpDx += dx
            jumpDy += dy
            if (!trimmed) {
                val farEnough = distanceToRequireTrim != null &&
                    (Math.abs(jumpDy) > distanceToRequireTrim || Math.abs(jumpDx) > distanceToRequireTrim)
                if (jumpCount == jumpsToRequireTrim || farEnough) {
                    val prev = if (jumpStart == 0) Stitch(0, 0, Command.TRIM) else stitches[jumpStart - 1]
                    stitches.add(jumpStart, Stitch(prev.x, prev.y, Command.TRIM))
                    jumpStart++
                    i++
                    ie++
                    trimmed = true
                }
            }
            if (clipping && jumpDx == 0 && jumpDy == 0) {
                stitches.subList(jumpStart, i + 1).clear()
                i = jumpStart - 1
                ie = stitches.size - 1
            }
        }
    }

    fun build(): EmbPattern = EmbPattern(stitches.toList(), threads.toList(), metadata.toMap())
}
