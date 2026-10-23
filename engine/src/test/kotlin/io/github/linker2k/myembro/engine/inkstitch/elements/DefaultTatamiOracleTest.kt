// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.elements

import io.github.linker2k.myembro.engine.geometry.PolygonRegion
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.*
import io.github.linker2k.myembro.engine.inkstitch.svg.PIXELS_PER_MM
import io.github.linker2k.myembro.engine.io.PesReader
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.Command
import kotlinx.serialization.json.*
import org.locationtech.jts.geom.util.AffineTransformation
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.math.*
import kotlin.test.*

class DefaultTatamiOracleTest {
    @Test fun rectangleMatchesOfficialDefaults() = compare("rectangle")
    @Test fun circleMatchesOfficialDefaults() = compare("circle")
    @Test fun holeMatchesOfficialDefaults() = compare("hole")

    private fun compare(shape: String) {
        val stem="fill_stitch--$shape--020mm--upstream-default"
        val base="/inkstitch-oracle/tatami-defaults-v3.3.0/$stem"
        fun resource(suffix:String)=javaClass.getResourceAsStream("$base.$suffix") ?: error("Missing $base.$suffix")
        val input=FillMatrixInput.read(resource("svg").use { it.readBytes() })
        val groups=input.regions.flatMap { r ->
            val native=AffineTransformation.scaleInstance(10/PIXELS_PER_MM,10/PIXELS_PER_MM).transform(r.geometry)
            DefaultTatami.stitchGroups(PolygonRegion(native),r.thread)
        }
        val pes=PesWriter.writeV6(EmbPatternAdapter.toEmbPattern(StitchPlanBuilder.fromGroups(groups)))
        val candidate=PesReader.read(pes).stitches.filter { it.command==Command.STITCH }
            .map { Pair(it.x/10.0,it.y/10.0) }
        val reference=Json.parseToJsonElement(GZIPInputStream(resource("coordinates.json.gz")).use { it.reader().readText() })
            .jsonObject.getValue("needles_mm").jsonArray.map { it.jsonArray.let { a -> a[0].jsonPrimitive.double to a[1].jsonPrimitive.double } }
        fun centered(points:List<Pair<Double,Double>>):List<Pair<Double,Double>> {
            val x=(points.minOf { it.first }+points.maxOf { it.first })/2
            val y=(points.minOf { it.second }+points.maxOf { it.second })/2
            return points.map { it.first-x to it.second-y }
        }
        val a=centered(candidate);val b=centered(reference)
        fun directed(from:List<Pair<Double,Double>>,to:List<Pair<Double,Double>>)=from.maxOf { p -> to.minOf { q -> hypot(p.first-q.first,p.second-q.second) } }
        val countDelta=abs(a.size-b.size).toDouble()/b.size
        val distance=max(directed(a,b),directed(b,a))
        val out=File("build/tatami-oracle").apply { mkdirs() }
        File(out,"$shape.pes").writeBytes(pes)
        File(out,"$shape.json").writeText("""{"candidate_count":${a.size},"official_count":${b.size},"count_relative_difference":$countDelta,"hausdorff_mm":$distance}""")
        // Worker is adding POLICY.md's five-condition gate; connect its generated-output API here after merge.
        assertTrue(countDelta<=0.05 && distance<=0.2,
            "$shape: counts ${a.size}/${b.size} (delta=$countDelta <=0.05), Hausdorff=$distance mm <=0.2")
    }
}
