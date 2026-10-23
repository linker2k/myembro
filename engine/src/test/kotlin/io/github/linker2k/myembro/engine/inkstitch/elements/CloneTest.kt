// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Ink/Stitch tests/test_clone.py@b0edd96311ece82ee48816dc93466274b12c2a9c (GPL-3.0-or-later), Copyright (c) Ink/Stitch contributors
package io.github.linker2k.myembro.engine.inkstitch.elements

import io.github.linker2k.myembro.engine.inkstitch.svg.SvgDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Same inputs and expected angles as Ink/Stitch's clone tests (angles compared mod 180). */
class CloneTest {
    private fun doc(body: String) = SvgDocument.parse("""<svg xmlns="http://www.w3.org/2000/svg"
        xmlns:xlink="http://www.w3.org/1999/xlink" xmlns:inkstitch="http://inkstitch.org/namespace"
        xmlns:inkscape="http://www.inkscape.org/namespaces/inkscape">$body</svg>""".byteInputStream())

    private val rect30 = """<rect id="r" width="10" height="10" inkstitch:angle="30"/>"""

    /** Elements sewn for the clone [id] alone. */
    private fun cloneElements(body: String, id: String = "u"): List<InkStitchElement> {
        val d = doc(body)
        return InkNodes(d).elementsOf(d.byId(id)!!).elements
    }

    private fun angle(body: String, id: String = "u") = cloneElements(body, id).filterIsInstance<InkFill>().single().params.number("angle")!!

    private fun assertAngle(expected: Double, actual: Double) =
        assertEquals(((expected % 180) + 180) % 180, ((actual % 180) + 180) % 180, 1e-4)

    @Test fun notEmbroiderable() =
        assertTrue(cloneElements("""<text id="t">Can't embroider this!</text><use id="u" xlink:href="#t"/>""").isEmpty())

    @Test fun notClone() =
        assertTrue(cloneElements("""$rect30<use id="u" xlink:href="#r" inkstitch:clone="false"/>""").isEmpty())

    @Test fun basic() = assertAngle(30.0, angle("""$rect30<use id="u" xlink:href="#r"/>"""))

    @Test fun hiddenClonedElementsNotEmbroidered() {
        val elements = cloneElements("""<g id="g"><rect id="shown" width="10" height="10" inkscape:label="NotHidden"/>
            <rect width="10" height="10" style="display: none"/><g style="display: none"><rect width="10" height="10"/></g></g>
            <use id="u" xlink:href="#g"/>""")
        assertEquals(1, elements.size)
    }

    @Test fun angleRotated() = assertAngle(10.0, angle("""$rect30<use id="u" xlink:href="#r" transform="rotate(20)"/>"""))

    @Test fun angleFlipped() = assertAngle(-30.0, angle("""$rect30<use id="u" xlink:href="#r" transform="scale(-1,1)"/>"""))

    @Test fun angleFlippedRotated() =
        assertAngle(-50.0, angle("""$rect30<use id="u" xlink:href="#r" transform="rotate(20) scale(-1,1)"/>"""))

    @Test fun angleNonUniformScale() =
        assertAngle(-55.0, angle("""$rect30<use id="u" xlink:href="#r" transform="rotate(10) scale(1,${-Math.sqrt(3.0)})"/>"""))

    @Test fun angleInheritsDownTree() = assertAngle(29.0, angle("""<g transform="rotate(3)">$rect30</g>
        <g transform="translate(20,0) rotate(-7)"><use id="u" xlink:href="#r" transform="rotate(11)"/></g>"""))

    @Test fun angleNotAppliedTwice() {
        val elements = cloneElements("""<rect id="r" width="10" height="10" style="stroke: skyblue; fill: red;"/>
            <use id="u" xlink:href="#r" transform="rotate(30)"/>""")
        assertEquals(2, elements.size, "fill and stroke")
        assertAngle(-30.0, elements.filterIsInstance<InkFill>().single().params.number("angle")!!)
    }

    @Test fun angleNotAppliedToNonFills() {
        val elements = cloneElements("""<rect id="r" width="10" height="10" style="stroke: skyblue; fill-opacity: 0;"/>
            <use id="u" xlink:href="#r" transform="rotate(30)"/>""")
        assertEquals(1, elements.size)
        assertNull(elements.single().params["angle"])
    }

    @Test fun styleInherits() {
        val elements = cloneElements("""<rect id="r" width="10" height="10" style="stroke: skyblue; fill-opacity: 0;"/>
            <use id="u" xlink:href="#r" style="stroke: red; stroke-width: 2;"/>""")
        // Source style takes precedence over the clone's.
        assertEquals(0xFF87CEEB.toInt(), elements.single().color, "skyblue")
    }

    @Test fun transformInheritsFromClonedElement() {
        val fill = cloneElements("""<rect id="r" width="10" height="10" inkstitch:angle="30" transform="scale(2,2)"/>
            <use id="u" xlink:href="#r" transform="translate(5,10)"/>""").single() as InkFill
        val xs = fill.paths.flatMap { p -> p.points.map { it.x } }; val ys = fill.paths.flatMap { p -> p.points.map { it.y } }
        assertEquals(listOf(5.0, 25.0, 10.0, 30.0), listOf(xs.min(), xs.max(), ys.min(), ys.max()).map { Math.round(it * 1e6) / 1e6 })
    }

    @Test fun cloneFillAngle() = assertAngle(42.0, angle("""$rect30<use id="u" xlink:href="#r" inkstitch:angle="42"/>"""))

    @Test fun angleManuallyFlipped() =
        assertAngle(-10.0, angle("""$rect30<use id="u" xlink:href="#r" transform="rotate(20)" inkstitch:flip_angle="True"/>"""))

    @Test fun recursiveUsesAngle() {
        val base = """$rect30<use id="u1" xlink:href="#r" transform="rotate(60)"/>"""
        assertAngle(-30.0, angle(base, "u1"))
        val grouped = """$base<g id="g" transform="rotate(-10)"><use id="u2" xlink:href="#u1"/></g>"""
        assertAngle(-20.0, angle(grouped, "u2"))
        assertAngle(-27.0, angle("""$grouped<use id="u3" xlink:href="#g" transform="rotate(7)"/>""", "u3"))
        // Cloning u2 directly, the relative transform of g does not apply.
        assertAngle(-37.0, angle("""$grouped<use id="u4" xlink:href="#u2" transform="rotate(7)"/>""", "u4"))
    }

    @Test fun recursiveUsesAngleWithSpecifiedAngle() = assertAngle(-7.0, angle("""$rect30
        <use id="u1" xlink:href="#r" transform="rotate(60)"/>
        <g id="g" transform="rotate(-10)"><use id="u2" xlink:href="#u1" inkstitch:angle="0"/></g>
        <use id="u3" xlink:href="#g" transform="rotate(7)"/>""", "u3"))

    @Test fun recursiveUsesPlaceCopiesByTheirSources() {
        val d = doc("""<g id="g1"><rect id="r" width="30" height="30"/><use id="u1" xlink:href="#r" transform="translate(20,0)"/></g>
            <use id="u2" xlink:href="#g1" transform="translate(0,20) scale(0.5,0.5)"/><use id="u3" xlink:href="#u2" transform="translate(0,30)"/>""")
        val fills = InkNodes(d).elementsOf(d.byId("u3")!!).elements.filterIsInstance<InkFill>()
        assertEquals(2, fills.size)
        // u3 ∘ u2 ∘ rect, and u3 ∘ u2 ∘ u1 ∘ rect: origins at (0, 50) and (10, 50).
        assertEquals(0.0, fills[0].paths.minOf { p -> p.points.minOf { it.x } }, 1e-6)
        assertEquals(50.0, fills[0].paths.minOf { p -> p.points.minOf { it.y } }, 1e-6)
        assertEquals(10.0, fills[1].paths.minOf { p -> p.points.minOf { it.x } }, 1e-6)
    }
}
