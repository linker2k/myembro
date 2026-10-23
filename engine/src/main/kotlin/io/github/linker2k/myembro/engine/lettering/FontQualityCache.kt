// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

/** revision must change when font bytes or the glyph extractor changes (e.g. SHA-256 + version). */
data class FontQualityKey(
    val fontId: String,
    val revision: String,
    val sampleText: String,
    val heightMm: Double,
    val outline: Boolean,
    val threadWeight: ThreadWeight = ThreadWeight.WT40,
) {
    init {
        require(fontId.isNotBlank() && revision.isNotBlank())
        require(heightMm.isFinite() && heightMm > 0)
    }
}

/** Bounded, thread-safe LRU. Compute APIs run on the caller's worker thread, never dispatch UI work. */
class FontQualityCache(private val maxEntries: Int = 64) {
    init { require(maxEntries > 0) }
    private val reports = LinkedHashMap<FontQualityKey, FontQualityReport>(16, 0.75f, true)

    @Synchronized fun get(key: FontQualityKey): FontQualityReport? = reports[key]

    /** The supplier is not invoked on a hit; concurrent misses for a key are analyzed once. */
    @Synchronized
    fun getOrAnalyze(
        key: FontQualityKey,
        glyphContours: () -> List<List<List<Pair<Double, Double>>>>,
    ): FontQualityReport = reports[key] ?: FontQuality.analyze(glyphContours(), key.heightMm, key.outline, key.threadWeight).also {
        put(key, it)
    }

    @Synchronized fun put(key: FontQualityKey, report: FontQualityReport) {
        reports[key] = report.copy(reasons = report.reasons.toList())
        while (reports.size > maxEntries) reports.remove(reports.keys.first())
    }

    /** Call after replacing or deleting an imported/catalog font. */
    @Synchronized fun invalidate(fontId: String) { reports.keys.removeAll { it.fontId == fontId } }
    @Synchronized fun clear() { reports.clear() }
    val size: Int @Synchronized get() = reports.size

    companion object {
        /** In-process cache shared by lettering and catalog cards. */
        val Shared = FontQualityCache()
    }
}
