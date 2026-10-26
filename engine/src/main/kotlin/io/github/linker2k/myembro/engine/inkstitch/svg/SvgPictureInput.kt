// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg

import io.github.linker2k.myembro.engine.geometry.SvgPath
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.InputStream
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import javax.xml.parsers.DocumentBuilderFactory

/** Untrusted SVG entry boundary. Limits are measured maxima of the 30 pinned bundled emoji;
 * see docs/agent/svg-picture.md. Not a new renderer: validated input uses VectorArt unchanged. */
object SvgPictureInput {
    const val MAX_BYTES = 4788
    const val MAX_ELEMENTS = 14
    const val MAX_PATH_POINTS = 202
    data class Metrics(val bytes: Int, val elements: Int, val pathPoints: Int)
    data class Parsed(val art: VectorArt.Art, val metrics: Metrics)

    fun read(input: InputStream): ByteArray {
        val buffer = ByteArray(MAX_BYTES + 1)
        var size = 0
        while (size < buffer.size) {
            val count = input.read(buffer, size, buffer.size - size)
            if (count < 0) break
            require(count > 0) { "Cannot read SVG" }
            size += count
        }
        require(size <= MAX_BYTES) { "SVG byte limit" }
        return buffer.copyOf(size)
    }

    fun parse(bytes: ByteArray): Parsed {
        require(bytes.size <= MAX_BYTES) { "SVG byte limit" }
        val svg = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        // Lexical prohibition is independent of Android/JAXP feature support. UTF-16 and NUL are
        // refused, so declarations cannot bypass it. Resolver below is an additional fail-closed layer.
        require('\u0000' !in svg && !Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(svg)) { "SVG declarations forbidden" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            // Android's factory does not implement every JAXP feature. The unconditional lexical
            // declaration ban and resolver remain active even when a feature is unavailable.
            for ((feature, enabled) in listOf(
                "http://apache.org/xml/features/disallow-doctype-decl" to true,
                "http://xml.org/sax/features/external-general-entities" to false,
                "http://xml.org/sax/features/external-parameter-entities" to false,
                "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false)) {
                try { setFeature(feature, enabled) } catch (_: javax.xml.parsers.ParserConfigurationException) { }
            }
        }
        val builder = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> throw IllegalArgumentException("External SVG entity forbidden") }
            setErrorHandler(object : org.xml.sax.helpers.DefaultHandler() {
                override fun error(e: org.xml.sax.SAXParseException) { throw e }
                override fun fatalError(e: org.xml.sax.SAXParseException) { throw e }
            })
        }
        val doc = builder.parse(InputSource(StringReader(svg)))
        val root = doc.documentElement
        require(root.localName == "svg" && root.namespaceURI == "http://www.w3.org/2000/svg") { "SVG root required" }
        val allowed = setOf("svg", "g", "path", "rect", "circle", "ellipse", "line", "polyline", "polygon", "title", "desc", "metadata", "defs")
        val elements = doc.getElementsByTagName("*")
        require(elements.length <= MAX_ELEMENTS) { "SVG element limit" }
        for (i in 0 until elements.length) {
            val e = elements.item(i) as Element
            require(e.namespaceURI == root.namespaceURI && e.localName in allowed) { "Unsupported SVG element" }
            for (j in 0 until e.attributes.length) {
                val a = e.attributes.item(j)
                val key = (a.localName ?: a.nodeName).lowercase(java.util.Locale.ROOT)
                val value = a.nodeValue.lowercase(java.util.Locale.ROOT)
                require(key != "href" && !key.startsWith("on") && !Regex("url\\s*\\(|@import").containsMatchIn(value)) { "SVG reference forbidden" }
            }
        }
        // This second parse uses the existing renderer only after the entire source is approved.
        val art = VectorArt.parseSvg(svg)
        require(art.viewBox.all { it.isFinite() } && art.viewBox[2] > 0 && art.viewBox[3] > 0 && art.shapes.isNotEmpty()) { "Empty SVG" }
        var points = 0
        for (shape in art.shapes) {
            require(shape.strokeWidth.isFinite() && shape.strokeWidth >= 0) { "Invalid stroke" }
            val paths = SvgPath.flatten(shape.d, shape.transform, maxPoints = MAX_PATH_POINTS - points)
            points += paths.sumOf { it.points.size }
            require(points <= MAX_PATH_POINTS && paths.flatMap { it.points }.all { it.x.isFinite() && it.y.isFinite() }) { "SVG point limit" }
        }
        return Parsed(art, Metrics(bytes.size, elements.length, points))
    }
}
