// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.sewout

import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.model.Stitch
import kotlin.math.hypot

/** PES carries coordinates, not reliable tie-on semantics. This is a conservative geometry hint. */
internal fun checkStartLocks(stitches: List<Stitch>, findings: Findings) {
    var needsLock = true
    var reason = "도안/객체 시작"
    for ((index, stitch) in stitches.withIndex()) {
        when (stitch.command) {
            Command.TRIM -> { needsLock = true; reason = "트림 뒤 재시작" }
            Command.COLOR_CHANGE -> { needsLock = true; reason = "색 교체 뒤 재시작" }
            Command.STITCH -> if (needsLock) {
                // Inspect at most six consecutive needle commands, without looking through travel/cuts.
                val start = stitches.subList(index, minOf(index + 6, stitches.size))
                    .takeWhile { it.command == Command.STITCH }
                if (!hasStartLock(start)) {
                    val last = start.last()
                    findings.add(FindingCode.MISSING_LOCK_STITCH, Severity.WARNING,
                        "$reason: 잠금 땀을 확인하지 못했습니다. 시작부의 짧은 왕복/삼각 고정을 확인하세요.",
                        Highlight(index, index + start.lastIndex,
                            NeedlePoint(stitch.x.toDouble(), stitch.y.toDouble()),
                            NeedlePoint(last.x.toDouble(), last.y.toDouble())))
                }
                needsLock = false
            }
            Command.END -> return
            else -> Unit // Travel before the first needle must not consume a pending start/trim check.
        }
    }
}

private fun hasStartLock(stitches: List<Stitch>): Boolean {
    // Zero-length anchors do not constitute a lock. Repeated punctures are checked separately.
    val points = mutableListOf<Stitch>()
    for (stitch in stitches) if (points.lastOrNull()?.let { it.x == stitch.x && it.y == stitch.y } != true) {
        points += stitch
        if (points.size == 4) break
    }
    if (points.size < 4) return false
    fun distance(a: Stitch, b: Stitch) = hypot(a.x.toDouble() - b.x, a.y.toDouble() - b.y)
    // Three real 0.3–1.5mm moves in a compact start, returning as A-B-A-B or A-B-C-A.
    if (points.zipWithNext().any { (a, b) -> distance(a, b) !in 3.0..15.0 }) return false
    if (points.any { distance(points[0], it) > 15.0 }) return false
    return distance(points[0], points[3]) <= 3.0 ||
        (distance(points[0], points[2]) <= 3.0 && distance(points[1], points[3]) <= 3.0)
}
