// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A point in font units (SVG font coordinates: y grows upward, baseline at y = 0). */
data class SvgPoint(val x: Double, val y: Double)

/** An open or closed polyline; a closed one repeats its first point at the end. */
typealias Polyline = List<SvgPoint>

/** One glyph: [unicode] is usually a single character but SVG fonts allow ligature sequences. */
data class SvgGlyph(val unicode: String, val name: String?, val advance: Double, val strokes: List<Polyline>)

/**
 * SVG 1.1 font (`<font><font-face/><glyph unicode d/></font>`) as used by Hershey Text, the EMS
 * single-line fonts and Relief SingleLine. Each glyph path is flattened to polylines, so stroke
 * fonts become running-stitch input directly. Parsing is a tolerant tag scan: no DTD is ever
 * fetched and unknown markup is ignored.
 */
class SvgFont(
    val familyName: String?,
    val unitsPerEm: Double,
    val ascent: Double,
    val descent: Double,
    val defaultAdvance: Double,
    val glyphs: Map<String, SvgGlyph>,
    val missingGlyph: SvgGlyph?,
) {
    fun supports(c: Char): Boolean = c.toString() in glyphs

    fun glyph(c: Char): SvgGlyph? = glyphs[c.toString()]

    /**
     * Lays out one line of [text] on the baseline starting at x = 0, in font units. Unknown
     * characters use the missing glyph (or just advance). [spacing] is extra advance per glyph.
     */
    fun layout(text: String, spacing: Double = 0.0): List<Polyline> {
        val out = ArrayList<Polyline>()
        var x = 0.0
        for (ch in text) {
            val g = glyph(ch) ?: missingGlyph
            g?.strokes?.forEach { stroke -> out += stroke.map { SvgPoint(it.x + x, it.y) } }
            x += (g?.advance ?: defaultAdvance) + spacing
        }
        return out
    }

    /** Total advance of [text] in font units. */
    fun advance(text: String, spacing: Double = 0.0): Double =
        text.sumOf { ((glyph(it) ?: missingGlyph)?.advance ?: defaultAdvance) + spacing }

    companion object {
        private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
        private val ATTR = Regex("""([A-Za-z_][\w:.-]*)\s*=\s*(?:"([^"]*)"|'([^']*)')""")

        /** Parses SVG font markup; [tolerance] is the max bezier flattening error in font units. */
        fun parse(svg: String, tolerance: Double = 2.0): SvgFont {
            val whole = COMMENT.replace(svg, "")
            // Only look inside <font>…</font>: metadata text may quote markup.
            val start = Regex("<font(?=[\\s>])").find(whole)?.range?.first
                ?: throw IllegalArgumentException("No <font> element")
            val end = whole.indexOf("</font>", start).let { if (it < 0) whole.length else it }
            val text = whole.substring(start, end)
            val font = tags(text, "font").first()
            val face = tags(text, "font-face").firstOrNull() ?: emptyMap()
            val defaultAdvance = font["horiz-adv-x"]?.toDoubleOrNull() ?: 500.0
            val unitsPerEm = face["units-per-em"]?.toDoubleOrNull() ?: 1000.0
            fun glyphOf(a: Map<String, String>, unicode: String) = SvgGlyph(
                unicode = unicode,
                name = a["glyph-name"],
                advance = a["horiz-adv-x"]?.toDoubleOrNull() ?: defaultAdvance,
                strokes = a["d"]?.let { SvgPath.toPolylines(it, tolerance) } ?: emptyList(),
            )
            val glyphs = LinkedHashMap<String, SvgGlyph>()
            for (a in tags(text, "glyph")) {
                val unicode = a["unicode"]?.let(::decodeEntities) ?: continue
                if (unicode.isEmpty() || unicode in glyphs) continue
                glyphs[unicode] = glyphOf(a, unicode)
            }
            return SvgFont(
                familyName = face["font-family"]?.let(::decodeEntities),
                unitsPerEm = unitsPerEm,
                ascent = face["ascent"]?.toDoubleOrNull() ?: unitsPerEm * 0.8,
                descent = face["descent"]?.toDoubleOrNull() ?: -unitsPerEm * 0.2,
                defaultAdvance = defaultAdvance,
                glyphs = glyphs,
                missingGlyph = tags(text, "missing-glyph").firstOrNull()?.let { glyphOf(it, "") },
            )
        }

        /** Attribute maps of every `<name ...>` start tag (self-closing or not). */
        private fun tags(text: String, name: String): List<Map<String, String>> {
            val re = Regex("<${Regex.escape(name)}(?=[\\s/>])([^>]*)>")
            return re.findAll(text).map { m ->
                ATTR.findAll(m.groupValues[1]).associate { it.groupValues[1] to (it.groups[2]?.value ?: it.groupValues[3]) }
            }.toList()
        }

        internal fun decodeEntities(s: String): String {
            if ('&' !in s) return s
            return Regex("&(#x[0-9A-Fa-f]+|#[0-9]+|amp|lt|gt|quot|apos);").replace(s) { m ->
                val e = m.groupValues[1]
                when {
                    e.startsWith("#x") -> String(Character.toChars(e.substring(2).toInt(16)))
                    e.startsWith("#") -> String(Character.toChars(e.substring(1).toInt()))
                    e == "amp" -> "&"
                    e == "lt" -> "<"
                    e == "gt" -> ">"
                    e == "quot" -> "\""
                    else -> "'"
                }
            }
        }
    }
}

/**
 * SVG path data (`d`) → polylines. Supports every path command (M L H V C S Q T A Z, absolute and
 * relative). Each subpath becomes one polyline; curves are subdivided until the chord error is
 * below the tolerance.
 */
object SvgPath {
    fun toPolylines(d: String, tolerance: Double = 2.0): List<Polyline> {
        val tol = max(tolerance, 1e-6)
        val tokens = tokenize(d)
        val out = ArrayList<Polyline>()
        var cur = ArrayList<SvgPoint>()
        var x = 0.0
        var y = 0.0
        var startX = 0.0
        var startY = 0.0
        // Reflection control points for S/T.
        var lastCubic: SvgPoint? = null
        var lastQuad: SvgPoint? = null
        var i = 0
        var cmd = ' '

        fun flush() {
            if (cur.size >= 2) out += cur
            cur = ArrayList()
        }
        fun lineTo(nx: Double, ny: Double) {
            if (cur.isEmpty()) cur += SvgPoint(x, y)
            cur += SvgPoint(nx, ny)
            x = nx; y = ny
        }
        fun num(): Double {
            val t = tokens.getOrNull(i) ?: throw IllegalArgumentException("Path ended early: $d")
            if (t !is Double) throw IllegalArgumentException("Expected number in path: $d")
            i++
            return t
        }
        fun flag(): Boolean = num() != 0.0

        while (i < tokens.size) {
            val t = tokens[i]
            if (t is Char) { cmd = t; i++ } else if (cmd == ' ') throw IllegalArgumentException("Path must start with a command: $d")
            val rel = cmd.isLowerCase()
            val ox = if (rel) x else 0.0
            val oy = if (rel) y else 0.0
            when (cmd.uppercaseChar()) {
                'M' -> {
                    flush()
                    x = ox + num(); y = oy + num()
                    startX = x; startY = y
                    cur += SvgPoint(x, y)
                    // Further coordinate pairs are implicit line-tos.
                    cmd = if (rel) 'l' else 'L'
                    lastCubic = null; lastQuad = null
                }
                'L' -> { lineTo(ox + num(), oy + num()); lastCubic = null; lastQuad = null }
                'H' -> { lineTo(ox + num(), y); lastCubic = null; lastQuad = null }
                'V' -> { lineTo(x, (if (rel) y else 0.0) + num()); lastCubic = null; lastQuad = null }
                'C' -> {
                    val c1 = SvgPoint(ox + num(), oy + num())
                    val c2 = SvgPoint(ox + num(), oy + num())
                    val p = SvgPoint(ox + num(), oy + num())
                    cubic(SvgPoint(x, y), c1, c2, p, tol) { lineTo(it.x, it.y) }
                    lastCubic = c2; lastQuad = null
                }
                'S' -> {
                    val c1 = lastCubic?.let { SvgPoint(2 * x - it.x, 2 * y - it.y) } ?: SvgPoint(x, y)
                    val c2 = SvgPoint(ox + num(), oy + num())
                    val p = SvgPoint(ox + num(), oy + num())
                    cubic(SvgPoint(x, y), c1, c2, p, tol) { lineTo(it.x, it.y) }
                    lastCubic = c2; lastQuad = null
                }
                'Q' -> {
                    val c = SvgPoint(ox + num(), oy + num())
                    val p = SvgPoint(ox + num(), oy + num())
                    quad(SvgPoint(x, y), c, p, tol) { lineTo(it.x, it.y) }
                    lastQuad = c; lastCubic = null
                }
                'T' -> {
                    val c = lastQuad?.let { SvgPoint(2 * x - it.x, 2 * y - it.y) } ?: SvgPoint(x, y)
                    val p = SvgPoint(ox + num(), oy + num())
                    quad(SvgPoint(x, y), c, p, tol) { lineTo(it.x, it.y) }
                    lastQuad = c; lastCubic = null
                }
                'A' -> {
                    val rx = num(); val ry = num(); val rot = num()
                    val large = flag(); val sweep = flag()
                    val p = SvgPoint(ox + num(), oy + num())
                    arc(SvgPoint(x, y), rx, ry, rot, large, sweep, p, tol) { lineTo(it.x, it.y) }
                    lastCubic = null; lastQuad = null
                }
                'Z' -> {
                    if (cur.isNotEmpty() && (x != startX || y != startY)) lineTo(startX, startY)
                    flush()
                    x = startX; y = startY
                    cmd = ' '
                    lastCubic = null; lastQuad = null
                }
                else -> throw IllegalArgumentException("Unknown path command '$cmd'")
            }
        }
        flush()
        return out
    }

    /** Commands as [Char], numbers as [Double]. Handles "1.5.5", "-1-2", "1e-3" and compact arc flags. */
    internal fun tokenize(d: String): List<Any> {
        val out = ArrayList<Any>()
        var i = 0
        var lastCmd = ' '
        var argIndex = 0
        while (i < d.length) {
            val c = d[i]
            when {
                c.isWhitespace() || c == ',' -> i++
                c.isLetter() && c != 'e' && c != 'E' -> {
                    out += c; lastCmd = c.uppercaseChar(); argIndex = 0; i++
                }
                else -> {
                    // Arc flags (args 3 and 4 of each 7) are single digits and may be unseparated.
                    if (lastCmd == 'A' && (argIndex % 7 == 3 || argIndex % 7 == 4) && (c == '0' || c == '1')) {
                        out += (c - '0').toDouble(); i++; argIndex++
                        continue
                    }
                    val start = i
                    if (d[i] == '+' || d[i] == '-') i++
                    var dot = false
                    while (i < d.length && (d[i].isDigit() || (d[i] == '.' && !dot))) { if (d[i] == '.') dot = true; i++ }
                    if (i < d.length && (d[i] == 'e' || d[i] == 'E')) {
                        val save = i
                        i++
                        if (i < d.length && (d[i] == '+' || d[i] == '-')) i++
                        if (i < d.length && d[i].isDigit()) { while (i < d.length && d[i].isDigit()) i++ } else i = save
                    }
                    val s = d.substring(start, i)
                    out += s.toDoubleOrNull() ?: throw IllegalArgumentException("Bad number '$s' in path")
                    argIndex++
                }
            }
        }
        return out
    }

    private fun cubic(p0: SvgPoint, p1: SvgPoint, p2: SvgPoint, p3: SvgPoint, tol: Double, emit: (SvgPoint) -> Unit) {
        // Wang's bound: segments needed so the chord error stays under tol.
        val ddx = max(abs(p0.x - 2 * p1.x + p2.x), abs(p1.x - 2 * p2.x + p3.x))
        val ddy = max(abs(p0.y - 2 * p1.y + p2.y), abs(p1.y - 2 * p2.y + p3.y))
        val n = segments(sqrt(0.75 * hypot(ddx, ddy) / tol))
        for (k in 1..n) {
            val t = k.toDouble() / n
            val u = 1 - t
            emit(
                SvgPoint(
                    u * u * u * p0.x + 3 * u * u * t * p1.x + 3 * u * t * t * p2.x + t * t * t * p3.x,
                    u * u * u * p0.y + 3 * u * u * t * p1.y + 3 * u * t * t * p2.y + t * t * t * p3.y,
                ),
            )
        }
    }

    private fun quad(p0: SvgPoint, p1: SvgPoint, p2: SvgPoint, tol: Double, emit: (SvgPoint) -> Unit) {
        val dd = hypot(p0.x - 2 * p1.x + p2.x, p0.y - 2 * p1.y + p2.y)
        val n = segments(sqrt(0.25 * dd / tol))
        for (k in 1..n) {
            val t = k.toDouble() / n
            val u = 1 - t
            emit(SvgPoint(u * u * p0.x + 2 * u * t * p1.x + t * t * p2.x, u * u * p0.y + 2 * u * t * p1.y + t * t * p2.y))
        }
    }

    private fun segments(v: Double): Int = min(256, max(1, ceil(v).toInt()))

    /** Endpoint-parameterized elliptical arc (SVG 1.1 implementation notes, F.6.5). */
    private fun arc(
        p0: SvgPoint, rxIn: Double, ryIn: Double, rotDeg: Double, large: Boolean, sweep: Boolean, p1: SvgPoint,
        tol: Double, emit: (SvgPoint) -> Unit,
    ) {
        var rx = abs(rxIn)
        var ry = abs(ryIn)
        if (rx == 0.0 || ry == 0.0 || (p0.x == p1.x && p0.y == p1.y)) { emit(p1); return }
        val phi = rotDeg * PI / 180
        val cp = cos(phi)
        val sp = sin(phi)
        val dx = (p0.x - p1.x) / 2
        val dy = (p0.y - p1.y) / 2
        val x1 = cp * dx + sp * dy
        val y1 = -sp * dx + cp * dy
        val lambda = (x1 * x1) / (rx * rx) + (y1 * y1) / (ry * ry)
        if (lambda > 1) { val s = sqrt(lambda); rx *= s; ry *= s }
        val num = rx * rx * ry * ry - rx * rx * y1 * y1 - ry * ry * x1 * x1
        val den = rx * rx * y1 * y1 + ry * ry * x1 * x1
        var coef = sqrt(max(0.0, num / den))
        if (large == sweep) coef = -coef
        val cx1 = coef * rx * y1 / ry
        val cy1 = -coef * ry * x1 / rx
        val cx = cp * cx1 - sp * cy1 + (p0.x + p1.x) / 2
        val cy = sp * cx1 + cp * cy1 + (p0.y + p1.y) / 2
        fun angle(ux: Double, uy: Double, vx: Double, vy: Double): Double {
            val a = acos(((ux * vx + uy * vy) / (hypot(ux, uy) * hypot(vx, vy))).coerceIn(-1.0, 1.0))
            return if (ux * vy - uy * vx < 0) -a else a
        }
        val theta1 = angle(1.0, 0.0, (x1 - cx1) / rx, (y1 - cy1) / ry)
        var delta = angle((x1 - cx1) / rx, (y1 - cy1) / ry, (-x1 - cx1) / rx, (-y1 - cy1) / ry)
        if (!sweep && delta > 0) delta -= 2 * PI else if (sweep && delta < 0) delta += 2 * PI
        // Chord error of a step dθ on radius r is r(1 - cos(dθ/2)).
        val r = max(rx, ry)
        val step = if (tol >= r) PI / 2 else 2 * acos(1 - tol / r)
        val n = segments(abs(delta) / step)
        for (k in 1..n) {
            val t = theta1 + delta * k / n
            val ex = rx * cos(t)
            val ey = ry * sin(t)
            emit(if (k == n) p1 else SvgPoint(cp * ex - sp * ey + cx, sp * ex + cp * ey + cy))
        }
    }
}
