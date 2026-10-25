// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.agent

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.lettering.*
import io.github.linker2k.myembro.engine.sewout.SewoutMachines
import kotlinx.serialization.json.*

data class AgentSettings(val machineId:String=SewoutMachines.SE400.id,val machineName:String=SewoutMachines.SE400.name,
    val defaultFrameId:String="H100",val defaultFrame:HoopSpec=HoopSpec.H100)
fun interface SettingsResolver { fun read():AgentSettings }
data class FontDescriptor(val id:String,val displayName:String,val kind:String,val codePoints:Set<Int>?,
    val tags:Set<String> = emptySet(),val installed:Boolean=true)
fun fontDescriptor(id:String,font:ResolvedTextFont,displayName:String=id,tags:Set<String> = emptySet()):FontDescriptor {
    val codes=when(font) {
        is ResolvedTextFont.Hershey -> font.font.glyphs.keys.map { it.code }.toSet()
        is ResolvedTextFont.InkStitch -> font.font.glyphs.keys.filter { it.codePointCount(0,it.length)==1 }.map { it.codePointAt(0) }.toSet()
        is ResolvedTextFont.SingleLineSvg -> font.font.glyphs.keys.filter { it.codePointCount(0,it.length)==1 }.map { it.codePointAt(0) }.toSet()
        is ResolvedTextFont.OutlineSnapshot -> font.supportedCodes
    }
    return FontDescriptor(id,displayName,when(font) { is ResolvedTextFont.InkStitch->"INKSTITCH";is ResolvedTextFont.Hershey->"HERSHEY";is ResolvedTextFont.SingleLineSvg->"SINGLE_LINE_SVG";else->"OUTLINE" },codes,tags)
}
internal fun fontList(resolver:FontResolver,limits:LetteringHeightLimits,args:JsonObject):JsonObject {
    val text=normalizedLetteringText(args.string("text"));val filters=args.getValue("filters").jsonArray.map { it.jsonPrimitive.content }.toSet()
    val rows=resolver.descriptors().mapNotNull { d ->
        if(args.getValue("installedOnly").jsonPrimitive.boolean && !d.installed)return@mapNotNull null
        val single=d.kind in setOf("HERSHEY","SINGLE_LINE_SVG")
        if("OUTLINE" in filters && d.kind!="OUTLINE" || "SINGLE_LINE" in filters && !single || "SCRIPT" in filters && "SCRIPT" !in d.tags)return@mapNotNull null
        val f=if(d.installed) resolver.resolveText(d.id,text) else null
        val codes=text.codePoints().toArray().filter { !Character.isWhitespace(it) }
        if(text.isNotBlank() && (f==null || codes.any { !f.supports(it) }))return@mapNotNull null
        if("KOREAN" in filters && "KOREAN" !in d.tags && d.codePoints?.any { Character.UnicodeScript.of(it)==Character.UnicodeScript.HANGUL }!=true)return@mapNotNull null
        if("LATIN" in filters && "LATIN" !in d.tags && d.codePoints?.any { Character.UnicodeScript.of(it)==Character.UnicodeScript.LATIN }!=true)return@mapNotNull null
        val ink=f?.let { resolver.inkRange(d.id,it) }
        val ranges=if(f!=null) automaticLetteringState(d.displayName,d.id,resolver.revision(d.id).orEmpty(),text,
            io.github.linker2k.myembro.engine.edit.TextObject(id=d.id).heightMm,
            if(f is ResolvedTextFont.OutlineSnapshot) LetteringGeometry.OUTLINE else LetteringGeometry.SINGLE_LINE,
            limits,ink).heightRanges else emptyList()

        buildJsonObject {
            put("id",d.id);put("fontId",d.id);put("displayName",d.displayName);put("kind",d.kind);put("installed",d.installed)
            put("fontRevision",resolver.revision(d.id)?.let(::JsonPrimitive) ?: JsonNull)
            put("coverage",buildJsonObject {
                put("status",if(d.codePoints==null)"unconfirmed" else "known");put("scope","font-source")
                put("count",d.codePoints?.size?.let(::JsonPrimitive) ?: JsonNull)
                put("ranges",JsonArray(codeRanges(d.codePoints.orEmpty()).map(::JsonPrimitive)))
            })
            put("heightRanges",JsonArray(ranges.map { r->buildJsonObject { put("minMm",r.minMm);put("maxMm",r.maxMm) } }))
            put("heightPolicy",if(ranges.isEmpty())"unconfirmed minimum; existing running/centerline policy applies" else "existing validated policy")
            put("minHeightMm",ranges.minOfOrNull { it.minMm }?.let(::JsonPrimitive) ?: JsonNull)
            put("maxHeightMm",ranges.maxOfOrNull { it.maxMm } ?: LetteringHeightLimits.MAX_MM)
        }
    }
    return buildJsonObject { put("ok",true);put("fonts",JsonArray(rows));put("scope","host font resolver") }
}
private fun codeRanges(codes:Set<Int>):List<String> {
    val result=mutableListOf<String>();var start:Int?=null;var end=0
    fun hex(c:Int)="U+"+c.toString(16).uppercase().padStart(4,'0')
    fun flush() { val s=start ?: return;result+=if(s==end)hex(s) else hex(s)+"–"+hex(end) }
    for(c in codes.sorted()) { if(start==null) { start=c;end=c } else if(c==end+1)end=c else { flush();start=c;end=c } }
    flush();return result
}
