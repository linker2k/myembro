// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.fat12

import java.util.Locale

/** VFAT long filename rules from the Microsoft FAT Specification (fatgen103), section 7.
 * Allowed: any Unicode except control characters (< 0x20) and \ / : * ? " < > |.
 * At most 255 UTF-16 code units. Trailing periods/spaces are ignored by the spec, so they are
 * never stored (trimmed by [sanitize], rejected by [isValid]).
 * Real-machine basis: 2026-11-01, a Hangul/space/punctuation long name copied by a PC was listed by SE400.
 */
object LongNames {
    const val MAX_LENGTH = 255
    const val CHARS_PER_SLOT = 13
    const val FORBIDDEN = "\\/:*?\"<>|"

    fun isAllowedChar(c: Char): Boolean = c.code >= 0x20 && c !in FORBIDDEN

    fun isValid(name: String): Boolean = name.isNotEmpty() && name.length <= MAX_LENGTH &&
        name.all(::isAllowedChar) && !name.endsWith('.') && !name.endsWith(' ') && name.isNotBlank()

    /** Number of 32-byte LFN directory slots; 13 UTF-16 units per slot. */
    fun slotsFor(name: String): Int = (name.length + CHARS_PER_SLOT - 1) / CHARS_PER_SLOT

    /** Replaces forbidden/control characters with '_', trims ignored trailing periods/spaces and
     * shortens the stem so the whole name (extension kept) fits [MAX_LENGTH]. Blank → [fallback]. */
    fun sanitize(name: String, fallback: String = "design.pes"): String {
        val cleaned = name.map { if (isAllowedChar(it)) it else '_' }.joinToString("").trim().trimEnd('.', ' ')
        val dot = cleaned.lastIndexOf('.')
        val ext = if (dot > 0) cleaned.substring(dot) else ""
        val stem = (if (dot > 0) cleaned.substring(0, dot) else cleaned).trimEnd('.', ' ')
        if (stem.isBlank()) return fallback
        var kept = stem.take(MAX_LENGTH - ext.length)
        // Never split a surrogate pair.
        if (kept.isNotEmpty() && kept.last().isHighSurrogate()) kept = kept.dropLast(1)
        return (kept.trimEnd('.', ' ') + ext).takeIf(::isValid) ?: fallback
    }

    /** First of `name`, `name (1)`, `name (2)`… (as PesExport.uniqueName) not in [taken], compared
     * case-insensitively as FAT does; the stem is shortened so the suffix still fits [MAX_LENGTH]. */
    fun unique(name: String, taken: Collection<String>): String {
        val used = taken.map { it.uppercase(Locale.ROOT) }.toHashSet()
        val clean = sanitize(name)
        val dot = clean.lastIndexOf('.')
        val ext = if (dot > 0) clean.substring(dot) else ""
        val stem = if (dot > 0) clean.substring(0, dot) else clean
        for (n in 0..999_999) {
            val suffix = if (n == 0) "" else " ($n)"
            var kept = stem.take(MAX_LENGTH - ext.length - suffix.length)
            if (kept.isNotEmpty() && kept.last().isHighSurrogate()) kept = kept.dropLast(1)
            val candidate = kept.trimEnd('.', ' ') + suffix + ext
            if (isValid(candidate) && candidate.uppercase(Locale.ROOT) !in used) return candidate
        }
        error("No unique FAT long file name available")
    }
}
