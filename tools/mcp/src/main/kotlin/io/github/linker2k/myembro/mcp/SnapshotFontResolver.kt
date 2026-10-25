// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.mcp

import io.github.linker2k.myembro.engine.agent.FontResolver
import io.github.linker2k.myembro.engine.lettering.*
import java.util.zip.GZIPInputStream

/** Replays licensed Android capture data only. No java.awt, font substitution or new geometry rules. */
class SnapshotFontResolver : FontResolver {
    val ids=listOf("jua","noto-sans-kr","gowun-batang")
    private val cache=mutableMapOf<String,TtfFontSnapshot>()
    private fun snapshot(id:String,variant:String="outline"):TtfFontSnapshot? {
        if(id !in ids) return null
        return cache.getOrPut("$id-$variant") {
            val stream=checkNotNull(javaClass.getResourceAsStream("/font-snapshots/$id-$variant.json.gz"))
            TtfFontSnapshotCodec.decode(GZIPInputStream(stream).bufferedReader().use { it.readText() })
        }
    }
    override fun descriptors()=ids.map { id -> io.github.linker2k.myembro.engine.agent.fontDescriptor(id,checkNotNull(resolve(id)),
        when(id) { "jua"->"Jua";"noto-sans-kr"->"Noto Sans KR";else->"Gowun Batang" },setOf("KOREAN","LATIN")) }
    override fun resolve(fontId:String)=snapshot(fontId)?.outline
    override fun revision(fontId:String)=snapshot(fontId)?.provenance?.get("fontSha256")?.toString()?.trim('"')?.plus(":glyph-extractor-v3")
    override fun prepare(request:TextPreparationRequest,font:ResolvedTextFont,policy:TextPreparationPolicy):io.github.linker2k.myembro.engine.edit.TextObject {
        if(request.stitchStyle!=StitchStyle.SATIN || !HangulSatin.applies(request.text)) return prepareText(request,font,policy).textObject
        val s=checkNotNull(snapshot(request.fontId,"satin"))
        val components=mutableListOf<Int>();val widths=mutableMapOf<Int,Double>()
        val lines=arrayListOf(mutableListOf<LayoutGlyph>())
        normalizedLetteringText(request.text).codePoints().toArray().forEachIndexed { index,code ->
            if(code=='\n'.code) lines+=mutableListOf<LayoutGlyph>() else {
                val c=String(Character.toChars(code));val glyph=s.outline.glyphs.getValue(c)
                val paths=if(Character.isWhitespace(code)) emptyList() else {
                    val traced=CenterlineGlyph.extract(s.rasters.getValue(c));components+=traced.components
                    widths[index]=HangulSatin.measuredWidthSource(glyph.paths,s.outline.reference);traced.paths
                }
                lines.last()+=LayoutGlyph(index,glyph.advance,paths)
            }
        }
        val layout=layoutGlyphs(lines,s.outline.reference,request.heightMm,request.spacingMm,request.alignment,request.lineSpacingMm)
        return prepareText(request.copy(centerlineSatin=CenterlineSatinData(layout.contours,layout.characters,components,widths)),s.outline,policy).textObject
    }
}
