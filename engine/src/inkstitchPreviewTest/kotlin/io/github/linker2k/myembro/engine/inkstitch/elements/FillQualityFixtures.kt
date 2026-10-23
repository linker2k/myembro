// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.elements

import io.github.linker2k.myembro.engine.geometry.*
import io.github.linker2k.myembro.engine.inkstitch.stitches.*
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.*
import io.github.linker2k.myembro.engine.inkstitch.svg.PIXELS_PER_MM
import io.github.linker2k.myembro.engine.io.PesReader
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.EmbThread
import kotlinx.serialization.json.*
import java.io.File

/** Emits genuine SVG→element→shared plan→PES candidates; makes no quality verdict itself. */
object FillQualityFixtures {
    @JvmStatic fun main(args: Array<String>) {
        val directory = File(args.firstOrNull() ?: "/tmp/myembro-fill-quality").apply { mkdirs() }
        val shapes = listOf("rectangle","hole","concave","bowtie")
        val methods = listOf("tatami","underlay-multi","legacy","contour-inner","contour-single","contour-double",
            "guided-copy","guided-offset","guided-buffer","circular","gradient","tartan","tartan-herringbone","meander")
        val heights = listOf(12.0,24.0)
        val cases = mutableListOf<JsonObject>()
        val errors = mutableListOf<String>()
        for (shape in shapes) for (method in methods) for (height in heights) {
            val id = "$method:$shape"
            val stem = "${method}_${shape}_${height.toInt()}"
            val output = File(directory,"$stem.pes")
            // Keep every declared case, including a failed generator: the gate must reject missing output.
            cases += buildJsonObject {
                put("font",id); put("height_mm",height); put("style","fill"); put("path",output.absolutePath)
            }
            try {
                val source = FillQualityFixtures::class.java.getResource("/inkstitch/fill/$shape.svg")!!.readText()
                val path = Regex("d=\"([^\"]+)\"").find(source)!!.groupValues[1]
                val scale = height*PIXELS_PER_MM/100
                val region = SvgPath.polygons(path,FillRule.EVEN_ODD,Affine2D.scale(scale),0.05).geometry
                fun point(x: Double,y: Double) = FillPoint(x*scale,y*scale)
                val mode: FillMethod = when (method) {
                    "tatami", "underlay-multi" -> FillMethod.Tatami
                    "legacy" -> FillMethod.Legacy
                    "contour-inner" -> FillMethod.Contour(ContourFillParameters(contour_strategy=0))
                    "contour-single" -> FillMethod.Contour(ContourFillParameters(contour_strategy=1))
                    "contour-double" -> FillMethod.Contour(ContourFillParameters(contour_strategy=2))
                    "guided-copy", "guided-offset", "guided-buffer" -> FillMethod.Guided(
                        FillGeometry.line(listOf(point(-20.0,50.0),point(60.0,30.0),point(140.0,50.0))),
                        options=GuidedFill.Parameters(guided_fill_strategy=when(method) { "guided-offset" -> 1; "guided-buffer" -> 2; else -> 0 }))
                    "circular" -> FillMethod.Circular()
                    "gradient" -> FillMethod.Gradient(LinearGradientFill.Gradient(point(0.0,0.0),point(0.0,100.0),
                        listOf(LinearGradientFill.Stop(0.0,"#c82828"),LinearGradientFill.Stop(1.0,"#2848c8"))))
                    "tartan", "tartan-herringbone" -> FillMethod.Tartan(TartanFill.Settings(warp=listOf(
                        TartanFill.Stripe("#c82828",4.0),TartanFill.Stripe("#2848c8",3.0))),
                        TartanFill.Parameters(herringbone_width_mm=if(method=="tartan") 0.0 else 3.0))
                    "meander" -> FillMethod.Meander()
                    else -> error(method)
                }
                val params = FillParameters(fill_method=mode.id,random_seed="public-fixture",
                    fill_underlay_angle=if(method=="underlay-multi") "45 -45" else null)
                val element = FillStitch(region,EmbThread(0xFFc82828.toInt(),"Red"),params,method=mode)
                val groups = element.toStitchGroups(null)
                val plan = StitchPlanBuilder.fromGroups(groups)
                val pattern = EmbPatternAdapter.toEmbPattern(plan)
                val bytes = PesWriter.writeV6(pattern)
                require(PesReader.read(bytes).stitches.isNotEmpty())
                output.writeBytes(bytes)
                File(directory,"$stem.svg").writeText(source.replace("<path ","<path transform=\"scale($scale)\" "))
            } catch (e: Exception) {
                output.delete()
                errors += "$stem: ${e::class.simpleName}: ${e.message}"
            }
        }
        val matrix = buildJsonObject {
            putJsonObject("axes") {
                putJsonArray("fonts") { for (shape in shapes) for (method in methods) add("$method:$shape") }
                putJsonArray("heights_mm") { heights.forEach { add(it) } }
                putJsonArray("styles") { add("fill") }
            }
            put("cases",JsonArray(cases))
        }
        File(directory,"matrix.json").writeText(Json { prettyPrint=true }.encodeToString(JsonObject.serializer(),matrix))
        File(directory,"generation-errors.json").writeText(Json.encodeToString(JsonArray.serializer(),JsonArray(errors.map(::JsonPrimitive))))
        println("Fill candidates: ${cases.size-errors.size}/${cases.size}; errors=${errors.size}; matrix=${File(directory,"matrix.json")}")
        errors.forEach(::println)
    }
}
