// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.sewout

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.inkstitch.svg.StitchTypeRule
import io.github.linker2k.myembro.engine.inkstitch.svg.VectorArt
import io.github.linker2k.myembro.engine.io.PesWriter
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.operation.buffer.BufferParameters
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.test.Test

/**
 * Evidence for the satin minimum (StitchTypeRule.SATIN_MIN_MM): narrow areas of one width, forced
 * through the area-satin path (rule minimum 0), written as PES and judged by SewoutCheck. A width
 * passes when every shape becomes a complete satin with zero blockers. The minimum is the smallest
 * width from which every measured width up to 6mm passes.
 *
 * Opt-in: SATIN_SWEEP=<out dir>.
 */
class SatinMinimumSweep {
    private val f = GeometryFactory()
    private val flat = BufferParameters().apply { endCapStyle = BufferParameters.CAP_FLAT }
    private fun line(vararg xy: Double) = f.createLineString((xy.indices step 2).map { Coordinate(xy[it], xy[it + 1]) }.toTypedArray())
    private fun band(g: Geometry, w: Double) = org.locationtech.jts.operation.buffer.BufferOp.bufferOp(g, w / 2, flat)

    /** Shapes in mm: straight 20mm bar, gentle arc, closed ring, zigzag, short 6mm bar. */
    private val shapes: Map<String, (Double) -> Geometry> = mapOf(
        "bar20" to { w -> band(line(0.0, 0.0, 20.0, 0.0), w) },
        "arc" to { w -> band(f.createLineString((0..40).map { i -> val a = Math.PI * i / 40; Coordinate(10 * Math.cos(a), -10 * Math.sin(a)) }.toTypedArray()), w) },
        "ring" to { w -> f.createPoint(Coordinate(0.0, 0.0)).buffer(10 + w / 2).difference(f.createPoint(Coordinate(0.0, 0.0)).buffer(10 - w / 2)) },
        "zigzag" to { w -> band(line(0.0, 0.0, 8.0, 10.0, 16.0, 0.0, 24.0, 10.0), w) },
        "bar6" to { w -> band(line(0.0, 0.0, 6.0, 0.0), w) },
    )

    private fun pathData(g: Geometry) = (0 until g.numGeometries).map { g.getGeometryN(it) as Polygon }.joinToString(" ") { p ->
        (listOf(p.exteriorRing) + (0 until p.numInteriorRing).map(p::getInteriorRingN)).joinToString(" ") { ring ->
            "M" + ring.coordinates.joinToString(" L") { String.format(Locale.ROOT, "%.4f,%.4f", it.x, it.y) } + " Z"
        }
    }

    /** null = complete satin and gate pass; otherwise why not. */
    private fun verdict(shape: String, widthMm: Double): String? = runCatching {
        val g = shapes.getValue(shape)(widthMm)
        val env = g.envelopeInternal
        val art = VectorArt.Art(listOf(env.minX, env.minY, env.width, env.height),
            listOf(VectorArt.Shape(pathData(g), fill = 0x000000, stroke = null, fillRule = io.github.linker2k.myembro.engine.geometry.FillRule.EVEN_ODD)))
        // Picture width = shape width, so source units stay mm (scale 1).
        val result = VectorArt.render(art, env.width, VectorArt.Rules(satinMinMm = 0.0))
        if (result.parts.any { it.method != StitchTypeRule.Type.SATIN }) return "no complete satin (${result.parts.map { it.method }})"
        val pes = PesWriter.write(result.pattern)
        val report = SewoutCheck.checkPes(pes, options = SewoutOptions(hoop = HoopSpec.H100, origin = DesignOrigin.APP_GENERATED))
        (PesInvariants.violations(pes, HoopSpec.H100) + report.findings.filter { it.severity == Severity.BLOCKER }.map { it.code.name })
            .takeIf { it.isNotEmpty() || !report.analysisComplete }?.joinToString(",")
    }.getOrElse { "ERROR ${it.javaClass.simpleName}: ${it.message?.take(70)}" }

    @Test fun sweep() {
        val out = System.getenv("SATIN_SWEEP")?.let(::File) ?: return
        val widths = (2..20).map { it / 10.0 } + listOf(2.5, 3.0, 4.0, 5.0, 6.0)
        val pool = Executors.newFixedThreadPool(8)
        val results = shapes.keys.associateWith { s -> widths.map { w -> pool.submit<String?> { verdict(s, w) } } }
            .mapValues { (_, v) -> v.map { it.get() } }
        pool.shutdown()
        val failing = widths.indices.filter { i -> results.values.any { it[i] != null } }
        val minimum = widths.getOrNull((failing.maxOrNull() ?: -1) + 1)
        val lines = listOf("shape\t" + widths.joinToString("\t")) +
            results.map { (s, v) -> s + "\t" + v.joinToString("\t") { if (it == null) "o" else "x" } }
        out.mkdirs()
        File(out, "satin-minimum.tsv").writeText(lines.joinToString("\n") + "\n" +
            results.flatMap { (s, v) -> v.mapIndexedNotNull { i, r -> r?.let { "fail\t$s\t${widths[i]}\t$it" } } }.joinToString("\n") + "\n")
        lines.forEach { println("SATIN $it") }
        results.forEach { (s, v) -> v.forEachIndexed { i, r -> if (r != null) println("SATIN fail $s ${widths[i]}mm $r") } }
        println("SATIN minimum ${minimum}mm (rule ${StitchTypeRule.SATIN_MIN_MM}mm)")
    }
}
