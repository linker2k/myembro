// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.fat12

import java.text.Normalizer
import java.util.Locale

/** ASCII uppercase 8.3 only. Collisions use _1, _2…; never emits LFN directory entries. */
object ShortNames {
    private fun clean(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKD)
        .uppercase(Locale.ROOT).filter { it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }

    fun unique(original: String, existing: Collection<String>): String {
        val leaf = original.replace('\\', '/').substringAfterLast('/')
        val stem = clean(leaf.substringBeforeLast('.', leaf)).ifEmpty { "DESIGN" }
        val extension = if ('.' in leaf) clean(leaf.substringAfterLast('.')).take(3) else ""
        val used = existing.map { it.uppercase(Locale.ROOT) }.toHashSet()
        fun name(base: String) = base + if (extension.isEmpty()) "" else ".$extension"
        var base = stem.take(8)
        if (base in setOf("CON", "PRN", "AUX", "NUL") || base.matches(Regex("(COM|LPT)[1-9]"))) base = "_$base"
        if (name(base) !in used) return name(base)
        for (i in 1..999_999) {
            val suffix = "_$i"
            val candidate = name(stem.take(8 - suffix.length) + suffix)
            if (candidate !in used) return candidate
        }
        error("No unique 8.3 name available")
    }

    internal fun encode(name: String): ByteArray {
        require(name == unique(name, emptyList())) { "Not a canonical ASCII 8.3 name" }
        val base = name.substringBefore('.')
        val ext = name.substringAfter('.', "")
        return (base.padEnd(8, ' ') + ext.padEnd(3, ' ')).toByteArray(Charsets.US_ASCII)
    }
}
