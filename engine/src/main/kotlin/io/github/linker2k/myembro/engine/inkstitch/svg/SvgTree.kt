// SPDX-License-Identifier: GPL-3.0-or-later
// Original myembro SVG reader for the Ink/Stitch port; the document model it feeds follows inkex as used by Ink/Stitch.
package io.github.linker2k.myembro.engine.inkstitch.svg

import java.io.InputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.SAXParseException

/** Why an SVG document was refused. The codes are shared with the release gate's negative cases. */
enum class SvgRejection { INVALID_INPUT, UNSUPPORTED_EXTERNAL_RESOURCE, CYCLIC_REFERENCE, MISSING_REFERENCE, INVALID_GEOMETRY, INVALID_PARAMETER }

class SvgImportException(val code: SvgRejection, message: String) : Exception("$code: $message")

/**
 * A light, mutable SVG element tree: enough of inkex's document model for Ink/Stitch's element
 * walk and clone resolution. Attribute names carry a fixed prefix for known namespaces
 * (`inkstitch:angle`, `inkscape:label`, `xlink:href`); SVG attributes are bare.
 */
class SvgNode(val tag: String, val attributes: MutableMap<String, String> = LinkedHashMap()) {
    var parent: SvgNode? = null
    val children: MutableList<SvgNode> = ArrayList()
    /** Text content (metadata values only). */
    var text: String = ""

    operator fun get(name: String): String? = attributes[name]
    operator fun set(name: String, value: String) { attributes[name] = value }
    val id: String? get() = attributes["id"]

    fun add(child: SvgNode) { child.parent = this; children += child }

    /** Ancestors from the parent up to the root. */
    fun ancestors(): Sequence<SvgNode> = generateSequence(parent) { it.parent }

    fun descendants(): Sequence<SvgNode> = sequence {
        for (c in children) { yield(c); yieldAll(c.descendants()) }
    }

    fun root(): SvgNode = ancestors().lastOrNull() ?: this

    /** Deep copy without a parent (inkex `copy_no_children` applied recursively). */
    fun copy(): SvgNode = SvgNode(tag, LinkedHashMap(attributes)).also { n ->
        n.text = text
        children.forEach { n.add(it.copy()) }
    }

    /** The `<use>` target reference, xlink or SVG 2 form. */
    val href: String? get() = attributes["xlink:href"] ?: attributes["href"]

    override fun toString(): String = "<$tag${id?.let { " id=$it" } ?: ""}>"
}

/** The parsed document plus its id index. */
class SvgDocument(val root: SvgNode) {
    private val ids: Map<String, SvgNode> = HashMap<String, SvgNode>().also { m ->
        (sequenceOf(root) + root.descendants()).forEach { n -> n.id?.let { m.putIfAbsent(it, n) } }
    }

    fun byId(id: String): SvgNode? = ids[id]

    /** `#id` reference; anything else is an external resource, which is never loaded. */
    fun resolve(url: String?): SvgNode? {
        val u = url?.trim() ?: return null
        if (!u.startsWith("#")) throw SvgImportException(SvgRejection.UNSUPPORTED_EXTERNAL_RESOURCE, "external reference $u")
        return ids[u.substring(1)]
    }

    companion object {
        private val PREFIXES = mapOf(
            "http://inkstitch.org/namespace" to "inkstitch",
            "http://www.inkscape.org/namespaces/inkscape" to "inkscape",
            "http://sodipodi.sourceforge.net/DTD/sodipodi-0.dtd" to "sodipodi",
            "http://www.w3.org/1999/xlink" to "xlink",
            "http://www.w3.org/XML/1998/namespace" to "xml",
        )
        private const val SVG_NS = "http://www.w3.org/2000/svg"
        /** Elements whose content never matters for embroidery; skipping them bounds memory. */
        private val DROPPED = setOf("style", "script", "foreignObject")
        const val MAX_ELEMENTS = 2_000_000

        /**
         * Reads an SVG without DTDs, entities or any external access. Ill-formed XML and a
         * non-SVG root are [SvgRejection.INVALID_INPUT]; a DOCTYPE (the only route to external
         * entities) is [SvgRejection.UNSUPPORTED_EXTERNAL_RESOURCE].
         */
        fun parse(input: InputStream): SvgDocument {
            val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true; isValidating = false }
            for ((feature, on) in listOf("http://apache.org/xml/features/disallow-doctype-decl" to true,
                "http://xml.org/sax/features/external-general-entities" to false,
                "http://xml.org/sax/features/external-parameter-entities" to false,
                "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false)) {
                runCatching { factory.setFeature(feature, on) }
            }
            val handler = Builder()
            try {
                factory.newSAXParser().parse(input, handler)
            } catch (e: SAXParseException) {
                val doctype = handler.sawDoctype || e.message.orEmpty().contains("DOCTYPE", ignoreCase = true)
                throw SvgImportException(if (doctype) SvgRejection.UNSUPPORTED_EXTERNAL_RESOURCE else SvgRejection.INVALID_INPUT,
                    e.message ?: "unreadable XML")
            } catch (e: SvgImportException) {
                throw e
            } catch (e: Exception) {
                throw SvgImportException(SvgRejection.INVALID_INPUT, e.message ?: e.javaClass.simpleName)
            }
            val root = handler.root ?: throw SvgImportException(SvgRejection.INVALID_INPUT, "empty document")
            if (root.tag != "svg") throw SvgImportException(SvgRejection.INVALID_INPUT, "root element is <${root.tag}>")
            return SvgDocument(root)
        }

        private class Builder : org.xml.sax.ext.DefaultHandler2() {
            var root: SvgNode? = null
            var sawDoctype = false
            private val stack = ArrayDeque<SvgNode?>()
            private var count = 0

            override fun startDTD(name: String?, publicId: String?, systemId: String?) {
                sawDoctype = true
                throw SvgImportException(SvgRejection.UNSUPPORTED_EXTERNAL_RESOURCE, "DOCTYPE declarations are not accepted")
            }

            override fun startElement(uri: String, local: String, qName: String, attrs: Attributes) {
                val parent = stack.lastOrNull()
                if ((stack.isNotEmpty() && parent == null) || local in DROPPED) { stack.addLast(null); return }
                if (++count > MAX_ELEMENTS) throw SvgImportException(SvgRejection.INVALID_INPUT, "too many elements")
                val prefix = PREFIXES[uri]
                val tag = when {
                    uri == SVG_NS || uri.isEmpty() -> local
                    prefix != null -> "$prefix:$local"
                    else -> "{$uri}$local"
                }
                val node = SvgNode(tag)
                for (i in 0 until attrs.length) {
                    val ns = attrs.getURI(i)
                    val name = when {
                        ns.isNullOrEmpty() -> attrs.getLocalName(i).ifEmpty { attrs.getQName(i) }
                        ns == "http://www.w3.org/2000/xmlns/" -> continue
                        else -> PREFIXES[ns]?.let { "$it:${attrs.getLocalName(i)}" } ?: continue
                    }
                    node.attributes[name] = attrs.getValue(i)
                }
                if (parent == null) root = node else parent.add(node)
                stack.addLast(node)
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                val node = stack.lastOrNull() ?: return
                if (node.tag.startsWith("inkstitch:") && node.text.length < 10_000) node.text += String(ch, start, length)
            }

            override fun endElement(uri: String, local: String, qName: String) { stack.removeLast() }
        }
    }
}
