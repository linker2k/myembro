// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.relay

import io.github.linker2k.myembro.engine.agent.ToolSchema
import io.github.linker2k.myembro.engine.inkstitch.svg.SvgPictureInput
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

/** import_picture's boundary: limits at n-1/n/n+1, signatures, defaults, and the schema the relay offers. */
class RelayPictureTest {
    private fun args(format: String, data: String, colors: Int? = null) = buildJsonObject {
        put("format", format); put("data", data); colors?.let { put("colors", it) }
    }
    private fun code(c: RelayPicture.Checked) = (c as? RelayPicture.Checked.Rejected)?.code
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** Base64 text of exactly [chars] characters (a multiple of 4) that decodes to a PNG-signed payload. */
    private fun pngBase64(chars: Int): String {
        val bytes = png + ByteArray(chars / 4 * 3 - png.size)
        return Base64.getEncoder().encodeToString(bytes).also { assertEquals(chars, it.length) }
    }

    @Test fun pngBase64LimitIsInclusive() {
        val max = RelayPicture.MAX_BASE64_CHARS
        assertEquals(0, max % 4)
        assertNull(code(RelayPicture.check(args("png", pngBase64(max - 4)))))
        assertNull(code(RelayPicture.check(args("png", pngBase64(max)))))
        // n+1 is never valid base64, so the next real size (n+4) is the one that must be refused as too large.
        assertEquals(RelayPicture.TOO_LARGE, code(RelayPicture.check(args("png", pngBase64(max + 4)))))
        assertEquals(RelayPicture.TOO_LARGE, code(RelayPicture.check(args("png", pngBase64(max) + "A"))))
        val refusal = RelayPicture.check(args("png", pngBase64(max + 4))) as RelayPicture.Checked.Rejected
        assertTrue(refusal.message, "share" in refusal.message && "공유" in refusal.message)
    }

    @Test fun svgByteLimitIsTheSvgPictureInputLimit() {
        val head = "<svg xmlns=\"http://www.w3.org/2000/svg\">"
        fun svg(bytes: Int) = head + "x".repeat(bytes - head.length - 6) + "</svg>"
        for (n in listOf(SvgPictureInput.MAX_BYTES - 1, SvgPictureInput.MAX_BYTES))
            assertNull(code(RelayPicture.check(args("svg", svg(n)))))
        assertEquals(RelayPicture.TOO_LARGE, code(RelayPicture.check(args("svg", svg(SvgPictureInput.MAX_BYTES + 1)))))
        // UTF-8 bytes, not characters: 2 Hangul syllables are 6 bytes.
        assertEquals(RelayPicture.TOO_LARGE, code(RelayPicture.check(args("svg", svg(SvgPictureInput.MAX_BYTES - 1).replace("xx", "안녕")))))
    }

    @Test fun contentMustMatchTheFormat() {
        val jpeg = Base64.getEncoder().encodeToString(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()))
        assertEquals("INVALID_PICTURE", code(RelayPicture.check(args("png", jpeg))))
        assertNull(code(RelayPicture.check(args("jpeg", jpeg))))
        assertEquals("INVALID_PICTURE", code(RelayPicture.check(args("jpeg", pngBase64(16)))))
        assertEquals("INVALID_PICTURE", code(RelayPicture.check(args("png", "not base64!"))))
        assertEquals("INVALID_PICTURE", code(RelayPicture.check(args("svg", "iVBORw0KGgo="))))
        assertNull(code(RelayPicture.check(args("png", "data:image/png;base64," + pngBase64(16)))))
    }

    @Test fun colorsDefaultToThePhotoScreenDefault() {
        assertEquals(4, (RelayPicture.check(args("png", pngBase64(16))) as RelayPicture.Checked.Accepted).colors)
        assertEquals(2, (RelayPicture.check(args("png", pngBase64(16), 2)) as RelayPicture.Checked.Accepted).colors)
    }

    @Test fun strictSchemaAcceptsOnlyTheDocumentedArguments() {
        val schema = ToolSchema(buildJsonObject { put("tools", JsonArray(listOf(RelayPicture.DEFINITION))) }.toString())
        // The relay leaves colors and title out; the app fills their defaults before the strict check.
        assertNull(schema.validate("import_picture", RelayCatalog.withDefaults("import_picture", args("svg", "<svg/>"))))
        assertNull(schema.validate("import_picture", JsonObject(args("png", "AAAA", 6) + ("title" to JsonPrimitive("Smile")))))
        assertEquals(JsonPrimitive(6), RelayCatalog.withDefaults("import_picture", args("png", "AAAA", 6))["colors"])
        for (bad in listOf(args("gif", "x"), args("png", "x", 1), args("png", "x", 7), buildJsonObject { put("format", "svg") },
            JsonObject(args("svg", "<svg/>") + ("widthMm" to JsonPrimitive(30)))))
            assertNotNull(bad.toString(), schema.validate("import_picture", bad))
        val offered = RelayProtocol.offer(listOf(RelayPicture.DEFINITION)).single()
        val description = offered.getValue("description").jsonPrimitive.content
        assertTrue(description, "SVG is preferred" in description && "must be small" in description)
        val input = offered.getValue("inputSchema").jsonObject
        assertEquals(listOf("format", "data"), input.getValue("required").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(JsonPrimitive(4), input.getValue("properties").jsonObject.getValue("colors").jsonObject["default"])
    }

    @Test fun layerNameIsTitleOrFirstFreeNumberPlusTheCallingAi() {
        val n = { i: Int -> "AI 그림 $i" }
        assertEquals("AI 그림 1", RelayPicture.name("", null, emptyList(), n))
        assertEquals("AI 그림 2 (claude-code)", RelayPicture.name(" ", "claude-code", listOf("AI 그림 1 (other)", "AI 그림 10"), n))
        assertEquals("스마일 (claude-code)", RelayPicture.name(" 스마일 ", "claude-code", listOf("AI 그림 1"), n))
        assertEquals(null, RelayCaller.of(" \n ").name)
        assertEquals("Claude Desktop", RelayCaller.of("Claude\nDesktop").name)
        assertEquals(40, RelayCaller.of("x".repeat(60)).name!!.length)
    }
}
