// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import kotlinx.serialization.json.*

/** Bounds come from approved evidence, never inferred by FontQuality or font size heuristics. */
data class ValidatedHeightRange(val minMm:Double,val maxMm:Double) {
    init { require(minMm.isFinite() && maxMm.isFinite() && minMm > 0 && maxMm >= minMm) }
    operator fun contains(height:Double)=height.isFinite() && height in minMm..maxMm
    fun clamp(height:Double):Double { require(height.isFinite());return height.coerceIn(minMm,maxMm) }
}
enum class LetteringGeometry { OUTLINE, SINGLE_LINE, SATIN_COLUMNS }
enum class LetteringEvidence { INKSTITCH_FONT_METADATA, PE_DESIGN_CORPUS }

data class ValidatedLetteringProfile(
    val fontId:String,
    /** Content revision including extraction version. A different file never inherits this approval. */
    val fontRevision:String,
    val geometry:LetteringGeometry,
    val style:StitchStyle,
    val heights:ValidatedHeightRange,
    val basis:LetteringEvidence,
    val evidence:String,
    val codePoints:Set<Int>,
    val strokeWidths:ValidatedHeightRange?=null,
) {
    init {
        require(fontId.isNotBlank() && fontRevision.isNotBlank() && evidence.isNotBlank())
        require(style==StitchStyle.RUNNING || style==StitchStyle.SATIN)
        require(style!=StitchStyle.SATIN || geometry==LetteringGeometry.SATIN_COLUMNS && strokeWidths!=null)
        require(codePoints.isNotEmpty() && codePoints.all(Character::isValidCodePoint))
    }
}

/** Automatic selection only among supplied, evidenced combinations. Unknown means unavailable. */
class AutomaticLetteringPolicy(private val profiles:List<ValidatedLetteringProfile>) {
    fun candidates(fontId:String,revision:String,geometry:LetteringGeometry,text:String,
        measuredStrokeWidths:ValidatedHeightRange?=null):List<ValidatedLetteringProfile> {
        val codes=text.codePoints().toArray().filter { !Character.isWhitespace(it) }.toSet()
        if(codes.isEmpty())return emptyList()
        return profiles.filter { profile ->
            profile.fontId==fontId && profile.fontRevision==revision && profile.geometry==geometry &&
                profile.codePoints.containsAll(codes) && (profile.style==StitchStyle.RUNNING ||
                measuredStrokeWidths!=null && profile.strokeWidths!!.let { measuredStrokeWidths.minMm in it && measuredStrokeWidths.maxMm in it })
        }
    }
    fun select(candidates:List<ValidatedLetteringProfile>,heightMm:Double):ValidatedLetteringProfile? =
        candidates.filter { heightMm in it.heights }.sortedBy { if(it.style==StitchStyle.SATIN)0 else 1 }.firstOrNull()

    /** S2 coordinator policy for unconfirmed fonts; this is not a certified size interval. */
    fun method(candidates:List<ValidatedLetteringProfile>,heightMm:Double,
        geometry:LetteringGeometry?=null,measuredStrokeWidths:ValidatedHeightRange?=null):StitchStyle {
        if(heightMm in TatamiLettering.MIN_HEIGHT_MM..TatamiLettering.MAX_HEIGHT_MM &&
            (geometry==LetteringGeometry.OUTLINE || measuredStrokeWidths?.maxMm?.let { it>TatamiLettering.MAX_SATIN_WIDTH_MM }==true))
            return StitchStyle.FILL
        return select(candidates,heightMm)?.style ?: StitchStyle.RUNNING
    }

    companion object {
        val Shared:AutomaticLetteringPolicy by lazy {
            val data=AutomaticLetteringPolicy::class.java.getResourceAsStream("/lettering/validated-ranges.json")
                ?.bufferedReader()?.use { it.readText() }
            if(data==null) AutomaticLetteringPolicy(emptyList()) else decode(data)
        }
        /** A malformed approval file fails closed. Build tests should reject it before distribution. */
        fun decode(data:String):AutomaticLetteringPolicy {
            val root=Json.parseToJsonElement(data).jsonObject
            require(root.getValue("schemaVersion").jsonPrimitive.int==1)
            fun JsonObject.range()=ValidatedHeightRange(getValue("minMm").jsonPrimitive.double,getValue("maxMm").jsonPrimitive.double)
            return AutomaticLetteringPolicy(root.getValue("profiles").jsonArray.map { raw ->
                val p=raw.jsonObject
                fun field(name:String)=p.getValue(name).jsonPrimitive.content
                ValidatedLetteringProfile(field("fontId"),field("fontRevision"),LetteringGeometry.valueOf(field("geometry")),
                    StitchStyle.valueOf(field("style")),p.getValue("heights").jsonObject.range(),LetteringEvidence.valueOf(field("basis")),
                    field("evidence"),p.getValue("codePoints").jsonArray.map { it.jsonPrimitive.int }.toSet(),
                    p["strokeWidths"]?.jsonObject?.range())
            })
        }
    }
}
