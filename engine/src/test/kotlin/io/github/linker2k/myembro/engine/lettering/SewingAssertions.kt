// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import io.github.linker2k.myembro.engine.model.*
import kotlin.math.hypot
import kotlin.test.*

internal fun bodyStitches(pattern: EmbPattern): List<Stitch> {
    val locks = pattern.metadata[io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor.LOCK_INDICES_KEY].orEmpty().split(',').mapNotNull(String::toIntOrNull).toSet()
    return pattern.stitches.filterIndexed { i, s -> i !in locks && s.command == Command.STITCH }
}
internal fun assertSewingLengths(pattern: EmbPattern, maximumMm: Double = 7.0) {
    pattern.stitches.zipWithNext().forEach { (a,b) -> if (a.command == Command.STITCH && b.command == Command.STITCH) {
        val d = hypot((b.x-a.x).toDouble(), (b.y-a.y).toDouble()) / 10
        assertTrue(d >= 0.1 - 1e-9 && d <= maxOf(maximumMm,0.5)+1e-9,"$a -> $b: $d")
    } }
}
