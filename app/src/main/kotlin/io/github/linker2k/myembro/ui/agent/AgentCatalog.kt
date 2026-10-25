// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.agent

import android.content.Context
import io.github.linker2k.myembro.fonts.CatalogFont
import io.github.linker2k.myembro.fonts.FontRepository
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
        val bundled = bundledLetteringFonts.map { f ->
            // Bundled Korean TTFs also carry Latin; their pinned sweep range is keyed by id.
            val r = ranges[f.id]
            AgentFontInfo(f.id, f.name, hangul = true, latin = true, r?.second?.first, r?.second?.second ?: LetteringHeightLimits.MAX_MM)
        }
        val repo = FontRepository(context)
        bundled + repo.catalog.filter(repo::isInstalled).map { f -> info(f, ranges[f.id]?.takeIf { it.first == f.sha256 }?.second) }
    }

    internal fun info(f: CatalogFont, fillRange: Pair<Double, Double>?): AgentFontInfo {
        val min = if (f.predigitized) f.recommendedMinHeightMm else fillRange?.first ?: f.recommendedMinHeightMm
        val max = (if (f.predigitized) f.recommendedMaxHeightMm else fillRange?.second) ?: LetteringHeightLimits.MAX_MM
        return AgentFontInfo(f.id, f.nameKo ?: f.name, f.korean, f.latin, min, minOf(max, LetteringHeightLimits.MAX_MM))
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
