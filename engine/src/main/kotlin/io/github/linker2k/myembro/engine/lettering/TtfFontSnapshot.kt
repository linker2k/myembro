// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import java.util.Base64
import kotlinx.serialization.json.*

/** Finite, revisioned glyph subset captured by Android, not a TTF parser or a fallback font. */
data class TtfFontSnapshot(val provenance:JsonObject,val outline:ResolvedTextFont.OutlineSnapshot,
    val rasters:Map<String,BinaryGlyphRaster>)

/** UTF-8 JSON numbers round-trip Double coordinates exactly. Hosts may gzip the document. */
object TtfFontSnapshotCodec {
    fun encode(snapshot:TtfFontSnapshot):String=buildJsonObject {
        put("schemaVersion",1);put("provenance",snapshot.provenance);put("reference",snapshot.outline.reference)
        put("glyphs",buildJsonArray { snapshot.outline.glyphs.forEach { (character,glyph)->add(buildJsonObject {
            put("character",character);put("advance",glyph.advance)
            put("supported",character.codePointAt(0) in snapshot.outline.supportedCodes)
            put("contours",JsonArray(glyph.paths.map { path->JsonArray(path.map { (x,y)->buildJsonArray { add(x);add(y) } }) }))
            val raster=snapshot.rasters.getValue(character)
            put("raster",buildJsonObject {
                put("width",raster.width);put("height",raster.height)
                put("pixels",Base64.getEncoder().encodeToString(raster.pixels))
                with(raster.transform) { put("left",left);put("top",top);put("paddingX",paddingX);put("paddingY",paddingY)
                    put("sourceSize",sourceSize);put("rasterSize",rasterSize) }
            })
        }) } })
    }.toString()

    fun decode(text:String):TtfFontSnapshot {
        val root=Json.parseToJsonElement(text).jsonObject
        require(root.getValue("schemaVersion").jsonPrimitive.int==1)
        val glyphs=linkedMapOf<String,PreparedGlyph>();val rasters=linkedMapOf<String,BinaryGlyphRaster>();val supported=linkedSetOf<Int>()
        root.getValue("glyphs").jsonArray.forEach { item->
            val g=item.jsonObject;val character=g.getValue("character").jsonPrimitive.content
            require(character.codePointCount(0,character.length)==1 && character !in glyphs)
            glyphs[character]=PreparedGlyph(g.getValue("advance").jsonPrimitive.double,g.getValue("contours").jsonArray.map { path->
                path.jsonArray.map { point->point.jsonArray.let { it[0].jsonPrimitive.double to it[1].jsonPrimitive.double } }
            })
            if(g.getValue("supported").jsonPrimitive.boolean)supported+=character.codePointAt(0)
            val r=g.getValue("raster").jsonObject
            fun n(key:String)=r.getValue(key).jsonPrimitive.int
            rasters[character]=BinaryGlyphRaster(n("width"),n("height"),Base64.getDecoder().decode(r.getValue("pixels").jsonPrimitive.content),
                RasterToSource(n("left"),n("top"),n("paddingX"),n("paddingY"),n("sourceSize"),n("rasterSize")))
        }
        return TtfFontSnapshot(root.getValue("provenance").jsonObject,
            ResolvedTextFont.OutlineSnapshot(root.getValue("reference").jsonPrimitive.double,glyphs,supported),rasters)
    }
}
