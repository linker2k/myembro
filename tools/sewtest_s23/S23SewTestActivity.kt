// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.lettering

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import io.github.linker2k.myembro.engine.geometry.SvgPath
import io.github.linker2k.myembro.engine.inkstitch.elements.DefaultTatami
import io.github.linker2k.myembro.engine.inkstitch.lettering.*
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.*
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.lettering.*
import io.github.linker2k.myembro.engine.model.*
import io.github.linker2k.myembro.engine.edit.HoopSpec
import org.json.*
import java.io.File

/** Opt-in public sewout fixture generator; compiled only by generate_s23_sewtest.py. */
class S23SewTestActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(TextView(this).apply { text="Generating S2 + S3 sewout fixtures…" })
        Thread {
            val output=File(filesDir,"s23").apply { mkdirs() }
            try {
                val thread=EmbThread(0xff000000.toInt(),"Black 40wt")
                val records=JSONArray()
                fun export(stem:String,label:String,pattern:EmbPattern) {
                    val bytes=PesWriter.write(pattern)
                    File(output,"$stem.PES").writeBytes(bytes)
                    val decoded=PesReader.read(bytes)
                    val b=checkNotNull(decoded.bounds)
                    records.put(JSONObject().put("file","$stem.PES").put("label",label)
                        .put("stitches",decoded.stitchCount).put("width_mm",b.width/10.0).put("height_mm",b.height/10.0))
                }
                for ((folder,text,height,stem) in listOf(
                    listOf("aventurina","Jung Min","12","SCRIPT12"),
                    listOf("aventurina","Jung","12","JUNG12"),
                    listOf("aventurina","Min","12","MIN12"),
                    listOf("geneva_simple","Abc","10","ABC10"))) {
                    val font=assets.open("sewtest-fonts/$folder/ltr.svg").use { svg ->
                        InkStitchFont.parse(svg,assets.open("sewtest-fonts/$folder/font.json").bufferedReader().use { it.readText() }) }
                    check(text.filterNot(Char::isWhitespace).all { it.toString() in font.glyphs })
                    export(stem,"$text · ${font.info.name} · ${height}mm satin",
                        InkStitchLettering.render(font,text,height.toDouble(),thread))
                }
                for ((stem,path) in listOf("SQUARE15" to "M-75 -75 H75 V75 H-75 Z",
                    "CIRCLE15" to "M-75 0 A75 75 0 1 0 75 0 A75 75 0 1 0 -75 0 Z")) {
                    val groups=DefaultTatami.stitchGroups(SvgPath.polygons(path,tolerance=.1),thread)
                    export(stem,"$stem · 15mm source-default tatami",
                        EmbPatternAdapter.toEmbPattern(StitchPlanBuilder.fromGroups(groups)))
                }
                val settings=LetteringSettings("정민","noto-sans-kr",15.0,0.0,LetteringAlignment.CENTER,
                    false,LetteringCurve.STRAIGHT,0.0,stitchStyle=StitchStyle.FILL)
                val result=renderLettering(this@S23SewTestActivity,settings,thread,HoopSpec.H100)
                check(!result.unsupported && !result.outsideHoop)
                val korean=result.pattern
                export("KOREAN15","정민 · Noto Sans KR · 15mm TTF fill",korean)
                File(output,"manifest.json").writeText(JSONObject().put("cases",records)
                    .put("personal_designs",false).put("thread","single black 40wt")
                    .put("font_notice","Aventurina: source-only sewout trial; excluded from redistributable app catalog (conditional original-font permission). Geneva Simple: catalog CC BY-SA 2.5; Noto Sans KR: OFL-1.1.").toString(2))
                File(output,"DONE").writeText("7 PES ready")
            } catch(error:Throwable) { File(output,"ERROR.txt").writeText(error.stackTraceToString()) }
        }.start()
    }
}
