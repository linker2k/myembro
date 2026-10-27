// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import io.github.linker2k.myembro.engine.agent.AttachmentStore
import io.github.linker2k.myembro.engine.agent.PhotoRaster
import io.github.linker2k.myembro.engine.agent.ToolDispatcher
import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.edit.Project
import io.github.linker2k.myembro.engine.inkstitch.svg.image.ColorDrawingPipeline
import io.github.linker2k.myembro.engine.inkstitch.svg.image.PhotoPreparationPolicy
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.ui.photo.photoCrop
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.*

/**
 * Thread count of a 2-color photo, counted in the written PES (PEC color table and stitch color
 * blocks), for prepare_photo and the photo screen's color path on the same repository image.
 */
class AgentPhotoColorCountTest {
    private val png = AgentPhotoAttachTest.decodePng(File("../engine/src/test/resources/image-to-design/robot.png").readBytes())
    private val hoop = HoopSpec.H100

    /** The photo screen's raster: centered zoom=1 crop in the hoop aspect, scaled (nearest here), alpha kept for color. */
    private fun raster(width: Int, height: Int): IntArray {
        val (w, h, argb) = png
        val crop = photoCrop(w, h, hoop.width.toDouble() / hoop.height, 1f, 0f, 0f)
        return IntArray(width * height) { i ->
            argb[(crop.top + (i / width) * crop.height / height) * w + crop.left + (i % width) * crop.width / width]
        }
    }

    private data class Count(val pecTable: Int, val pecUnique: Int, val blocks: Int, val threads: Int, val colors: Set<Int>)

    /** PEC color table (count byte at PEC+48, then one palette index per block) and the pattern's own blocks. */
    private fun count(pattern: EmbPattern, pes: ByteArray): Count {
        val pec = (0..3).fold(0) { v, k -> v or ((pes[8 + k].toInt() and 0xFF) shl (8 * k)) }
        val changes = pes[pec + 48].toInt() and 0xFF
        val table = (0..changes).map { pes[pec + 49 + it].toInt() and 0xFF }
        return Count(table.size, table.toSet().size, pattern.colorChangeCount + 1, pattern.threads.size,
            pattern.threads.map { it.color and 0xFFFFFF }.toSet())
    }

    @Test fun prepareAndThePhotoScreenGiveTheSameThreadCount() {
        val store = AttachmentStore { id, r -> if (id == "a1") PhotoRaster(r.width, r.height, raster(r.width, r.height)) else null }
        val kernel = ToolDispatcher(attachmentStore = store)
        val prepared = kernel.dispatch(Project(hoop), "0", "prepare_photo",
            Json.parseToJsonElement("""{"attachmentId":"a1","mode":"color","colors":2,"widthMm":40,"filter":"original"}""").jsonObject)
        assertTrue(prepared.ok, prepared.output.toString())
        val added = kernel.dispatch(Project(hoop), "0", "add_generated_art", Json.parseToJsonElement(
            """{"transactionId":"t","resultId":"${prepared.output.getValue("resultId").jsonPrimitive.content}","objectId":"p","name":"p"}""").jsonObject)
        val pes = kernel.dispatch(added.project, "0", "prepare_pes",
            Json.parseToJsonElement("""{"projectRevision":"0","machineId":"se400","frameId":"H100","pesVersion":"V6"}""").jsonObject)
        assertTrue(pes.ok, pes.output.toString())
        val agentPattern = assertIs<io.github.linker2k.myembro.engine.edit.ShapeObject>(added.project.objects.single()).localPattern()
        val agent = count(agentPattern, pes.pesBytes!!)

        // Photo screen, color mode, 2 colors, 40mm picture, default detail 1 (PhotoViewModel.prepare/generateResult).
        val edge = PhotoPreparationPolicy.photoRasterSize(1, 40.0, 40.0)
        val preview = ColorDrawingPipeline.trace(edge, edge, raster(edge, edge), 40.0, 2,
            minAreaMm2 = ColorDrawingPipeline.MINIMUM_REGION_AREA_MM2)
        val screenPattern = ColorDrawingPipeline.generate(preview, hoop).pattern
        val screen = count(screenPattern, PesWriter.writeV1(screenPattern, hoop))

        val layers = preview.layers.map { "%06X".format(it.thread.color and 0xFFFFFF) }
        println("PHOTO_COLORS colors=2 prepare_photo: $agent")
        println("PHOTO_COLORS colors=2 photo screen: $screen layers=$layers palette=${preview.palette.map { "%06X".format(it and 0xFFFFFF) }}")
        // The preview pane paints each run in its thread color only (AgentScreens.PreviewPane); other
        // tones on screen are anti-aliased 1.2px lines mixing with the pane background.
        val painted = io.github.linker2k.myembro.ui.RenderData(added.project.objects.single().placedPattern()).runs.map { it.color and 0xFFFFFF }.toSet()
        println("PHOTO_COLORS preview pane run colors: ${painted.map { "%06X".format(it) }}")
        assertEquals(agent.colors, painted)
        assertEquals(agent, screen)
        assertEquals(2, screen.threads); assertEquals(2, screen.pecTable); assertEquals(2, screen.pecUnique)
    }
}
