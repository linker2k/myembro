// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.photo

/** Routing only: MIME never proves content. SVG still passes SvgPictureInput before conversion. */
internal object PictureIntake {
    enum class Kind { PNG, JPEG, WEBP, BMP, GIF, HEIF, AVIF, SVG, DESIGN, UNKNOWN;
        val picture get() = this in setOf(PNG, JPEG, WEBP, BMP, GIF, HEIF, AVIF, SVG)
        val raster get() = this in setOf(PNG, JPEG, WEBP, BMP, GIF, HEIF, AVIF)
    }
    val pickerMimes = arrayOf("image/*", "image/svg+xml", "text/xml", "application/xml", "text/plain", "application/octet-stream")
    fun sniff(prefix: ByteArray, fileName: String? = null): Kind {
        fun starts(vararg bytes: Int) = prefix.size >= bytes.size && bytes.indices.all { prefix[it].toInt() and 255 == bytes[it] }
        fun ascii(at: Int, text: String) = prefix.size >= at + text.length && text.indices.all { prefix[at + it] == text[it].code.toByte() }
        if (starts(137,80,78,71,13,10,26,10)) return Kind.PNG
        if (starts(255,216,255)) return Kind.JPEG
        if (ascii(0,"BM") && prefix.size >= 14) return Kind.BMP
        if (ascii(0,"GIF87a") || ascii(0,"GIF89a")) return Kind.GIF
        if (ascii(0,"RIFF") && ascii(8,"WEBP")) return Kind.WEBP
        if(ascii(4,"ftyp")) {
            val brands=(8 until minOf(prefix.size-3,64) step 4).map { prefix.copyOfRange(it,it+4).toString(Charsets.US_ASCII) }
            if(brands.any { it in setOf("avif","avis") }) return Kind.AVIF
            if(brands.any { it in setOf("heic","heix","hevc","hevx","mif1","msf1") }) return Kind.HEIF
        }
        // Recognized embroidery/archive bytes win even over a misleading .svg suffix.
        if (ascii(0,"#PES") || ascii(0,"#PEC") || ascii(0,"LA:") || starts(80,75,3,4) || starts(80,75,5,6) || starts(80,75,7,8)) return Kind.DESIGN
        val text = prefix.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        if (Regex("<svg(?:\\s|>)", RegexOption.IGNORE_CASE).containsMatchIn(text) || fileName?.endsWith(".svg", true) == true) return Kind.SVG
        if (fileName?.substringAfterLast('.', "")?.lowercase(java.util.Locale.ROOT) in setOf("pes","pec","dst","zip","myembro")) return Kind.DESIGN
        return Kind.UNKNOWN
    }
    /** Every non-picture stays with the existing importer, including its existing error handling. */
    fun partition(kinds: List<Kind>): Pair<List<Int>, List<Int>> =
        kinds.indices.partition { kinds[it].picture }
}
