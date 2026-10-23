// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch

import io.github.linker2k.myembro.engine.inkstitch.lettering.*
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.io.PesReader
import io.github.linker2k.myembro.engine.model.EmbThread
import java.io.File
import kotlinx.serialization.json.*
import kotlin.test.*

/** Three name cases only. Font data stays external; no private PES is read. */
class InkFontNameReproductionTest {
    @Test fun generateThreeNames() {
        val root=System.getProperty("myembro.nameOutput")?.let(::File) ?: return
        val input=root.resolve("inputs")
        val font=InkStitchFont.parse(input.resolve("ltr.svg").inputStream(),input.resolve("font.json").readText())
        assertTrue("HJungMin".all { it.toString() in font.glyphs })
        assertTrue(font.unsupported.isEmpty(),font.unsupported.toString())
        val cases=listOf(12.0,20.0,30.0).map { height ->
            val placements=InkStitchLettering.layout(font,"Jung Min",height)
            val elements=placements.flatMap { it.glyph.elements }
            assertTrue(elements.any { it is InkSatin })
            assertFalse(elements.any { it is InkFill })
            val pattern=InkStitchLettering.render(font,"Jung Min",height,EmbThread(0xFF000000.toInt()))
            assertTrue(pattern.metadata.getValue(GeneratedPatternProcessor.LOCK_INDICES_KEY).isNotBlank())
            val id="aventurina-jung-min-${height.toInt()}mm"
            val file=root.resolve("$id.pes").apply { writeBytes(PesWriter.write(pattern)) }
            assertEquals(pattern.stitchCount,PesReader.read(file.readBytes()).stitchCount)
            buildJsonObject {
                put("id",id);put("font_id","aventurina");put("text","Jung Min");put("height_mm",height)
                put("path",file.absolutePath);put("style","satin");put("text_check",true)
                put("stitch_count",pattern.stitchCount);put("satin_elements",elements.count { it is InkSatin })
                put("running_elements",elements.count { it is InkRun })
                put("recorded_min_height_mm",font.minHeightMm!!);put("recorded_max_height_mm",font.maxHeightMm!!)
                put("within_recorded_range",height>=font.minHeightMm!! && height<=font.maxHeightMm!!)
                put("placements",buildJsonArray { placements.forEach { p->add(buildJsonObject {
                    put("glyph",p.glyph.name);put("dx_px",p.dx/InkStitchFont.MM_PER_PX)
                    put("dy_px",p.dy/InkStitchFont.MM_PER_PX);put("scale",p.scale/InkStitchFont.MM_PER_PX)
                }) } })
            }
        }
        root.resolve("cases.json").writeText(Json { prettyPrint=true }.encodeToString(JsonObject.serializer(),
            buildJsonObject { put("cases",JsonArray(cases)) }))
    }
}
