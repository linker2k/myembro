// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.io

import io.github.linker2k.myembro.engine.model.EmbThread

/** Brother's fixed 64-color PEC thread chart, indexed by the PEC color byte (index 0 is unused). */
object PecThreads {
    private fun t(r: Int, g: Int, b: Int, name: String, catalog: String) = EmbThread(
        color = (0xFF shl 24) or (r shl 16) or (g shl 8) or b,
        description = name,
        catalogNumber = catalog,
        brand = "Brother",
        chart = "Brother",
    )

    val SET: List<EmbThread?> = listOf(
        null,
        t(14, 31, 124, "Prussian Blue", "1"),
        t(10, 85, 163, "Blue", "2"),
        t(0, 135, 119, "Teal Green", "3"),
        t(75, 107, 175, "Cornflower Blue", "4"),
        t(237, 23, 31, "Red", "5"),
        t(209, 92, 0, "Reddish Brown", "6"),
        t(145, 54, 151, "Magenta", "7"),
        t(228, 154, 203, "Light Lilac", "8"),
        t(145, 95, 172, "Lilac", "9"),
        t(158, 214, 125, "Mint Green", "10"),
        t(232, 169, 0, "Deep Gold", "11"),
        t(254, 186, 53, "Orange", "12"),
        t(255, 255, 0, "Yellow", "13"),
        t(112, 188, 31, "Lime Green", "14"),
        t(186, 152, 0, "Brass", "15"),
        t(168, 168, 168, "Silver", "16"),
        t(125, 111, 0, "Russet Brown", "17"),
        t(255, 255, 179, "Cream Brown", "18"),
        t(79, 85, 86, "Pewter", "19"),
        t(0, 0, 0, "Black", "20"),
        t(11, 61, 145, "Ultramarine", "21"),
        t(119, 1, 118, "Royal Purple", "22"),
        t(41, 49, 51, "Dark Gray", "23"),
        t(42, 19, 1, "Dark Brown", "24"),
        t(246, 74, 138, "Deep Rose", "25"),
        t(178, 118, 36, "Light Brown", "26"),
        t(252, 187, 197, "Salmon Pink", "27"),
        t(254, 55, 15, "Vermilion", "28"),
        t(240, 240, 240, "White", "29"),
        t(106, 28, 138, "Violet", "30"),
        t(168, 221, 196, "Seacrest", "31"),
        t(37, 132, 187, "Sky Blue", "32"),
        t(254, 179, 67, "Pumpkin", "33"),
        t(255, 243, 107, "Cream Yellow", "34"),
        t(208, 166, 96, "Khaki", "35"),
        t(209, 84, 0, "Clay Brown", "36"),
        t(102, 186, 73, "Leaf Green", "37"),
        t(19, 74, 70, "Peacock Blue", "38"),
        t(135, 135, 135, "Gray", "39"),
        t(216, 204, 198, "Warm Gray", "40"),
        t(67, 86, 7, "Dark Olive", "41"),
        t(253, 217, 222, "Flesh Pink", "42"),
        t(249, 147, 188, "Pink", "43"),
        t(0, 56, 34, "Deep Green", "44"),
        t(178, 175, 212, "Lavender", "45"),
        t(104, 106, 176, "Wisteria Violet", "46"),
        t(239, 227, 185, "Beige", "47"),
        t(247, 56, 102, "Carmine", "48"),
        t(181, 75, 100, "Amber Red", "49"),
        t(19, 43, 26, "Olive Green", "50"),
        t(199, 1, 86, "Dark Fuchsia", "51"),
        t(254, 158, 50, "Tangerine", "52"),
        t(168, 222, 235, "Light Blue", "53"),
        t(0, 103, 62, "Emerald Green", "54"),
        t(78, 41, 144, "Purple", "55"),
        t(47, 126, 32, "Moss Green", "56"),
        t(255, 204, 204, "Flesh Pink", "57"),
        t(255, 217, 17, "Harvest Gold", "58"),
        t(9, 91, 166, "Electric Blue", "59"),
        t(240, 249, 112, "Lemon Yellow", "60"),
        t(227, 243, 91, "Fresh Green", "61"),
        t(255, 153, 0, "Orange", "62"),
        t(255, 240, 141, "Cream Yellow", "63"),
        t(255, 200, 200, "Applique", "64"),
    )

    /** pyembroidery's red-mean color distance (EmbThread.color_distance_red_mean), alpha ignored. */
    fun distance(a: EmbThread, b: EmbThread): Int = distance(a.color, b.color)

    fun distance(a: Int, b: Int): Int {
        val ar = (a shr 16) and 0xFF; val ag = (a shr 8) and 0xFF; val ab = a and 0xFF
        val br = (b shr 16) and 0xFF; val bg = (b shr 8) and 0xFF; val bb = b and 0xFF
        val mean = Math.rint((ar + br) / 2.0).toInt()
        val r = ar - br; val g = ag - bg; val bl = ab - bb
        return (((512 + mean) * r * r) shr 8) + 4 * g * g + (((767 - mean) * bl * bl) shr 8)
    }

    /** Unknown index 0 renders as black, matching Brother machines. */
    fun forIndex(index: Int): EmbThread =
        SET[index % SET.size] ?: EmbThread(0xFF000000.toInt(), "Unknown", "0", "Brother", "Brother")
}
