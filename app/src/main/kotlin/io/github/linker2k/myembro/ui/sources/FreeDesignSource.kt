// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.sources

import java.net.URI
import java.time.LocalDate
import kotlinx.serialization.json.*

data class SourceText(val ko: String, val en: String) {
    fun localized(language: String): String = if (language == "ko") ko else en
}

data class FreeDesignSource(
    val id: String,
    val name: String,
    val description: SourceText,
    val url: String,
    val formats: List<String>,
    val includesPes: Boolean,
    val conditions: SourceText,
    val commercialUse: String,
    val registration: String,
    val languages: List<String>,
    val verifiedOn: String,
    val termsUrls: List<String>,
)

/** This is an offline link directory. It never fetches designs or sends user data. */
object FreeDesignSources {
    const val ASSET = "sources/free_design_sources.json"

    fun parse(text: String): List<FreeDesignSource> {
        val root = Json.parseToJsonElement(text).jsonObject
        require(root.getValue("version").jsonPrimitive.int == 1)
        val result = root.getValue("sources").jsonArray.map { element ->
            val obj = element.jsonObject
            fun string(key: String) = obj.getValue(key).jsonPrimitive.content
            fun strings(key: String) = obj.getValue(key).jsonArray.map { it.jsonPrimitive.content }
            fun localized(key: String): SourceText = obj.getValue(key).jsonObject.let {
                SourceText(it.getValue("ko").jsonPrimitive.content, it.getValue("en").jsonPrimitive.content)
            }
            FreeDesignSource(string("id"), string("name"), localized("description"), string("url"),
                strings("formats"), obj.getValue("includesPes").jsonPrimitive.boolean,
                localized("conditions"), string("commercialUse"), string("registration"),
                strings("languages"), string("verifiedOn"), strings("termsUrls"))
        }
        require(result.isNotEmpty() && result.map { it.id }.distinct().size == result.size)
        for (source in result) {
            require(source.id.isNotBlank() && source.name.isNotBlank() && source.formats.isNotEmpty() && source.languages.isNotEmpty())
            require(source.includesPes == source.formats.contains("PES"))
            require(source.registration in setOf("required", "not_required"))
            require(source.commercialUse in setOf("not_allowed", "finished_items_limited"))
            require(source.termsUrls.isNotEmpty())
            for (url in listOf(source.url) + source.termsUrls) {
                val uri = URI(url)
                require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null)
            }
            for (textValue in listOf(source.description, source.conditions)) {
                require(textValue.ko.isNotBlank() && textValue.en.isNotBlank())
            }
            LocalDate.parse(source.verifiedOn)
        }
        return result
    }
}
