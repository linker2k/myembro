// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.agent

import io.github.linker2k.myembro.engine.lettering.*
import io.github.linker2k.myembro.engine.inkstitch.lettering.InkStitchFont
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Local fonts only. A snapshot adapter may override resolveText to extract the requested glyphs.
 * Unknown fonts return null; no fallback, download or platform types cross this interface. */
interface FontResolver {
    fun descriptors():List<FontDescriptor> = emptyList()
    fun resolve(fontId: String): ResolvedTextFont?
    fun revision(fontId: String): String?
    fun prepare(request: TextPreparationRequest, font: ResolvedTextFont, policy: TextPreparationPolicy): io.github.linker2k.myembro.engine.edit.TextObject =
        prepareText(request,font,policy).textObject
    fun resolveText(fontId: String, text: String): ResolvedTextFont? = resolve(fontId)
    fun inkRange(fontId: String, font: ResolvedTextFont): ClosedFloatingPointRange<Double>? =
        (font as? ResolvedTextFont.InkStitch)?.font?.let { f -> f.minHeightMm?.let { min -> f.maxHeightMm?.let { min..it } } }
}

/** JVM defaults. Inject another resolver for TTF OutlineSnapshot data; no AWT/Android dependency here. */
class BundledFontResolver(private val snapshots: FontResolver? = null) : FontResolver {
    override fun descriptors():List<FontDescriptor> = (IDS+HersheyFont.DEFAULT_ID).map { id ->
        val f=checkNotNull(resolve(id));fontDescriptor(id,f,when(f) {
            is ResolvedTextFont.InkStitch -> f.font.info.name
            is ResolvedTextFont.Hershey -> "Hershey Futural"
            else -> id
        }, if(metadata[id]?.let { entry-> listOf("theme","style").any { entry[it]?.jsonPrimitive?.content=="script" } }==true) setOf("SCRIPT") else emptySet())
    } + snapshots?.descriptors().orEmpty()
    private val metadata by lazy {
        val catalog=Json.parseToJsonElement(checkNotNull(javaClass.getResourceAsStream("/agent/fonts/inkstitch-catalog.json")).bufferedReader().use { it.readText() }).jsonObject
        catalog.getValue("fonts").jsonArray.map { it.jsonObject }.associateBy { it.getValue("id").jsonPrimitive.content }
    }
    private val ranges by lazy {
        metadata.filterKeys { it in IDS }.mapValues { (_,entry)->entry.getValue("recommendedMinHeightMm").jsonPrimitive.double..entry.getValue("recommendedMaxHeightMm").jsonPrimitive.double }
    }
    private val cache = mutableMapOf<String, Pair<ResolvedTextFont,String>>()
    private fun entry(id: String): Pair<ResolvedTextFont,String>? {
        if(id == HersheyFont.DEFAULT_ID) return ResolvedTextFont.Hershey(HersheyFont.DEFAULT) to "bundled-hershey-v1:glyph-extractor-v3"
        cache[id]?.let { return it }
        // Resource lookup is limited to the shipped list, never caller-controlled paths.
        if(id !in IDS) return null
        fun bytes(name: String) = checkNotNull(javaClass.getResourceAsStream("/agent/fonts/$id/$name")).use { it.readBytes() }
        val svg=bytes("ltr.svg");val metadata=bytes("font.json")
        val revision=MessageDigest.getInstance("SHA-256").digest(svg).joinToString("") { "%02x".format(it.toInt() and 255) }
        return (ResolvedTextFont.InkStitch(InkStitchFont.parse(svg.inputStream(),metadata.toString(Charsets.UTF_8))) to (revision+":glyph-extractor-v3")).also { cache[id]=it }
    }
    override fun resolve(fontId:String) = entry(fontId)?.first ?: snapshots?.resolve(fontId)
    override fun resolveText(fontId:String,text:String) = entry(fontId)?.first ?: snapshots?.resolveText(fontId,text)
    override fun revision(fontId:String) = entry(fontId)?.second ?: snapshots?.revision(fontId)
    override fun prepare(request:TextPreparationRequest,font:ResolvedTextFont,policy:TextPreparationPolicy) =
        if(request.fontId in IDS || request.fontId==HersheyFont.DEFAULT_ID) super.prepare(request,font,policy)
        else snapshots?.prepare(request,font,policy) ?: super.prepare(request,font,policy)
    override fun inkRange(fontId:String,font:ResolvedTextFont) = if(fontId in IDS) ranges[fontId] else snapshots?.inkRange(fontId,font)
    companion object { val IDS=setOf("inkstitch-medium-font","inkstitch-sacramarif","inkstitch-chicken-little","inkstitch-bathaus-fi","inkstitch-chicken-scratch") }
}

internal class TextToolRejection(val code:String, message:String): IllegalArgumentException(message)
