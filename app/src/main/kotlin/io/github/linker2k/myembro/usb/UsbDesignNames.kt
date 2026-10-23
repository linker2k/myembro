// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.usb

import io.github.linker2k.myembro.engine.fat12.ShortNames
import java.util.Locale

/** Prefer the original design filename; remove generic leading labels before 8.3 truncation.
 * PES thread brands/PEC labels are not filenames ("EMBROIDERY" is commonly a thread brand).
 */
internal object UsbDesignNames {
    private val generic = setOf("embroidery", "embroide", "design", "designs", "pattern", "patterns", "sample", "samples")
    private fun words(name: String): List<String> = name.replace('\\', '/').substringAfterLast('/')
        .substringBeforeLast('.')
        .replace(Regex("([a-z])([A-Z])"), "$1_$2")
        .replace(Regex("^embroidery", RegexOption.IGNORE_CASE), "")
        .split(Regex("[\\s_.-]+")).filter { it.isNotBlank() }

    private fun meaningful(name: String): String = words(name)
        .dropWhile { it.lowercase(Locale.ROOT) in generic }.joinToString("_")

    fun needsDesignLabel(name: String): Boolean = meaningful(name).isBlank()

    fun unique(original: String, existing: Collection<String>, designLabel: String? = null): String {
        val stem = meaningful(original).ifBlank {
            designLabel?.let { meaningful("$it.pes") }?.takeIf { it.isNotBlank() } ?: "DESIGN"
        }
        val preferred = ShortNames.unique("$stem.PES", emptyList())
        val used = existing.map { it.uppercase(Locale.ROOT) }.toHashSet()
        if (preferred !in used) return preferred
        val base = preferred.substringBeforeLast('.')
        for (number in 1..999_999) {
            val suffix = "~$number"
            val candidate = base.take(8 - suffix.length).trimEnd('_', '-') + suffix + ".PES"
            if (candidate !in used) return candidate
        }
        error("No unique PES short name available")
    }
}
