// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

/** A point in Hershey font units; +y points down, as in EmbPattern. */
data class HersheyPoint(val x: Int, val y: Int)

data class HersheyGlyph(
    val left: Int,
    val right: Int,
    val strokes: List<List<HersheyPoint>>,
) {
    val advance: Int get() = right - left
}

/** James Hurt .jhf records, mapped by record order starting at ASCII space. */
class HersheyFont private constructor(
    val id: String,
    val glyphs: Map<Char, HersheyGlyph>,
    val capHeight: Int,
    val baseline: Int,
) {
    fun supports(character: Char): Boolean = character in glyphs
    operator fun get(character: Char): HersheyGlyph? = glyphs[character]

    companion object {
        const val DEFAULT_ID = "hershey-futural"

        /** Loaded once from the packaged resource; no Android or network dependency. */
        val DEFAULT: HersheyFont by lazy {
            val stream = checkNotNull(HersheyFont::class.java.getResourceAsStream("/lettering/futural.jhf")) {
                "Missing bundled Hershey font"
            }
            stream.bufferedReader(Charsets.US_ASCII).use { parse(it.readText(), DEFAULT_ID) }
        }

        fun load(fontId: String = DEFAULT_ID): HersheyFont {
            require(fontId == DEFAULT_ID) { "Unknown lettering font: $fontId" }
            return DEFAULT
        }

        /**
         * The first five columns are a glyph id (often a placeholder), the next three
         * count coordinate pairs INCLUDING the bearings. " R" is a pen lift.
         * Preserve spaces: trimming a record would corrupt its coordinates.
         */
        fun parse(
            data: String,
            id: String = "custom",
            firstCharacter: Char = ' ',
            capHeight: Int = 21,
            baseline: Int = 9,
        ): HersheyFont {
            require(capHeight > 0) { "capHeight must be positive" }
            val glyphs = linkedMapOf<Char, HersheyGlyph>()
            data.lineSequence().filter { it.isNotEmpty() }.forEachIndexed { index, line ->
                require(line.length >= 10) { "Short Hershey record ${index + 1}" }
                require(line.take(5).trim().toIntOrNull() != null) { "Invalid Hershey glyph id" }
                val count = line.substring(5, 8).trim().toIntOrNull()
                require(count != null && count >= 1 && line.length == 8 + count * 2) {
                    "Invalid Hershey vertex count at record ${index + 1}"
                }
                val left = line[8] - 'R'
                val right = line[9] - 'R'
                require(right >= left) { "Reversed Hershey bearings" }
                val strokes = mutableListOf<List<HersheyPoint>>()
                var stroke = mutableListOf<HersheyPoint>()
                for (offset in 10 until line.length step 2) {
                    val x = line[offset]
                    val y = line[offset + 1]
                    if (x == ' ' && y == 'R') {
                        if (stroke.isNotEmpty()) strokes += stroke.toList()
                        stroke = mutableListOf()
                    } else {
                        stroke += HersheyPoint(x - 'R', y - 'R')
                    }
                }
                if (stroke.isNotEmpty()) strokes += stroke.toList()
                val code = firstCharacter.code + index
                require(code <= Char.MAX_VALUE.code) { "Too many Hershey records" }
                glyphs[code.toChar()] = HersheyGlyph(left, right, strokes.toList())
            }
            require(glyphs.isNotEmpty()) { "Empty Hershey font" }
            return HersheyFont(id, glyphs.toMap(), capHeight, baseline)
        }
    }
}
