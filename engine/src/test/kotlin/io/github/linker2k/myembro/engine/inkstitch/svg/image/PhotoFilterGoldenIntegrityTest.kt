// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import kotlinx.serialization.json.*
import java.awt.image.BufferedImage
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.test.*

/** Verifies frozen original outputs and their byte encoding, NOT Kotlin filter parity.
 * Filter ports should consume these fixtures in their own exact-byte comparison tests. */
class PhotoFilterGoldenIntegrityTest {
    private val manifest=json("manifest.json")
    private val cases=manifest.getValue("cases").jsonArray.map { it.jsonPrimitive.content }
    private fun bytes(path:String)=checkNotNull(javaClass.getResourceAsStream("/photo-filters/$path")) { path }.use { it.readBytes() }
    private fun json(path:String)=Json.parseToJsonElement(bytes(path).toString(Charsets.UTF_8)).jsonObject
    private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun image(path:String)=checkNotNull(ImageIO.read(bytes(path).inputStream()))
    private fun pixels(image:BufferedImage,mode:String):ByteArray {
        val channels=if(mode=="L")1 else 3
        return ByteArray(image.width*image.height*channels).also { output->
            var index=0
            for(y in 0 until image.height) for(x in 0 until image.width) {
                // getRGB on TYPE_BYTE_GRAY applies a color-space transform; raster samples preserve uint8 L.
                if(mode=="L") output[index++]=image.raster.getSample(x,y,0).toByte()
                else {
                    val rgb=image.getRGB(x,y)
                    output[index++]=(rgb ushr 16).toByte();output[index++]=(rgb ushr 8).toByte();output[index++]=rgb.toByte()
                }
            }
        }
    }
    @Test fun everyGeneratedFileMatchesManifestAndPinnedSourceIdentity() {
        assertEquals(39,cases.size);assertEquals(39,cases.toSet().size)
        val files=manifest.getValue("files").jsonObject
        assertEquals(196,files.size)
        for((name,entry) in files) {
            val expected=entry.jsonObject;val actual=bytes(name)
            assertEquals(expected.getValue("bytes").jsonPrimitive.int,actual.size,name)
            assertEquals(expected.getValue("sha256").jsonPrimitive.content,hash(actual),name)
        }
        val p=json("provenance.json")
        val upstream=p.getValue("upstream").jsonObject
        assertEquals("e8a42ba85aaf5fd9322ef9ca51bc21063b22fcae",upstream.getValue("scikit-image").jsonObject.getValue("commit").jsonPrimitive.content)
        assertEquals("89f1f4626a2aaf5f3d5ca6437f41def2998fbe09",upstream.getValue("Pillow").jsonObject.getValue("commit").jsonPrimitive.content)
        println("PHOTO_GOLDEN_MANIFEST PASS: 39 cases, 196 SHA-256 file checks, pinned original commits")
    }
    @Test fun pngDecodingPreservesEveryRecordedInputAndOutputByte() {
        for(id in cases) {
            val e=json("$id/expected.json");val mode=e.getValue("mode").jsonPrimitive.content
            val input=image("$id/input.png");assertEquals(e.getValue("width").jsonPrimitive.int,input.width);assertEquals(e.getValue("height").jsonPrimitive.int,input.height)
            assertEquals(e.getValue("inputPixelSha256").jsonPrimitive.content,hash(pixels(input,mode)),id)
            val otsu=e.getValue("otsu").jsonObject
            assertEquals(otsu.getValue("inputPixelSha256").jsonPrimitive.content,hash(pixels(image("$id/otsu-input.png"),"L")),id)
            assertEquals(otsu.getValue("maskPixelSha256").jsonPrimitive.content,hash(pixels(image("$id/otsu-mask.png"),"L")),id)
            assertEquals(e.getValue("autocontrast").jsonObject.getValue("pixelSha256").jsonPrimitive.content,hash(pixels(image("$id/autocontrast.png"),mode)),id)
        }
        println("PHOTO_GOLDEN_PIXELS PASS: 156 PNG payloads decoded to exact recorded uint8 bytes")
    }
    @Test fun otsuMasksKeepDocumentedInkPolarityAndConstantTieCases() {
        for(id in cases) {
            val e=json("$id/expected.json").getValue("otsu").jsonObject
            val threshold=e.getValue("threshold").jsonPrimitive.int
            val input=pixels(image("$id/otsu-input.png"),"L");val mask=pixels(image("$id/otsu-mask.png"),"L")
            assertTrue(threshold in 0..255,id);assertEquals(input.size,mask.size)
            assertContentEquals(ByteArray(input.size) { i->if((input[i].toInt() and 255)<=threshold)0 else 255.toByte() },mask,id)
            assertEquals(e.getValue("inkPixels").jsonPrimitive.int,mask.count { it==0.toByte() },id)
            if(id.startsWith("gray-bimodal-tie-"))assertEquals(32,threshold,id)
            if(id.startsWith("gray-black-") || id.startsWith("gray-constant-") || id.startsWith("gray-white-")) {
                assertEquals(input[0].toInt() and 255,threshold,id);assertTrue(mask.all { it==0.toByte() },id)
            }
        }
        println("PHOTO_GOLDEN_MASKS PASS: 39 masks, first-max tie=32, constants retain upstream <= polarity")
    }
    @Test fun capturedOriginalLutsExplainEveryAutocontrastOutputByte() {
        for(id in cases) {
            val e=json("$id/expected.json");val mode=e.getValue("mode").jsonPrimitive.content
            val input=pixels(image("$id/input.png"),mode);val result=pixels(image("$id/autocontrast.png"),mode)
            val channels=if(mode=="L")1 else 3
            val lut=e.getValue("autocontrast").jsonObject.getValue("lut").jsonArray.map { it.jsonPrimitive.int }
            assertEquals(channels*256,lut.size,id);assertTrue(lut.all { it in 0..255 },id)
            assertContentEquals(ByteArray(input.size) { i->lut[(i%channels)*256+(input[i].toInt() and 255)].toByte() },result,id)
        }
        println("PHOTO_GOLDEN_LUTS PASS: 39 captured original LUTs and all result bytes")
    }
}
