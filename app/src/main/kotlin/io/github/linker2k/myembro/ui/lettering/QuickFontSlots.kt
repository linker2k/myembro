// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.lettering

import android.content.Context

/** A font the lettering sheet can offer in a quick slot. */
internal data class QuickFont(
    val id: String,
    /** Verified Ink/Stitch embroidery font (Latin glyph sets only). */
    val embroidery: Boolean,
    /** Has Hangul glyphs. */
    val hangul: Boolean,
    /** script, serif, sans, display; null when unknown. */
    val theme: String?,
    /** Whether every non-space character of the current text has a glyph. */
    val covers: (String) -> Boolean,
)

/**
 * The three quick slots: [current] first, then the most recent other font (so a library pick can be
 * compared with the one before), then a same-theme neighbour of the current font. Empty slots take
 * fonts by language: embroidery fonts for Latin text, Hangul TTFs for Hangul text. Only fonts that
 * cover the text are added; the current and recent fonts stay even when they do not (shown dimmed).
 */
internal fun quickFontSlots(current: String, recent: List<String>, fonts: List<QuickFont>, text: String): List<String> {
    val byId = fonts.associateBy { it.id }
    val slots = mutableListOf(current)
    recent.firstOrNull { it != current && it in byId }?.let { slots += it }
    val hangulText = text.any { it in '가'..'힣' || it in 'ㄱ'..'ㆎ' }
    // Latin text: verified embroidery fonts first; Hangul text: Hangul TTFs first.
    val preferred = fonts.filter { it.covers(text) }
        .sortedByDescending { if (hangulText) it.hangul else it.embroidery }
    val theme = byId[current]?.theme
    if (theme != null) preferred.firstOrNull { it.theme == theme && it.id !in slots }?.let { slots += it.id }
    for (f in preferred) { if (slots.size >= 3) break; if (f.id !in slots) slots += f.id }
    return slots.take(3)
}

/**
 * First-open default (no recent fonts): Latin text starts in a verified embroidery font that is
 * installed — bundled or downloaded ([installedEmbroidery]) — and covers it, script first; otherwise, and for
 * Hangul text, [fallback] (고운바탕).
 */
internal fun defaultLetteringFont(recent: List<String>, text: String, installedEmbroidery: List<QuickFont>,
    fallback: String = "gowun-batang"): String {
    if (recent.isNotEmpty() || text.any { it in '\uAC00'..'\uD7A3' || it in '\u3131'..'\u318E' }) return fallback
    // A script font first: names are the main use (bundled sacramarif / chicken_scratch).
    return installedEmbroidery.filter { it.embroidery && it.covers(text) }.sortedByDescending { it.theme == "script" }
        .firstOrNull()?.id ?: fallback
}

/** Recently used lettering fonts, newest first, kept in the app's preferences. */
internal class RecentLetteringFonts(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("lettering", Context.MODE_PRIVATE)

    fun list(): List<String> = prefs.getString(KEY, "").orEmpty().split('\n').filter { it.isNotBlank() }

    fun use(id: String) {
        val next = (listOf(id) + list().filter { it != id }).take(MAX)
        prefs.edit().putString(KEY, next.joinToString("\n")).apply()
    }

    private companion object {
        const val KEY = "recentFonts"
        const val MAX = 8
    }
}
