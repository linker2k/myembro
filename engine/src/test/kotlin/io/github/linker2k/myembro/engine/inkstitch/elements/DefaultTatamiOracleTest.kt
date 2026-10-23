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
        val plan=StitchPlanBuilder.fromGroups(groups)
        val pes=PesWriter.writeV6(EmbPatternAdapter.toEmbPattern(plan))
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
        val firstDifference=(0 until minOf(a.size,b.size)).firstOrNull { hypot(a[it].first-b[it].first,a[it].second-b[it].second)>1e-7 }
            ?: if(a.size!=b.size) minOf(a.size,b.size) else -1
        val orderedErrors=(0 until minOf(a.size,b.size)).map { hypot(a[it].first-b[it].first,a[it].second-b[it].second) }
        val firstOverTolerance=orderedErrors.indexOfFirst { it>0.2 }
        val details=buildJsonObject {
            put("candidate_count",a.size);put("official_count",b.size)
            put("count_ratio",a.size.toDouble()/b.size);put("count_relative_difference",countDelta)
            put("hausdorff_mm",distance);put("first_difference_index",firstDifference)
            put("first_difference_over_02mm_index",firstOverTolerance)
            put("ordered_max_error_mm",orderedErrors.maxOrNull() ?: 0.0)
            put("input_polygon_vertices",input.regions.sumOf { it.geometry.numPoints })
            putJsonArray("candidate_prefix_mm") { a.take(8).forEach { add(JsonArray(listOf(JsonPrimitive(it.first),JsonPrimitive(it.second)))) } }
            putJsonArray("official_prefix_mm") { b.take(8).forEach { add(JsonArray(listOf(JsonPrimitive(it.first),JsonPrimitive(it.second)))) } }
            putJsonArray("raw_layers") { groups.forEach { group -> add(buildJsonObject {
                put("stage",if("fill_underlay" in group.stitches.first().tags) "underlay" else "top")
                put("count",group.stitches.size)
                putJsonArray("start_mm") { add(group.stitches.first().x/PIXELS_PER_MM);add(group.stitches.first().y/PIXELS_PER_MM) }
                putJsonArray("end_mm") { add(group.stitches.last().x/PIXELS_PER_MM);add(group.stitches.last().y/PIXELS_PER_MM) }
            }) } }
            put("postprocess_needles",plan.colorBlocks.sumOf { block -> block.stitches.count { !it.isJump && !it.isTrim && !it.isStop && !it.isColorChange && !it.isEnd } })
        }
        println("ORACLE $shape ratio=${a.size.toDouble()/b.size} hausdorff_mm=$distance first_difference_index=$firstDifference")
        File(out,"$shape.json").writeText(Json { prettyPrint=true }.encodeToString(JsonObject.serializer(),details))
        // Worker is adding POLICY.md's five-condition gate; connect its generated-output API here after merge.
        assertTrue(countDelta<=0.05 && distance<=0.2,
            "$shape: counts ${a.size}/${b.size} (delta=$countDelta <=0.05), Hausdorff=$distance mm <=0.2")
    }
}
