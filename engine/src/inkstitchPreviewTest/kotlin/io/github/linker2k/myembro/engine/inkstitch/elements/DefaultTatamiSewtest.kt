// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.elements

import io.github.linker2k.myembro.engine.geometry.FillRule
import io.github.linker2k.myembro.engine.geometry.SvgPath
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.EmbPatternAdapter
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.StitchPlanBuilder
import io.github.linker2k.myembro.engine.io.PesReader
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.model.EmbThread
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest

/** Four original analytic fixtures; exports candidates, never assigns a quality verdict. */
object DefaultTatamiSewtest {
    @JvmStatic fun main(args: Array<String>) {
        val out = File(args.firstOrNull() ?: "/tmp/myembro-sewtest/s4").apply { mkdirs() }
        val fixtures = listOf(
            Triple("SQUARE20", "Square 20 x 20 mm", "M-100 -100 H100 V100 H-100 Z"),
            Triple("CIRCLE25", "Circle diameter 25 mm", "M-125 0 A125 125 0 1 0 125 0 A125 125 0 1 0 -125 0 Z"),
            Triple("DONUT30", "Donut outer 30 / inner 12 mm", "M-150 0 A150 150 0 1 0 150 0 A150 150 0 1 0 -150 0 Z M-60 0 A60 60 0 1 0 60 0 A60 60 0 1 0 -60 0 Z"),
            // Original block H: 16mm wide, 20mm high; bars 4mm thick. No font assets.
            Triple("BLOCKH20", "Block H height 20 mm", "M-80 -100 H-40 V-20 H40 V-100 H80 V100 H40 V20 H-40 V100 H-80 Z"),
        )
        val cases = fixtures.map { (id, label, path) ->
            val region = SvgPath.polygons(path, FillRule.EVEN_ODD, tolerance=0.1)
            val groups = DefaultTatami.stitchGroups(region, EmbThread(0xff245b85.toInt(), "Blue"))
            val pattern = EmbPatternAdapter.toEmbPattern(StitchPlanBuilder.fromGroups(groups))
            val bytes = PesWriter.writeV6(pattern)
            val decoded = PesReader.read(bytes)
            require(decoded.stitchCount == pattern.stitchCount)
            require(decoded.bounds!!.width <= 1000 && decoded.bounds!!.height <= 1000)
            if (id == "DONUT30") require(decoded.stitches.filter { it.command == Command.STITCH }
                .none { it.x.toDouble()*it.x + it.y.toDouble()*it.y < 59.0*59.0 })
            val pes = File(out, "$id.pes").apply { writeBytes(bytes) }
            File(out, "$id.svg").writeText("""<svg xmlns="http://www.w3.org/2000/svg" width="40mm" height="40mm" viewBox="-200 -200 400 400"><path d="$path" fill-rule="evenodd" fill="#245b85"/></svg>""")
            println("$id: ${decoded.stitchCount} stitches, ${decoded.bounds!!.width/10.0} x ${decoded.bounds!!.height/10.0} mm")
            buildJsonObject {
                put("id",id); put("label",label); put("path",pes.absolutePath); put("style","fill")
                put("stitches",decoded.stitchCount); put("width_mm",decoded.bounds!!.width/10.0)
                put("height_mm",decoded.bounds!!.height/10.0)
                put("sha256",MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            }
        }
        File(out,"manifest.json").writeText(Json { prettyPrint=true }.encodeToString(JsonObject.serializer(),buildJsonObject {
            put("api","DefaultTatami.stitchGroups -> StitchPlanBuilder -> PES v6")
            put("license","CC0-1.0 (original geometric fixture designs)")
            put("acceptance","Candidates only; independent gate and user sew-out required")
            put("cases",JsonArray(cases))
        }))
    }
}
