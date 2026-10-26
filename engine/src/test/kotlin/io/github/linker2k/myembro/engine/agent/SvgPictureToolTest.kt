// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.agent

import io.github.linker2k.myembro.engine.edit.commands.EditSession
import io.github.linker2k.myembro.engine.inkstitch.svg.SvgPictureInput
import kotlinx.serialization.json.*
import kotlin.test.*

/** The host-only SVG picture preparation behind the relay's import_picture. */
class SvgPictureToolTest {
    private val smile = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><circle cx="50" cy="50" r="45" fill="#FFCC00"/>""" +
        """<circle cx="35" cy="40" r="6" fill="#000000"/><circle cx="65" cy="40" r="6" fill="#000000"/>""" +
        """<path d="M30 62 Q50 82 70 62 L66 60 Q50 74 34 60 Z" fill="#000000"/></svg>"""
    private val export = buildJsonObject { put("machineId", "se700"); put("frameId", "H100"); put("pesVersion", "V6") }

    @Test fun twoColorSmileIsOneCheckedTurnAndOneUndo() {
        val session = EditSession()
        val controller = ToolDispatcher().bind(session)
        val before = session.snapshot()
        val prepared = controller.prepareSvgPicture(smile.toByteArray(), 50.0, "Smile")
        assertTrue(prepared.ok, prepared.output.toString())
        assertEquals(50.0, prepared.output.getValue("widthMm").jsonPrimitive.double)
        assertEquals("PASS", prepared.output.getValue("verdict").jsonPrimitive.content)
        assertEquals(before, session.snapshot(), "preparing changes nothing")
        val added = controller.executeScript("t1", session.revision, listOf(ToolTurnController.Call("add_generated_art", buildJsonObject {
            put("transactionId", "t1"); put("resultId", prepared.output.getValue("resultId")); put("objectId", "picture-1"); put("name", "Smile")
        })), export)
        assertTrue(added.ok, added.output.toString())
        val obj = assertIs<io.github.linker2k.myembro.engine.edit.ShapeObject>(session.project.objects.single())
        assertTrue(obj.regions.isNotEmpty())
        assertEquals("Smile", obj.name)
        assertEquals(2, obj.placedPattern().colorBlocks.size)
        assertEquals(1, session.snapshot().undoCount)
        session.undo(session.revision)
        assertEquals(before.project, session.project)
        println("SVG_IMPORT 2-color smile -> sewout ${prepared.output.getValue("verdict").jsonPrimitive.content}; 1 turn, 1 undo")
    }

    @Test fun widthIsClampedToThePictureRangeAtOneTenthMm() {
        val controller = ToolDispatcher().bind(EditSession())
        // H100: pictureWidthRange max = floor(min(999, 999/1))/10 = 99.9 for a square picture.
        assertEquals(99.9, controller.prepareSvgPicture(smile.toByteArray(), 500.0, "Smile").output.getValue("widthMm").jsonPrimitive.double)
        assertEquals(20.0, controller.prepareSvgPicture(smile.toByteArray(), 1.0, "Smile").output.getValue("widthMm").jsonPrimitive.double)
        assertEquals(42.4, controller.prepareSvgPicture(smile.toByteArray(), 42.36, "Smile").output.getValue("widthMm").jsonPrimitive.double)
    }

    @Test fun refusedSvgChangesNothing() {
        val session = EditSession()
        val controller = ToolDispatcher().bind(session)
        val tooBig = smile.replace("</svg>", "<!--" + "x".repeat(SvgPictureInput.MAX_BYTES) + "--></svg>")
        for (svg in listOf(tooBig, smile.replace("<circle cx=\"35\"", "<text>hi</text><circle cx=\"35\""),
            """<svg xmlns="http://www.w3.org/2000/svg"><image href="http://x/y.png"/></svg>""", "not svg")) {
            val result = controller.prepareSvgPicture(svg.toByteArray(), 50.0, "x")
            assertFalse(result.ok); assertEquals("SVG_NOT_SUPPORTED", result.code, result.output.toString())
        }
        assertEquals(0, session.snapshot().undoCount)
    }
}
