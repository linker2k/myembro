// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Independent embro-codex goldens: real scikit-image/Pillow defaults, not Kotlin-generated expectations. */
class PhotoFilterOracleTest {
    private val external = System.getProperty("myembro.photoFilterOracle") ?: System.getenv("MYEMBRO_PHOTO_FILTER_ORACLE")
    private fun resource(path: String): ByteArray = external?.let {
        File(it, path).readBytes()
    } ?: requireNotNull(javaClass.getResourceAsStream("/photo-filters/$path")).use { it.readBytes() }
    private fun json(path: String) = Json.parseToJsonElement(resource(path).decodeToString()).jsonObject
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun pixels(path: String, channels: Int): ByteArray {
        val image = requireNotNull(ImageIO.read(ByteArrayInputStream(resource(path))))
        assertEquals(channels, image.raster.numBands, path)
        // Raw samples preserve uint8 grayscale; getRGB would introduce a gray color-space conversion.
        return ByteArray(image.width * image.height * channels) { index ->
            val pixel = index / channels
            image.raster.getSample(pixel % image.width, pixel / image.width, index % channels).toByte()
        }
    }

    @Test fun matchesIndependentThresholdsAndEveryOutputByteExactly() {
        val provenance = json("provenance.json").getValue("upstream").jsonObject
        assertEquals("e8a42ba85aaf5fd9322ef9ca51bc21063b22fcae",
            provenance.getValue("scikit-image").jsonObject.getValue("commit").jsonPrimitive.content)
        assertEquals("89f1f4626a2aaf5f3d5ca6437f41def2998fbe09",
            provenance.getValue("Pillow").jsonObject.getValue("commit").jsonPrimitive.content)
        val manifest = json(if (external == null) "port-manifest.json" else "manifest.json")
        for ((path, detail) in manifest.getValue("files").jsonObject) {
            assertEquals(detail.jsonObject.getValue("sha256").jsonPrimitive.content, hash(resource(path)), path)
        }
        val cases = manifest.getValue("cases").jsonArray
        assertTrue(cases.isNotEmpty())
        for (id in cases.map { it.jsonPrimitive.content }) {
            val expected = json("$id/expected.json")
            val channels = if (expected.getValue("mode").jsonPrimitive.content == "RGB") 3 else 1
            val input = pixels("$id/input.png", channels)
            val unchanged = input.copyOf()
            assertEquals(expected.getValue("inputPixelSha256").jsonPrimitive.content, hash(input), id)
            val auto = Autocontrast.apply(input, channels)
            assertContentEquals(pixels("$id/autocontrast.png", channels), auto, "$id autocontrast bytes")
            assertEquals(expected.getValue("autocontrast").jsonObject.getValue("pixelSha256").jsonPrimitive.content, hash(auto), id)
            val otsu = expected.getValue("otsu").jsonObject
            val gray = pixels("$id/otsu-input.png", 1)
            assertEquals(otsu.getValue("inputPixelSha256").jsonPrimitive.content, hash(gray), id)
            val grayUnchanged = gray.copyOf()
            assertEquals(otsu.getValue("threshold").jsonPrimitive.int, OtsuThreshold.thresholdOtsu(gray), "$id threshold")
            val mask = OtsuThreshold.darkMask(gray)
            assertContentEquals(pixels("$id/otsu-mask.png", 1), mask, "$id Otsu mask bytes")
            assertEquals(otsu.getValue("maskPixelSha256").jsonPrimitive.content, hash(mask), id)
            assertContentEquals(unchanged, input, "$id immutable RGB/L input")
            assertContentEquals(grayUnchanged, gray, "$id immutable grayscale input")
            println("ORACLE_EXACT $id threshold/autocontrast/mask")
        }
        println("ORACLE_EXACT_TOTAL ${cases.size}")
    }
}
