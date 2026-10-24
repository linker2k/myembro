// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.lettering

import android.app.Instrumentation
import android.os.Bundle
import android.os.Build
import android.graphics.Paint
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.engine.lettering.*
import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.SewingOptions
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.lettering.AndroidGlyphOutlines
import io.github.linker2k.myembro.lettering.AndroidCenterlineGlyph
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

/** Explicit development-only capture. Install/run on emulator-5556 only. */
class FontSnapshotInstrumentation:Instrumentation() {
    override fun onCreate(arguments:Bundle?) { super.onCreate(arguments);start() }
    override fun onStart() {
        try {
            val out=File(targetContext.filesDir,"font-snapshots").apply { mkdirs() }
            val sample=" 자수봄날정민안녕H?ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".codePoints().toArray().distinct()
            for(font in bundledLetteringFonts) for(variant in listOf("outline","satin")) {
                val resource=if(font.id=="noto-sans-kr" && variant=="satin")R.font.myembro_sans else font.resource
                val face=targetContext.resources.getFont(resource)
                val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface=face;textSize=1000f }
                fun contours(character:String)=AndroidGlyphOutlines.extract(character,face).map { path->
                    path.map { (x,y)->x+paint.measureText(character).toDouble()/2 to y }
                }
                val ref=contours(if(paint.hasGlyph("자"))"자" else "H").flatten().let { points->
                    if(points.isEmpty())1000.0 else (points.maxOf { it.second }-points.minOf { it.second }).coerceAtLeast(1.0)
                }
                val glyphs=sample.associate { code->String(Character.toChars(code)).let { c-> c to PreparedGlyph(paint.measureText(c).toDouble(),contours(c)) } }
                val rasters=glyphs.keys.associateWith { AndroidCenterlineGlyph.rasterize(it,face) }
                val digest=MessageDigest.getInstance("SHA-256").digest(targetContext.resources.openRawResource(font.resource).use { it.readBytes() }).joinToString("") { "%02x".format(it.toInt() and 255) }
                val snapshot=TtfFontSnapshot(buildJsonObject {
                    put("fontId",font.id);put("variant",variant);put("fontSha256",digest);put("sdkInt",Build.VERSION.SDK_INT)
                    put("androidFingerprint",Build.FINGERPRINT);put("extractor","glyph-extractor-v3; centerline128-pad8-v1")
                    put("face",if(resource==R.font.myembro_sans)"myembro_sans.xml wght=400" else "bundled TTF default")
                    put("license","OFL-1.1")
                },ResolvedTextFont.OutlineSnapshot(ref,glyphs,sample.filter { paint.hasGlyph(String(Character.toChars(it))) }.toSet()),rasters)
                val text=TtfFontSnapshotCodec.encode(snapshot)
                check(TtfFontSnapshotCodec.decode(text).outline==snapshot.outline)
                GZIPOutputStream(File(out,"${font.id}-$variant.json.gz").outputStream()).use { it.write(text.toByteArray(Charsets.UTF_8)) }
                File(out,"${font.id}-$variant-size.txt").writeText("glyphs=${glyphs.size} json_bytes=${text.toByteArray().size}\n")
            }
            val cases=buildJsonArray {
                for(id in listOf("jua","noto-sans-kr")) for(text in listOf("정민","안녕","Ab12")) {
                    val height=15.0
                    val state=kotlinx.coroutines.runBlocking { automaticLetteringState(targetContext,id,text,height) }
                    val settings=LetteringSettings(text,id,height,1.0,LetteringAlignment.CENTER,false,LetteringCurve.STRAIGHT,0.0,
                        stitchStyle=state.style)
                    val result=renderLettering(targetContext,settings,EmbThread(0xff000000.toInt(),"Black 40wt"),HoopSpec.H100,SewingOptions())
                    val name="$id-${if(text=="Ab12")"latin" else text}.pes"
                    File(out,name).writeBytes(PesWriter.write(result.pattern))
                    File(out,name.removeSuffix(".pes")+".myembro").writeText(Project(objects=listOf(result.textObject)).toJson())
                    add(buildJsonObject { put("file",name);put("fontId",id);put("text",text);put("heightMm",height);put("style",checkNotNull(state.style).name) })
                }
            }
            File(out,"cases.json").writeText(cases.toString())
            File(out,"CAPTURE_OK").writeText("PASS 3 bundled OFL fonts; 6 variants; ${sample.size} glyphs each; 6 actual app PES cases\n")
            finish(-1,Bundle().apply { putString("result","PASS files/font-snapshots") })
        } catch(error:Throwable) { finish(0,Bundle().apply { putString("error",error.stackTraceToString()) }) }
    }
}
