// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.agent

import android.content.Context
import io.github.linker2k.myembro.fonts.CatalogFont
import io.github.linker2k.myembro.fonts.FontRepository
import io.github.linker2k.myembro.ui.lettering.LetteringFont
import io.github.linker2k.myembro.ui.lettering.LetteringHeightLimits
import io.github.linker2k.myembro.ui.lettering.bundledLetteringFonts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * What the prompt lists, read from the app's own stores (the same sources as the font library and
 * the built-in design picker): fonts usable now, and the bundled sample designs.
 */
internal object AgentCatalog {
    suspend fun fonts(context: Context): List<AgentFontInfo> = withContext(Dispatchers.IO) {
        val ranges = fillRanges(context)
        val korean = context.resources.configuration.locales[0].language == "ko"
        val repo = FontRepository(context)
        bundledFonts(ranges.mapValues { it.value.second }) { it.displayName(context) } +
            repo.catalog.filter(repo::isInstalled).map { f -> info(f, ranges[f.id]?.takeIf { it.first == f.sha256 }?.second, korean) }
    }

    /**
     * Bundled TTFs with their display name in the current locale ([name], from string resources since the
     * font names were localized) and pinned sweep range by id. Bundled Korean TTFs also carry Latin.
     */
    internal fun bundledFonts(ranges: Map<String, Pair<Double, Double>>, name: (LetteringFont) -> String) = bundledLetteringFonts.map { f ->
        val r = ranges[f.id]
        AgentFontInfo(f.id, name(f), hangul = true, latin = true, r?.first, r?.second ?: LetteringHeightLimits.MAX_MM)
    }

    /** Catalog fonts: Korean UI shows nameKo like the font library; other locales the catalog name. */
    internal fun info(f: CatalogFont, fillRange: Pair<Double, Double>?, korean: Boolean = true): AgentFontInfo {
        val min = if (f.predigitized) f.recommendedMinHeightMm else fillRange?.first ?: f.recommendedMinHeightMm
        val max = (if (f.predigitized) f.recommendedMaxHeightMm else fillRange?.second) ?: LetteringHeightLimits.MAX_MM
        return AgentFontInfo(f.id, (if (korean) f.nameKo else null) ?: f.name, f.korean, f.latin, min, minOf(max, LetteringHeightLimits.MAX_MM))
    }

    /** fontId → (sha256, min..max) from the pinned TTF fill sweep (assets/lettering/ttf-fill-height-ranges.json). */
    private fun fillRanges(context: Context): Map<String, Pair<String, Pair<Double, Double>>> = runCatching {
        val profiles = JSONObject(context.assets.open(LetteringHeightLimits.ASSET).bufferedReader().use { it.readText() }).getJSONArray("profiles")
        (0 until profiles.length()).map(profiles::getJSONObject).associate {
            it.getString("fontId") to (it.getString("fontSha256") to (it.getDouble("minMm") to it.getDouble("maxMm")))
        }
    }.getOrDefault(emptyMap())

    /** Built-in designs exactly as the dispatcher resolves them (AndroidDesignResolver ids such as builtin:<file>). */
    suspend fun designs(context: Context): List<AgentDesignInfo> = withContext(Dispatchers.IO) {
        runCatching { io.github.linker2k.myembro.library.AndroidDesignResolver(context).builtins().map { d ->
            val b = d.pattern.bounds
            AgentDesignInfo(d.id, d.name, (b?.width ?: 0) / 10.0, (b?.height ?: 0) / 10.0)
        } }.getOrDefault(emptyList())
    }
}
