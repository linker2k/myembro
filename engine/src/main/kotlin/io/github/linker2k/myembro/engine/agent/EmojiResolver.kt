// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.agent

import io.github.linker2k.myembro.engine.emoji.*
import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.geometry.pictureWidthRange
import io.github.linker2k.myembro.engine.inkstitch.svg.VectorArt
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.sewout.*
import kotlinx.serialization.json.*
import java.security.MessageDigest

/** Local-only interface. Implementations must never fetch/download in these methods. */
interface EmojiResolver {
    val catalog: EmojiCatalog
    fun isAvailable(emoji: CatalogEmoji): Boolean
    fun localSvg(emoji: CatalogEmoji): ByteArray?
}
class BundledEmojiResolver : EmojiResolver {
    override val catalog by lazy { EmojiCatalog.parse(checkNotNull(javaClass.getResourceAsStream("/emoji/fluent-catalog.json"))
        .bufferedReader().use { it.readText() }) }
    override fun isAvailable(emoji: CatalogEmoji) = emoji.bundled
    override fun localSvg(emoji: CatalogEmoji): ByteArray? = if (!emoji.bundled) null else
        javaClass.getResourceAsStream("/emoji/bundled/${emoji.id}.svg")?.use { it.readBytes() }
}

internal class EmojiToolRejection(val code: String, message: String,
    val details: JsonObject = buildJsonObject {}) : IllegalArgumentException(message)
internal data class PreparedEmoji(val id: String, val name: String, val pattern: EmbPattern,
    val widthMm: Double, val range: ClosedFloatingPointRange<Double>, val verdict: SewoutVerdict,
    val shape: ShapeObject? = null) {
    fun objectFor(id: String, name: String): DesignObject {
        val editable = shape ?: return StitchObject(id, name, pattern) // Redwork/centerline remain stitches.
        val obj = if (name == editable.name) editable.withId(id) else ShapeObject.fromArt(id, name, editable.sourceArt, editable.widthMm)
        // StitchObject used to re-center actual sewn bounds at import. Preserve that placement exactly.
        val b = obj.localPattern().bounds ?: return obj
        return obj.withTransform(Transform(x = -((b.minX.toLong()+b.maxX)/2).toDouble(), y = -((b.minY.toLong()+b.maxY)/2).toDouble()))
    }
}

/** The existing EmojiViewModel pipeline: verified SVG -> VectorArt defaults -> checked PES. */
internal class EmojiPreparation(private val resolver: EmojiResolver) {
    fun prepare(id: String, hoop: HoopSpec, requestedMm: Double): PreparedEmoji {
        val emoji=resolver.catalog.emoji.firstOrNull { it.id==id }
            ?: throw EmojiToolRejection("EMOJI_NOT_FOUND","Unknown emoji ID")
        val local=try { resolver.localSvg(emoji) } catch (_: java.io.IOException) {
            throw EmojiToolRejection("EMOJI_READ_FAILED","Cannot read or verify the installed emoji")
        }
        val bytes=local ?: throw EmojiToolRejection("DOWNLOAD_REQUIRED","Emoji is not installed; select/download it in the app",buildJsonObject {
            put("emojiId",id);put("downloadRequired",true)
        })
        if(sha256(bytes)!=emoji.sha256) throw EmojiToolRejection("ASSET_INTEGRITY_ERROR","Emoji checksum mismatch")
        val art=VectorArt.parseSvg(bytes.toString(Charsets.UTF_8))
        val aspect=VectorArt.aspectRatio(art)
        require(aspect.isFinite() && aspect>0) { "Emoji has no painted area" }
        val range=pictureWidthRange(hoop,aspect)
        if(!requestedMm.isFinite() || requestedMm !in range) throw EmojiToolRejection("WIDTH_OUT_OF_RANGE","Width is outside the existing picture policy",buildJsonObject {
            put("minWidthMm",range.start);put("maxWidthMm",range.endInclusive)
        })
        val width=Math.round(requestedMm*10)/10.0 // Same slider precision as EmojiViewModel.changeSize.
        val shape=ShapeObject.fromArt("prepared",emoji.name,art,width)
        val pattern=shape.localPattern()
        val pes=try { PesWriter.writeV1(pattern,hoop) } catch(e:IllegalArgumentException) {
            throw EmojiToolRejection("SEWOUT_BLOCKED",e.message ?: "Writer blocked emoji")
        }
        val report=SewoutCheck.checkPes(pes,options=SewoutOptions(hoop=hoop,origin=DesignOrigin.APP_GENERATED))
        if(!report.analysisComplete || report.hasBlockingIssues) throw EmojiToolRejection("SEWOUT_BLOCKED","Emoji did not pass the existing generated-design gate")
        val token=sha256("${resolver.catalog.commit}:$id:${emoji.sha256}:$width:${hoop.width}:${hoop.height}".toByteArray())
        return PreparedEmoji("emoji-$token",emoji.name,pattern,width,range,report.verdict,shape)
    }
}
private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 255) }

/** An AI picture handed over as SVG text: SvgPictureInput's limits and content checks, then the photo
 * screen's SVG path unchanged (VectorArt defaults, width clamped to pictureWidthRange, 0.1mm) and the
 * same generated-design gate as emoji. docs/agent/svg-picture.md. */
internal object SvgPicturePreparation {
    fun prepare(svg: ByteArray, hoop: HoopSpec, requestedMm: Double, name: String): PreparedEmoji {
        val art=try { io.github.linker2k.myembro.engine.inkstitch.svg.SvgPictureInput.parse(svg).art }
            catch(e:Exception) { throw EmojiToolRejection("SVG_NOT_SUPPORTED","This SVG cannot be used: ${e.message ?: "unsupported"}. Send a PNG instead.") }
        val range=pictureWidthRange(hoop,VectorArt.aspectRatio(art))
        val width=Math.round(requestedMm.coerceIn(range)*10)/10.0
        val shape=try { ShapeObject.fromArt("prepared",name,art,width) } catch(e:IllegalArgumentException) {
            throw EmojiToolRejection("SVG_NOT_SUPPORTED","This SVG cannot be used: ${e.message ?: "unsupported"}. Send a PNG instead.")
        }
        val pattern=shape.localPattern()
        val pes=try { PesWriter.writeV1(pattern,hoop) } catch(e:IllegalArgumentException) {
            throw EmojiToolRejection("SEWOUT_BLOCKED",e.message ?: "Writer blocked the picture")
        }
        val report=SewoutCheck.checkPes(pes,options=SewoutOptions(hoop=hoop,origin=DesignOrigin.APP_GENERATED))
        if(!report.analysisComplete || report.hasBlockingIssues) throw EmojiToolRejection("SEWOUT_BLOCKED","Picture did not pass the existing generated-design gate")
        return PreparedEmoji("svg-${java.util.UUID.randomUUID()}",name,pattern,width,range,report.verdict,shape)
    }
}
