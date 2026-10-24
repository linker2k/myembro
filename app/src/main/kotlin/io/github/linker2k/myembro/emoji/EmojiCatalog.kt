// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.emoji

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** One Fluent Emoji (Flat) picture: pinned path + sha256, from tools/emoji_catalog.py. */
data class CatalogEmoji(
    val id: String, val name: String, val glyph: String, val group: String, val keywords: List<String>,
    val path: String, val sha256: String, val size: Long, val bundled: Boolean,
)

/** The bundled catalog asset: source commit, raw base URL and every supported emoji. */
class EmojiCatalog(val commit: String, val rawBase: String, val emoji: List<CatalogEmoji>) {
    /** Pinned https URL of [e]: raw base at the catalog commit + URL-encoded repository path. */
    fun url(e: CatalogEmoji): String = rawBase + e.path.split('/').joinToString("/") { segment ->
        java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
    }

    companion object {
        const val ASSET = "emoji/fluent-catalog.json"
        const val BUNDLED = "emoji/bundled"

        fun parse(text: String): EmojiCatalog {
            val root = Json.parseToJsonElement(text).jsonObject
            val commit = root.getValue("commit").jsonPrimitive.content
            val rawBase = root.getValue("rawBase").jsonPrimitive.content
            require(rawBase.startsWith("https://") && commit in rawBase) { "Emoji catalog must pin its commit" }
            return EmojiCatalog(commit, rawBase, root.getValue("emoji").jsonArray.map { item ->
                val o = item.jsonObject
                fun s(name: String) = o.getValue(name).jsonPrimitive.content
                CatalogEmoji(s("id"), s("name"), s("glyph"), s("group"), o.getValue("keywords").jsonArray.map { it.jsonPrimitive.content },
                    s("path"), s("sha256"), o.getValue("size").jsonPrimitive.long, o.getValue("bundled").jsonPrimitive.boolean)
            })
        }
    }
}
