// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg.image

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.model.EmbThread
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Line art with one large black region full of holes (resources/image-to-design/README.md).
 * The prepared clamp area must not change a single stitch: the digests were recorded with the
 * unprepared per-call buffer/covers on main 13511d13 (/tmp/myembro-wolf-hang/report.md).
 * No time limit is asserted (no measured basis for one).
 */
class WolfLineArtRegressionTest {
    private val image = ImageIO.read(javaClass.getResourceAsStream("/image-to-design/wolf-lineart.png"))
    private val hoop = HoopSpec.H100
    private val widthMm = PhotoPreparationPolicy.photoMaxWidthMm(hoop)

    private fun digest(stitches: List<*>) = MessageDigest.getInstance("SHA-256")
        .digest(stitches.joinToString(";").toByteArray()).joinToString("") { "%02x".format(it) }

    @Test fun colourDefaultsKeepStitchesAndReportProgressInsideTheLargeRegion() {
        val w = image.width; val h = image.height
        val pixels = PhotoCorrections.adjust(w, h, image.getRGB(0, 0, w, h, null, 0, w), false, 0, 1f).pixels
        val preview = ColorDrawingPipeline.traceRegions(w, h, pixels, widthMm, 4)
        val steps = mutableListOf<String>()
        val result = ColorDrawingPipeline.generate(preview, hoop) { if (it.startsWith("region_")) steps += it }
        assertEquals(46, result.regions.size)
        assertEquals(27_167, result.pattern.stitchCount)
        assertEquals("b30b88db81b9543d8bed878976b958e82a445a05d55aa75a227a4fd832a0d35a", digest(result.pattern.stitches))
        assertProgress(steps)
    }

    @Test fun silhouetteFinestDetailKeepsStitches() {
        // App silhouette input: white, then the corner colour, then the picture scaled to 256px.
        val edge = PhotoPreparationPolicy.photoRasterSize(2, widthMm, widthMm)
        val scaled = BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB)
        scaled.createGraphics().apply {
            color = java.awt.Color.WHITE; fillRect(0, 0, edge, edge)
            color = java.awt.Color(image.getRGB(0, 0), true); fillRect(0, 0, edge, edge)
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(image, 0, 0, edge, edge, 0, 0, image.width, image.height, null); dispose()
        }
        val pixels = PhotoCorrections.adjust(edge, edge, scaled.getRGB(0, 0, edge, edge, null, 0, edge), true, 0, 1f).pixels
        val thread = EmbThread(0xFFB5492A.toInt())
        val outline = RedworkPipeline.trace(edge, edge, pixels, widthMm, 0, thread,
            minAreaMm2 = PhotoPreparationPolicy.photoMinimumArea(2), maxContours = 128)
        val steps = mutableListOf<String>()
        val result = RedworkPipeline.generate(outline, thread, photoMode = true, style = PhotoStyle.SILHOUETTE, hoop = hoop) {
            if (it.startsWith("region_")) steps += it }
        assertEquals(15_816, result.pattern.stitchCount)
        assertEquals("37717af911f087000ce1210e9126b473b3c80cb95c9377f0f1cb3c58eee47aed", digest(result.pattern.stitches))
        assertProgress(steps)
    }

    /** Each pass names its stages before counting up within 1..total. */
    private fun assertProgress(events: List<String>) {
        assertTrue(events.isNotEmpty())
        // Path steps are always announced: rows before route, route before its first step.
        assertEquals("region_stage=rows", events.first())
        events.zipWithNext().filter { (_, b) -> b == "region_stage=route" }.forEach { (a, _) -> assertTrue(a != "region_stage=route") }
        events.zipWithNext().filter { (a, b) -> b.startsWith("region_step=") && !a.startsWith("region_step=") }
            .forEach { (a, _) -> assertEquals("region_stage=route", a) }
        val steps = events.filter { it.startsWith("region_step=") }
        assertTrue(steps.isNotEmpty())
        val pairs = steps.map { it.removePrefix("region_step=").split('/').map(String::toInt) }
        assertTrue(pairs.all { (done, total) -> done in 1..total })
        assertTrue(pairs.zipWithNext().all { (a, b) -> b[0] > a[0] || a[0] == a[1] })
    }
}
