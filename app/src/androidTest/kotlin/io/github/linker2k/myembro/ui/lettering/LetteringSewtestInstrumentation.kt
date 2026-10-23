// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.lettering

import android.app.Instrumentation
import android.os.Bundle
import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.inkstitch.elements.SatinParameters
import io.github.linker2k.myembro.engine.lettering.*
import io.github.linker2k.myembro.engine.model.EmbThread
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** Opt-in artifact generator: uses the actual UI renderer and Android Paint, never AWT geometry. */
class LetteringSewtestInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        try {
            runBlocking {
                val fontDir=File(targetContext.filesDir,"fonts").apply { mkdirs() }
                val fontFile=File(fontDir,"dancing-script-bold.ttf")
                context.assets.open("fonts/dancing-script-bold/DancingScript-Bold.ttf").use { input ->
                    fontFile.outputStream().use { input.copyTo(it) }
                }
                context.assets.open("fonts/dancing-script-bold/OFL.txt").use { input ->
                    File(fontDir,"dancing-script-bold.LICENSE.txt").outputStream().use { input.copyTo(it) }
                }
                val out=File(targetContext.filesDir,"sewtest-s2").apply { mkdirs() }
                val rows=JSONArray()
                val cases=listOf(
                    Triple("jung-min","Jung Min", "dancing-script-bold") to listOf(12.0,20.0,30.0),
                    Triple("myembro","myembro","noto-sans-kr") to listOf(10.0,20.0),
                    Triple("jungmin-ko","정민","noto-sans-kr") to listOf(15.0,25.0))
                for ((entry,heights) in cases) for(height in heights) {
                    val (slug,text,fontId)=entry
                    val state=automaticLetteringState(targetContext,fontId,text,height)
                    val settings=LetteringSettings(text,fontId,height,0.0,LetteringAlignment.CENTER,
                        false,LetteringCurve.STRAIGHT,0.0,lineSpacingMm=0.0,stitchStyle=state.style,
                        satinOptions=SatinOptions(parameters=SatinParameters(),autoDensity=false),threadWeight=ThreadWeight.WT40)
                    val result=renderLettering(targetContext,settings,EmbThread(0xFFB5492A.toInt()),HoopSpec.H100)
                    check(!result.unsupported) { "Missing glyph: $fontId $text" }
                    val name="$slug-${height.toInt()}mm"
                    File(out,"$name.pes").writeBytes(PesWriter.write(result.pattern))
                    val commands=JSONArray()
                    result.pattern.stitches.forEach { s -> commands.put(JSONArray().put(s.x/10.0).put(s.y/10.0).put(s.command.name)) }
                    File(out,"$name-stitches.json").writeText(commands.toString())
                    val b=result.pattern.bounds!!
                    rows.put(JSONObject().put("id",name).put("text",text).put("font_id",fontId)
                        .put("height_mm",height).put("style",result.textObject.stitchStyle.name.lowercase())
                        .put("validated_ranges",state.heightRanges.size).put("outside_hoop",result.outsideHoop)
                        .put("width_mm",b.width/10.0).put("height_extent_mm",b.height/10.0)
                        .put("stitches",result.pattern.stitchCount).put("path","$name.pes"))
                }
                File(out,"manifest.json").writeText(JSONObject().put("renderer","Android renderLettering / automaticLetteringState; 4-input UI defaults; 40wt; H100")
                    .put("cases",rows).toString(2))
            }
            finish(-1,Bundle().apply { putString("result","Generated 7 cases in files/sewtest-s2") })
        } catch(error:Throwable) {
            finish(0,Bundle().apply { putString("error",error.stackTraceToString()) })
        }
    }
}
