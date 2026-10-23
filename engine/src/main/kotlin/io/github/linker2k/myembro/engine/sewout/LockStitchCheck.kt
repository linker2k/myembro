// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.sewout

import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.model.Stitch
import kotlin.math.hypot

/** Functional boundary checks from the frozen quality-gate v5 lock_geometry.py.
 * Accept short sewn returns or 2–3 boundary needles; do not require brand-specific bytes.
 * Ordinary JUMPs do not create new threads and cannot supply a sewn return leg. */
internal data class ThreadLockCounts(val sections: Int, val startMissing: Int, val endMissing: Int)

internal fun checkThreadLocks(stitches: List<Stitch>, findings: Findings, severity: Severity): ThreadLockCounts {
    var sections = 0; var startMissing = 0; var endMissing = 0; var needles = 0
    data class Needle(val point: NeedlePoint, val from: NeedlePoint, val index: Int)
    var prior = NeedlePoint(0.0, 0.0)
    val section = mutableListOf<Needle>()
    fun distance(a: NeedlePoint, b: NeedlePoint) = hypot(a.x-b.x, a.y-b.y)
    fun locked(start: Boolean): Boolean {
        val seq = if (start) section.take(16) else section.asReversed().take(16)
        fun inspect(relaxed: Boolean): Boolean {
            val coords = mutableListOf<NeedlePoint>()
            var previous: Needle? = null
            var repeat = 0
            for (needle in seq) {
                val q = needle.point
                val old = previous
                if (old != null && distance(if (start) needle.from else old.from,
                        if (start) coords.last() else q) > 1e-6) break
                val leg = coords.lastOrNull()?.let { distance(q, it) }
                if (leg != null && leg > (if (relaxed) 10.0 else 30.0) + 1e-6) break
                if (coords.any { distance(q, it) > 30.0 + 1e-6 }) break
                repeat = if (leg != null && leg < 1e-6) repeat + 1 else 1
                previous = needle
                if (relaxed && repeat in 2..3) return true
                if (!relaxed && leg != null && leg < 1e-6) continue
                coords += q
                val path = if (relaxed && start && distance(seq.first().from, coords.first()) <= 10.0 + 1e-6)
                    listOf(seq.first().from) + coords else coords
                val edges = path.zipWithNext().filter { (a,b) -> distance(a,b) > 1e-6 }
                    .map { (a,b) -> NeedlePoint(b.x-a.x,b.y-a.y) }
                if (edges.size < 2) continue
                if (relaxed && edges.zipWithNext().any { (a,b) -> a.x*b.x+a.y*b.y < -1e-7 }) return true
                if (!relaxed && coords.size >= 3) {
                    val travel = edges.sumOf { hypot(it.x,it.y) }
                    val reverse = edges.zipWithNext().any { (a,b) -> a.x*b.x+a.y*b.y <= -.25*hypot(a.x,a.y)*hypot(b.x,b.y)+1e-7 }
                    if (travel >= 2.0-1e-6 && distance(coords.first(),coords.last()) <= .55*travel+1e-6 && reverse) return true
                }
            }
            return false
        }
        return inspect(true) || inspect(false)
    }
    fun flush() {
        if (section.isEmpty()) return
        // v5 metric(): zero-only control placeholders do not constitute a sewn thread section.
        // Their needles are still counted by density and consecutive-repeat checks.
        if (section.none { it.point != it.from }) { section.clear(); return }
        sections++
        for (start in listOf(true,false)) if (!locked(start)) {
            if (start) startMissing++ else endMissing++
            val seq = if (start) section.take(16) else section.takeLast(16)
            findings.add(FindingCode.MISSING_LOCK_STITCH, severity,
                if (start) "실 시작 잠금 땀을 확인하지 못했습니다." else "실 끝 잠금 땀을 확인하지 못했습니다.",
                Highlight(seq.first().index,seq.last().index,seq.first().point,seq.last().point))
        }
        section.clear()
    }
    fun result(): ThreadLockCounts {
        if (needles > 0 && sections == 0) findings.add(FindingCode.MISSING_LOCK_STITCH, severity,
            "실 시작 잠금을 확인할 봉제 구간이 없습니다.")
        return ThreadLockCounts(sections,startMissing,endMissing)
    }
    for ((index, stitch) in stitches.withIndex()) {
        val p = NeedlePoint(stitch.x.toDouble(),stitch.y.toDouble())
        if (stitch.command == Command.STITCH) { needles++; section += Needle(p,prior,index) }
        else if (stitch.command in setOf(Command.TRIM,Command.STOP,Command.COLOR_CHANGE,Command.END)) flush()
        prior = p
        if (stitch.command == Command.END) return result()
    }
    flush()
    return result()
}
