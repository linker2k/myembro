// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.photo

import kotlin.test.*

class PictureIntakeTest {
    private val svg = "<?xml version=\"1.0\"?><svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 10 10\"><rect width=\"10\" height=\"10\"/></svg>".toByteArray()
    private val png = byteArrayOf(137.toByte(),80,78,71,13,10,26,10)
    private val jpeg = byteArrayOf(255.toByte(),216.toByte(),255.toByte(),224.toByte())
    private val webp = "RIFF0000WEBPVP8 ".toByteArray()
    private val pes = "#PES0001".toByteArray()
    @Test fun mimeContentMatrixIsIndependentOfMimeAndAction() {
        val cases = listOf("SVG" to (svg to PictureIntake.Kind.SVG), "PNG" to (png to PictureIntake.Kind.PNG),
            "JPEG" to (jpeg to PictureIntake.Kind.JPEG), "WEBP" to (webp to PictureIntake.Kind.WEBP),
            "PES" to (pes to PictureIntake.Kind.DESIGN), "PEC" to ("#PEC0001".toByteArray() to PictureIntake.Kind.DESIGN),
            "DST" to ("LA:Synthetic".toByteArray() to PictureIntake.Kind.DESIGN),
            "ZIP" to (byteArrayOf(80,75,3,4) to PictureIntake.Kind.DESIGN),
            "text" to ("not an image".toByteArray() to PictureIntake.Kind.UNKNOWN))
        var count = 0
        for (action in listOf("SEND","SEND_MULTIPLE","VIEW")) {
            for (mime in listOf("image/svg+xml","image/png","text/xml","application/xml","text/plain","application/octet-stream")) {
                for ((label, test) in cases) {
                    val actual = PictureIntake.sniff(test.first, "opaque-download")
                    assertEquals(test.second, actual, "$action/$mime/$label")
                    assertEquals(listOf(0).takeIf { actual.picture }.orEmpty(), PictureIntake.partition(listOf(actual)).first)
                    println("PICTURE_ROUTE $action | $mime | $label -> ${if(actual.picture) "PHOTO" else "EXISTING_IMPORT"} ($actual)")
                    count++
                }
            }
        }
        assertEquals(162, count)
        println("PICTURE_MATRIX PASS rows=$count; MIME is not evidence of content")
    }
    @Test fun svgSuffixIsCandidateOnlyAndDesignMagicWins() {
        assertEquals(PictureIntake.Kind.SVG, PictureIntake.sniff("unsupported".toByteArray(), "download.SVG"))
        assertEquals(PictureIntake.Kind.DESIGN, PictureIntake.sniff(pes, "misnamed.svg"))
        assertEquals(PictureIntake.Kind.PNG, PictureIntake.sniff(png, "misnamed.pes"))
        assertEquals(PictureIntake.Kind.UNKNOWN, PictureIntake.sniff("not PNG".toByteArray(), "bad.png"))
        assertEquals(PictureIntake.Kind.SVG, PictureIntake.sniff("\uFEFF  <!-- banner --><svg viewBox=\"0 0 10 10\"></svg>".toByteArray()))
    }
    @Test fun truncatedHeadersAndNonWebpRiffAreNotImages() {
        assertEquals(PictureIntake.Kind.UNKNOWN, PictureIntake.sniff(png.take(7).toByteArray()))
        assertEquals(PictureIntake.Kind.UNKNOWN, PictureIntake.sniff("RIFF0000WAVE".toByteArray()))
        assertEquals(PictureIntake.Kind.UNKNOWN, PictureIntake.sniff(byteArrayOf(255.toByte(),216.toByte())))
        assertEquals(PictureIntake.Kind.UNKNOWN, PictureIntake.sniff(byteArrayOf()))
    }
    @Test fun mixedBatchRetainsAllNonPicturesForUnchangedImporter() {
        val input = listOf(PictureIntake.Kind.SVG,PictureIntake.Kind.DESIGN,PictureIntake.Kind.PNG,PictureIntake.Kind.UNKNOWN)
        val (photos, imports) = PictureIntake.partition(input)
        assertEquals(listOf(0,2),photos); assertEquals(listOf(1,3),imports)
        assertEquals(input.indices.toList(),(photos+imports).sorted())
        assertTrue(listOf("image/*","image/svg+xml","text/xml","application/xml","text/plain","application/octet-stream").all { it in PictureIntake.pickerMimes })
    }
}
