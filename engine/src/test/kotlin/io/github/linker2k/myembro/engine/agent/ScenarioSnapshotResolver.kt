// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.agent

import io.github.linker2k.myembro.engine.lettering.*
import java.util.zip.GZIPInputStream

/** Licensed Android captures from agent/font-snapshots@4413c6a; no AWT/substitute outlines. */
internal class ScenarioSnapshotResolver : FontResolver {
    private val snapshot by lazy {
        TtfFontSnapshotCodec.decode(GZIPInputStream(checkNotNull(javaClass.getResourceAsStream(
            "/font-snapshots/noto-sans-kr-satin.json.gz"))).bufferedReader().use { it.readText() })
    }
    override fun resolve(fontId: String)=if(fontId=="noto-sans-kr") snapshot.outline else null
    override fun revision(fontId: String)=if(fontId=="noto-sans-kr")
        snapshot.provenance.getValue("fontSha256").toString().trim('"')+":glyph-extractor-v3" else null
    override fun prepare(request: TextPreparationRequest,font: ResolvedTextFont,policy: TextPreparationPolicy): io.github.linker2k.myembro.engine.edit.TextObject {
        // Same chain as AndroidFontSnapshotTest and Android centerline preparation, unchanged.
        val components=mutableListOf<Int>();val widths=mutableMapOf<Int,Double>()
        val lines=arrayListOf(mutableListOf<LayoutGlyph>())
        normalizedLetteringText(request.text).codePoints().toArray().forEachIndexed { index,code ->
            if(code=='\n'.code) lines+=mutableListOf<LayoutGlyph>() else {
                val c=String(Character.toChars(code));val glyph=snapshot.outline.glyphs.getValue(c)
                val paths=if(Character.isWhitespace(code)) emptyList() else {
                    val traced=CenterlineGlyph.extract(snapshot.rasters.getValue(c));components+=traced.components
                    widths[index]=HangulSatin.measuredWidthSource(glyph.paths,snapshot.outline.reference);traced.paths
                }
                lines.last()+=LayoutGlyph(index,glyph.advance,paths)
            }
        }
        val layout=layoutGlyphs(lines,snapshot.outline.reference,request.heightMm,request.spacingMm,request.alignment,request.lineSpacingMm)
        return prepareText(request.copy(centerlineSatin=CenterlineSatinData(layout.contours,layout.characters,components,widths)),font,policy).textObject
    }
}
