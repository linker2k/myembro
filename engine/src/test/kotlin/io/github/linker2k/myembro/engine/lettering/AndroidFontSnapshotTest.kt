// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.edit.*
import java.io.File
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.SewingOptions
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlin.test.*

/** Actual Android-generated inputs and actual app PES; no JVM font parser/rasterizer/substitute. */
class AndroidFontSnapshotTest {
    private fun bytes(name:String)=checkNotNull(javaClass.getResourceAsStream("/font-snapshots/$name")).use { it.readBytes() }
    private fun snapshot(id:String,variant:String)=TtfFontSnapshotCodec.decode(
        GZIPInputStream(bytes("$id-$variant.json.gz").inputStream()).bufferedReader(Charsets.UTF_8).use { it.readText() })

    @Test fun pinnedAndroidSnapshotsHaveExactChecksumsAndSubsetCoverage() {
        val manifest=Json.parseToJsonElement(bytes("manifest.json").decodeToString()).jsonObject
        manifest.getValue("files").jsonObject.forEach { (name,record)->
            val data=bytes(name)
            assertEquals(record.jsonObject.getValue("bytes").jsonPrimitive.int,data.size)
            assertEquals(record.jsonObject.getValue("sha256").jsonPrimitive.content,
                MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 255) })
        }
        for(id in listOf("gowun-batang","jua","noto-sans-kr")) for(variant in listOf("outline","satin")) {
            val s=snapshot(id,variant)
            assertTrue(s.outline.glyphs.keys.containsAll("자수봄날정민안녕ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".map(Char::toString)))
            val replay=TtfFontSnapshotCodec.decode(TtfFontSnapshotCodec.encode(s))
            assertEquals(s.outline,replay.outline)
            s.rasters.forEach { (c,r)->assertContentEquals(r.pixels,replay.rasters.getValue(c).pixels);assertEquals(r.transform,replay.rasters.getValue(c).transform) }
            assertFailsWith<NoSuchElementException> { s.outline.glyph("힣") }
        }
    }

    @Test fun jvmPreparedObjectsAndSatinPesMatchActualAppForTwoFonts() {
        val cases=Json.parseToJsonElement(bytes("cases.json").decodeToString()).jsonArray
        assertEquals(6,cases.size)
        cases.forEach { value->
            val c=value.jsonObject
            fun field(key:String)=c.getValue(key).jsonPrimitive.content
            val style=StitchStyle.valueOf(field("style"));val text=field("text");val id=field("fontId")
            val s=snapshot(id,if(style==StitchStyle.SATIN)"satin" else "outline")
            val height=c.getValue("heightMm").jsonPrimitive.double
            val request=TextPreparationRequest(text,id,height,1.0,LetteringAlignment.CENTER,9.0,
                EmbThread(0xff000000.toInt(),"Black 40wt"),stitchStyle=style)
            val centerline=if(style==StitchStyle.SATIN) {
                val codes=normalizedLetteringText(text).codePoints().toArray()
                val components=mutableListOf<Int>();val widths=mutableMapOf<Int,Double>()
                val lines=arrayListOf(mutableListOf<LayoutGlyph>())
                codes.forEachIndexed { index,code->
                    if(code=='\n'.code)lines+=mutableListOf<LayoutGlyph>() else {
                        val char=String(Character.toChars(code));val glyph=s.outline.glyphs.getValue(char)
                        val paths=if(Character.isWhitespace(code))emptyList() else {
                            val traced=CenterlineGlyph.extract(s.rasters.getValue(char));components+=traced.components
                            widths[index]=HangulSatin.measuredWidthSource(glyph.paths,s.outline.reference)
                            traced.paths
                        }
                        lines.last()+=LayoutGlyph(index,glyph.advance,paths)
                    }
                }
                val layout=layoutGlyphs(lines,s.outline.reference,height,1.0,LetteringAlignment.CENTER,9.0)
                CenterlineSatinData(layout.contours,layout.characters,components,widths)
            } else null
            val prepared=prepareText(request.copy(centerlineSatin=centerline),s.outline)
            val objectJson=Project(objects=listOf(prepared.textObject)).toJson()
            val expectedJson=GZIPInputStream(bytes(field("file").removeSuffix(".pes")+".myembro.gz").inputStream()).bufferedReader().use { it.readText() }
            check(objectJson==expectedJson) { "Android/JVM TextObject JSON mismatch $id $text at ${objectJson.zip(expectedJson).indexOfFirst { it.first!=it.second }}" }
            if(style==StitchStyle.FILL && System.getenv("MYEMBRO_SNAPSHOT_FILL_PARITY")!="1") {
                println("ANDROID_JVM_OBJECT_EXACT $id text=$text style=$style; FILL PES parity unconfirmed (opt-in diagnostic)")
                return@forEach
            }
            val raw=prepared.localPattern()
            val pattern=if(raw.metadata[GeneratedPatternProcessor.FINISHED_KEY]=="v1")
                GeneratedPatternProcessor.finish(raw,options=SewingOptions()) else raw
            val actual=PesWriter.write(pattern);val expected=bytes(field("file"))
            if(!expected.contentEquals(actual)) {
                val out=File("/tmp/myembro-font-snapshot-diff").apply { mkdirs() }
                File(out,field("file")).writeBytes(actual)
                println("ANDROID_JVM_PES_DIFFERENCE $id $text $style object_json=exact expected_bytes=${expected.size} actual_bytes=${actual.size}")
            }
            assertTrue(expected.contentEquals(actual),"Actual Android vs JVM $id $text $style expected_bytes=${expected.size} actual_bytes=${actual.size}")
            println("ANDROID_JVM_PES_EXACT $id text=$text height_mm=$height style=$style bytes=${actual.size}")
        }
    }
}
