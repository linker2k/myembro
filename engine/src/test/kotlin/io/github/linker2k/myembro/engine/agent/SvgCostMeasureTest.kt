// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.agent

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.edit.ShapeObject
import io.github.linker2k.myembro.engine.geometry.SvgPath
import io.github.linker2k.myembro.engine.inkstitch.svg.SvgPictureInput
import io.github.linker2k.myembro.engine.io.PesWriteSettings
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.sewout.*
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.test.Test

/** Local cost curve of the SVG picture path; never a CI check. Groups a (bundled emoji) and b
 * (/tmp/myembro-cute-art/final-svg, read only) run here with
 * `./gradlew :engine:test --tests '*SvgCostMeasureTest' -PsvgMeasure=true -PsvgCostGroups=a,b --rerun`.
 * Synthetic (c) and hostile (d) cases run one per JVM through [SvgCostCli] with a heap and time cap. */
class SvgCostMeasureTest {
    @Test fun measure() {
        if (System.getProperty("svgMeasure") != "true") { println("SvgCostMeasureTest skipped (-PsvgMeasure=true)"); return }
        val groups = System.getProperty("svgCostGroups")?.takeIf { it.isNotBlank() } ?: "a,b"
        val raw = File(SvgCost.out, "raw-$groups.tsv").apply { writeText(SvgCost.HEADER + "\n") }
        val only = groups.split(',').toSet()
        val files = buildList {
            if ("a" in only) File("../app/src/main/assets/emoji/bundled").listFiles()!!.filter { it.extension == "svg" }.sortedBy { it.name }.forEach { add("a-bundled" to it) }
            if ("b" in only) File("/tmp/myembro-cute-art/final-svg").listFiles().orEmpty().filter { it.extension == "svg" }.sortedBy { it.length() }.forEach { add("b-cute-art" to it) }
        }
        for ((group, file) in files) SvgCost.row(group, file.name, file.readBytes()).let { println("SVGCOST\t$it"); raw.appendText(it + "\n") }
    }
}

/** Stages follow PhotoViewModel.generateSvg: parse (input checks + VectorArt.parseSvg + flatten),
 * flatten alone, ShapeObject.fromArt (VectorArt.render) at 50mm, PES V1 + SewoutCheck on SE400/H100. */
internal object SvgCost {
    const val HEADER = "group\tname\tbytes\telements\tpoints\tparse_ms\tparse_alloc_mb\tparse_peak_mb\tflatten_ms\tflatten_alloc_mb\tflatten_peak_mb\trender_ms\trender_alloc_mb\trender_peak_mb\tcheck_ms\tcheck_alloc_mb\tcheck_peak_mb\tstitches\tverdict\tpolicy"
    /** Measurement-only bypass: the real input policy is [SvgPictureInput.parse] with default limits. */
    val wide = SvgPictureInput.Limits(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)
    var stageTimeoutMs = (System.getProperty("svgCostTimeoutMs") ?: "120000").toLong()
    var repeatBelowMs = 20_000L
    val out = File(System.getProperty("svgCostOut") ?: "/tmp/myembro-svg-cost").apply { mkdirs() }

    class Stage(val ms: Long?, val allocMb: Double?, val peakMb: Double?, val error: String?)

    /** One run on a fresh thread: wall time, bytes allocated by that thread, sampled heap peak over the GC'd baseline. */
    fun <T> run(block: () -> T): Pair<Stage, T?> {
        val mx = ManagementFactory.getMemoryMXBean()
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        System.gc(); Thread.sleep(50)
        val base = mx.heapMemoryUsage.used
        var peak = base
        var result: T? = null; var error: Throwable? = null; var ms = 0L; var alloc = 0L
        val worker = Thread {
            val tid = Thread.currentThread().id
            val a0 = threads.getThreadAllocatedBytes(tid); val t0 = System.nanoTime()
            try { result = block() } catch (t: Throwable) { error = t }
            ms = (System.nanoTime() - t0) / 1_000_000; alloc = threads.getThreadAllocatedBytes(tid) - a0
        }.apply { isDaemon = true }
        worker.start()
        val deadline = System.currentTimeMillis() + stageTimeoutMs
        while (worker.isAlive && System.currentTimeMillis() < deadline) {
            peak = maxOf(peak, mx.heapMemoryUsage.used); worker.join(2)
        }
        if (worker.isAlive) {
            worker.interrupt(); worker.join(2000)
            @Suppress("DEPRECATION") if (worker.isAlive) worker.stop()
            worker.join(5000)
            return Stage(null, null, (peak - base) / 1e6, "TIMEOUT>${stageTimeoutMs / 1000}s") to null
        }
        val err = error?.let { (it::class.simpleName ?: "Error") + ": " + (it.message ?: "").take(60) }
        return Stage(ms, alloc / 1e6, (peak - base) / 1e6, err) to result
    }

    /** Median of 3 runs; a run over [repeatBelowMs] is measured once. Allocation/peak come from the first run. */
    fun <T> measure(block: () -> T): Pair<Stage, T?> {
        val first = run(block)
        if (first.first.error != null || (first.first.ms ?: 0) > repeatBelowMs) return first
        val times = listOf(first.first.ms!!) + (1..2).map { run(block).first.ms ?: Long.MAX_VALUE }
        return Stage(times.sorted()[1], first.first.allocMb, first.first.peakMb, null) to first.second
    }

    private fun fmt(s: Stage?) = when {
        s == null -> "-\t-\t-"
        s.error != null && s.ms == null -> "${s.error}\t-\t${"%.0f".format(s.peakMb)}"
        else -> "${s.ms}\t${"%.0f".format(s.allocMb)}\t${"%.0f".format(s.peakMb)}"
    }

    fun row(group: String, name: String, bytes: ByteArray): String {
        val policy = runCatching { SvgPictureInput.parse(bytes) }.fold({ "default-accept" }, { "bypass (default rejects: ${it.message})" })
        System.err.println("STAGE parse"); val (parse, parsed) = measure { SvgPictureInput.parse(bytes, wide) }
        var flatten: Stage? = null; var render: Stage? = null; var check: Stage? = null
        var stitches = "-"; var verdict = parse.error ?: "-"
        val m = parsed?.metrics
        if (parsed != null) {
            System.err.println("STAGE flatten")
            flatten = measure { parsed.art.shapes.sumOf { s -> SvgPath.flatten(s.d, s.transform, maxPoints = Int.MAX_VALUE).sumOf { it.points.size } } }.first
            System.err.println("STAGE render")
            val (r, shape) = measure { ShapeObject.fromArt("m", "m", parsed.art, 50.0).also { it.localPattern() } }
            render = r
            if (shape != null) {
                val pattern = shape.localPattern(); stitches = pattern.stitchCount.toString()
                System.err.println("STAGE check")
                val (c, report) = measure {
                    val pes = PesWriter.write(pattern, PesWriteSettings(hoop = HoopSpec.H100))
                    SewoutCheck.checkPes(pes, SewoutMachines.SE400, SewoutOptions(hoop = HoopSpec.H100, origin = DesignOrigin.APP_GENERATED))
                }
                check = c
                verdict = c.error ?: report?.let { rep -> rep.verdict.name + rep.findingSeverities.filterValues { it == Severity.BLOCKER }.keys.joinToString(",", " ").trimEnd() } ?: "-"
            } else verdict = r.error ?: "-"
        }
        return listOf(group, name, bytes.size, m?.elements ?: "-", m?.pathPoints ?: "-", fmt(parse), fmt(flatten), fmt(render), fmt(check), stitches, verdict, policy).joinToString("\t")
    }

    fun svg(body: String, view: String = "0 0 1000 1000") =
        "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"$view\">$body</svg>".toByteArray()
    private val colors = listOf("#d35400", "#2c3e50", "#27ae60", "#f1c40f")

    /** n elements including root: n-1 rects on a grid, 4 colors. */
    private fun rects(n: Int): ByteArray {
        val k = n - 1; val side = kotlin.math.ceil(kotlin.math.sqrt(k.toDouble())).toInt(); val cell = 1000.0 / side
        return svg((0 until k).joinToString("") { i ->
            "<rect x=\"${"%.2f".format((i % side) * cell)}\" y=\"${"%.2f".format((i / side) * cell)}\" width=\"${"%.2f".format(cell * 0.8)}\" height=\"${"%.2f".format(cell * 0.8)}\" fill=\"${colors[i % 4]}\"/>"
        })
    }

    /** One closed wavy-circle polygon of p vertices (one color). */
    private fun wavy(p: Int): ByteArray = svg("<path fill=\"#d35400\" d=\"" + (0 until p).joinToString(" ", "M", "Z") { i ->
        val t = 2 * Math.PI * i / p; val r = 400 + 30 * kotlin.math.sin(t * 24)
        "%.1f %.1f".format(500 + r * kotlin.math.cos(t), 500 + r * kotlin.math.sin(t))
    } + "\"/>")

    private const val LEAF = "<rect width=\"100\" height=\"100\" fill=\"#d35400\"/>"

    /** Synthetic scale (c) and hostile (d) inputs, generated on demand by id. */
    val cases: List<Triple<String, String, () -> ByteArray>> = buildList {
        fun add(g: String, n: String, b: () -> ByteArray) = add(Triple(g, n, b))
        for (n in listOf(14, 100, 1_000, 10_000)) add("c-elements", "rects n=$n") { rects(n) }
        for (p in listOf(202, 2_000, 20_000, 200_000)) add("c-points", "path p=$p") { wavy(p) }
        add("c-bytes", "path p=16000 (largest under 200,000 B)") { wavy(16_000) }
        add("c-bytes", "rects n=2700 (largest under 200,000 B)") { rects(2_700) }
        add("c-bytes", "rects n=14 + 197971 B comment") { String(rects(14)).replace("</svg>", "<!--" + "x".repeat(197_971) + "--></svg>").toByteArray() }
        for (d in listOf(1_000, 5_000, 20_000)) add("d-nested-g", "g depth=$d") { svg("<g>".repeat(d) + LEAF + "</g>".repeat(d)) }
        for (n in listOf(10_000, 16_000, 50_000)) add("d-subpaths", "subpaths n=$n") { svg("<path fill=\"#2c3e50\" d=\"" +
            (0 until n).joinToString("") { i -> "M${(i % 200) * 5} ${(i / 200 % 200) * 5}h3v3z" } + "\"/>") }
        add("d-coords", "1e308 coords") { svg("<path fill=\"#d35400\" d=\"M0 0L1e308 0L1e308 1e308Z\"/>") }
        add("d-coords", "1e400 (inf) coord") { svg("<path fill=\"#d35400\" d=\"M0 0L1e400 0L0 100Z\"/>") }
        add("d-coords", "1e300 viewBox") { svg(LEAF, "0 0 1e300 1e300") }
        add("d-coords", "1e-300 tiny shape") { svg("<path fill=\"#d35400\" d=\"M0 0L1e-300 0L0 1e-300Z\"/>") }
        add("d-transform", "scale(10) x400 nested") { svg("<g transform=\"scale(10)\">".repeat(400) + LEAF + "</g>".repeat(400)) }
        add("d-transform", "rotate(1) x1000 nested") { svg("<g transform=\"rotate(1)\">".repeat(1000) + LEAF + "</g>".repeat(1000)) }
        add("d-transform", "999 overlapping rotated rects") { svg((0 until 999).joinToString("") { i ->
            "<rect x=\"300\" y=\"300\" width=\"400\" height=\"400\" fill=\"${colors[i % 4]}\" transform=\"rotate(${i * 0.36} 500 500)\"/>" }) }
        add("d-transform", "13 overlapping rotated rects (fits element limit)") { svg((0 until 13).joinToString("") { i ->
            "<rect x=\"300\" y=\"300\" width=\"400\" height=\"400\" fill=\"${colors[i % 4]}\" transform=\"rotate(${i * 7} 500 500)\"/>" }) }
        add("d-declaration", "DOCTYPE billion laughs") { ("<?xml version=\"1.0\"?><!DOCTYPE svg [<!ENTITY a \"aaaaaaaaaa\"><!ENTITY b \"&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;\">]>" +
            "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 10 10\"><title>&b;</title>$LEAF</svg>").toByteArray() }
        add("d-declaration", "external ENTITY") { ("<!DOCTYPE svg [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 10 10\"><title>&x;</title>$LEAF</svg>").toByteArray() }
        add("d-reference", "use href") { svg("$LEAF<use href=\"#a\"/>") }
        add("d-reference", "xlink:href on path") { ("<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" viewBox=\"0 0 1000 1000\"><path xlink:href=\"http://x/\" d=\"M0 0h9v9z\"/></svg>").toByteArray() }
        add("d-reference", "fill url()") { svg("<rect width=\"100\" height=\"100\" fill=\"url(#g)\"/>") }
        add("d-reference", "style @import") { svg("<rect width=\"100\" height=\"100\" style=\"@import 'http://x/a.css'\"/>") }
        add("d-script", "script element") { svg("<script>alert(1)</script>$LEAF") }
        add("d-script", "onload attribute") { svg("<rect width=\"100\" height=\"100\" onload=\"alert(1)\"/>") }
    }
}
