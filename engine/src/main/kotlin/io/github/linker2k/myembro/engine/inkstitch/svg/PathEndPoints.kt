// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch lib/stitch_plan/lock_stitch.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.svg

import io.github.linker2k.myembro.engine.inkstitch.utils.Point

/** Endpoint-only SVG path interpretation, replacing the source's inkex Path.end_points.
 * Bézier control points and arc interiors are intentionally not sampled for lock stitches. */
object PathEndPoints {
    private val token = Regex("[a-zA-Z]|[-+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?")
    fun parse(path: String): List<Point> {
        val matches = token.findAll(path).toList()
        var offset = 0
        for (match in matches) {
            require(path.substring(offset, match.range.first).all { it.isWhitespace() || it == ',' }) { "Invalid SVG lock path" }
            offset = match.range.last + 1
        }
        require(path.substring(offset).all { it.isWhitespace() || it == ',' }) { "Invalid SVG lock path" }
        val values = matches.map { it.value }.toMutableList()
        val result = mutableListOf<Point>()
        var index = 0
        var command: Char? = null
        var current = Point(0.0, 0.0)
        var subpath = current
        while (index < values.size) {
            if (values[index].length == 1 && values[index][0].isLetter()) {
                command = values[index++][0]
                if (command.uppercaseChar() == 'Z') {
                    current = subpath
                    result += current
                    command = null
                    continue
                }
            }
            val operation = requireNotNull(command) { "SVG lock path has no command" }
            val upper = operation.uppercaseChar()
            val count = when (upper) {
                'M', 'L', 'T' -> 2
                'H', 'V' -> 1
                'S', 'Q' -> 4
                'C' -> 6
                'A' -> 7
                else -> throw IllegalArgumentException("Unsupported SVG lock command: $operation")
            }
            val args = DoubleArray(count) { n ->
                require(index < values.size) { "Incomplete SVG lock command: $operation" }
                if (upper == 'A' && n in 3..4) {
                    // SVG arc flags need no separators: A1 1 0 011 2 means flags 0,1 then x=1.
                    val value = values[index]
                    require(value.first() == '0' || value.first() == '1') { "Invalid SVG arc flag" }
                    if (value.length == 1) index++ else values[index] = value.substring(1)
                    value.first().digitToInt().toDouble()
                } else {
                    values[index++].toDoubleOrNull()?.takeIf { it.isFinite() }
                        ?: throw IllegalArgumentException("Invalid SVG lock coordinate")
                }
            }
            val relative = operation.isLowerCase()
            val x = when (upper) {
                'V' -> current.x
                'H' -> args[0] + if (relative) current.x else 0.0
                else -> args[count - 2] + if (relative) current.x else 0.0
            }
            val y = when (upper) {
                'H' -> current.y
                'V' -> args[0] + if (relative) current.y else 0.0
                else -> args[count - 1] + if (relative) current.y else 0.0
            }
            current = Point(x, y)
            result += current
            if (upper == 'M') {
                subpath = current
                command = if (relative) 'l' else 'L'
            }
        }
        return result
    }
}
