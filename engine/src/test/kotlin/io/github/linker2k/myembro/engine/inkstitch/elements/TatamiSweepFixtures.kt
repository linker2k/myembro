// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.elements

import io.github.linker2k.myembro.engine.geometry.*
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.*
import io.github.linker2k.myembro.engine.io.PesReader
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.EmbThread
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest
import kotlin.math.*

/** Extended local sweep only. Fixed original analytic shapes, no private/font inputs. */
object TatamiSweepFixtures {
    @JvmStatic fun main(args: Array<String>) {
        val out=File(args[0]).apply { mkdirs() }
        val sizes=args.getOrNull(1)?.split(',')?.map(String::toInt) ?: (5..90).toList()
        require(sizes.isNotEmpty() && sizes.distinct().size==sizes.size && sizes.all { it in 5..90 })
        val rows=mutableListOf<JsonObject>()
        val json=Json { prettyPrint=true }
        fun save()=File(out,"generation.json").writeText(json.encodeToString(JsonObject.serializer(),buildJsonObject {
            put("api","DefaultTatami.stitchGroups -> StitchPlanBuilder defaults -> PES v6")
            put("angle_deg",0);put("sizes_mm",JsonArray(sizes.map(::JsonPrimitive)))
            put("cases",JsonArray(rows))
        }))
        for(shape in listOf("square","circle","donut","star","block_h")) for(size in sizes) {
            val id="${shape}_${size}mm";val pes=File(out,"$id.pes");val start=System.nanoTime()
            val row=buildJsonObject {
                put("id",id);put("shape",shape);put("size_mm",size);put("angle_deg",0)
                put("path",pes.absolutePath)
                try {
                    val circle="M-50 0 A50 50 0 1 0 50 0 A50 50 0 1 0 -50 0 Z"
                    val path=when(shape) {
                        "square" -> "M-50 -50 H50 V50 H-50 Z"
                        "circle" -> circle
                        "donut" -> "$circle M-20 0 A20 20 0 1 0 20 0 A20 20 0 1 0 -20 0 Z"
                        "block_h" -> "M-40 -50 H-20 V-10 H20 V-50 H40 V50 H20 V10 H-20 V50 H-40 Z"
                        else -> (0..9).joinToString(" ") { i ->
                            val angle=-PI/2+i*PI/5;val radius=if(i%2==0) 50.0 else 22.5
                            "${if(i==0) "M" else "L"}${cos(angle)*radius} ${sin(angle)*radius}"
                        }+" Z"
                    }
                    // Input is native 0.1mm; nominal shape spans 100 units before size scaling.
                    val region=SvgPath.polygons(path,FillRule.EVEN_ODD,Affine2D.scale(size/10.0),0.1)
                    put("area_mm2",region.geometry.area/100)
                    val groups=DefaultTatami.stitchGroups(region,EmbThread(0xff245b85.toInt(),"Blue"))
                    val pattern=EmbPatternAdapter.toEmbPattern(StitchPlanBuilder.fromGroups(groups))
                    val bytes=PesWriter.writeV6(pattern)
                    val decoded=PesReader.read(bytes)
                    require(decoded.stitchCount==PesWriter.prepareForOutput(pattern).stitchCount) { "PES round-trip needle count differs" }
                    pes.writeBytes(bytes)
                    put("sha256",MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
                    put("stitches",decoded.stitchCount);put("status","GENERATED")
                } catch(e:Exception) {
                    pes.delete();put("status","GENERATION_ERROR");put("error","${e.javaClass.simpleName}: ${e.message}")
                }
                put("elapsed_ms",(System.nanoTime()-start)/1_000_000)
            }
            rows+=row;save();println("$id ${row["status"]} ${row["elapsed_ms"]}ms")
        }
    }
}
