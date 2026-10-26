// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

import io.github.linker2k.myembro.engine.inkstitch.svg.VectorArt
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.sewout.*
import java.io.File
import java.security.MessageDigest
import kotlin.test.*

class ShapeObjectTest {
    private fun fixture(path: String) = File("src/test/resources/$path").readText()
    private fun parity(svg: String, name: String, width: Double): ShapeObject {
        val art = VectorArt.parseSvg(svg)
        val legacy = VectorArt.render(art, width, name = name)
        val shape = ShapeObject.fromArt("shape", name, art, width)
        val actual = shape.localPattern()
        assertEquals(legacy.pattern.stitches, actual.stitches)
        assertEquals(legacy.pattern.threads, actual.threads)
        assertEquals(legacy.pattern.metadata, actual.metadata)
        assertEquals(legacy.pattern.warnings, actual.warnings)
        assertEquals(legacy.parts.size, shape.regions.size)
        assertTrue(shape.regions.isNotEmpty())
        assertTrue(shape.regions.all { it.paths.isNotEmpty() })
        assertEquals(legacy.parts.map { it.thread }, shape.regions.map { it.color })
        for (version in PesVersion.entries) {
            val expected = PesWriter.write(legacy.pattern, version)
            val bytes = PesWriter.write(actual, version)
            assertContentEquals(expected, bytes)
            val viewport = PecViewportCheck.check(bytes, HoopSpec.H100)
            assertTrue(viewport.valid, viewport.errors.toString())
            val report = SewoutCheck.checkPes(bytes, options = SewoutOptions(origin = DesignOrigin.APP_GENERATED))
            assertEquals(SewoutVerdict.PASS, report.verdict, report.findings.toString())
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            println("SHAPE_PARITY $name $version stitches=${actual.stitchCount} regions=${shape.regions.size} bytes=${bytes.size} sha256=$sha identical=true viewport=PASS sewout=PASS")
        }
        return shape
    }

    @Test fun emojiDefaultMatchesEveryStitchAndPesByte() {
        parity(fixture("emoji/fluent/red_heart_flat.svg"), "heart", 30.0)
    }
    @Test fun svgHoleDefaultMatchesEveryStitchAndPesByteAndPreservesHoleGeometry() {
        val shape = parity(fixture("inkstitch/fill/hole.svg"), "hole", 30.0)
        assertTrue(shape.regions.any { it.paths.size >= 2 }, "hole rings must remain editable geometry")
        assertTrue(shape.regions.flatMap { it.paths }.all { it.closed })
        assertTrue(shape.regions.flatMap { it.paths }.flatMap { it.points }.all { it.x in -15.0..15.0 })
    }
    @Test fun transformsVisibilityAndDuplicateRetainShapeIdentityAndGeometry() {
        val shape = ShapeObject.fromSvg("a", "heart", fixture("emoji/fluent/red_heart_flat.svg"), 30.0)
        val project = Project(objects = listOf(shape))
        val changed = project.duplicate("a", "b").rotateBy("b", 90.0).mirrorX("b")
        val copy = assertIs<ShapeObject>(changed.find("b"))
        assertEquals(shape.regions, copy.regions)
        assertEquals(shape.sourceArt, copy.sourceArt)
        assertEquals(shape.localPattern().stitches, copy.localPattern().stitches)
        assertSame(shape.localPattern(), copy.localPattern())
        assertEquals(Transform(), shape.transform)
        assertSame(shape, shape.withId(shape.id))
        assertSame(shape, shape.withTransform(shape.transform))
        assertSame(shape, shape.withVisible(shape.visible))
        val rebuilt = ShapeObject.fromArt(shape.id, shape.name, shape.sourceArt, shape.widthMm)
        assertEquals(shape, rebuilt)
        assertEquals(shape.hashCode(), rebuilt.hashCode())
        assertEquals(90.0, copy.transform.rotationDeg)
        assertIs<ShapeObject>(copy.withVisible(false))
        assertFalse(copy.withVisible(false).visible)
    }
    @Test fun shapeCannotBypassPlacedOrEncodedHoopChecks() {
        val shape = ShapeObject.fromSvg("a", "heart", fixture("emoji/fluent/red_heart_flat.svg"), 30.0)
            .withTransform(Transform(x = 1000.0))
        val project = Project(objects = listOf(shape))
        assertTrue(project.exceedsHoop())
        val report = SewoutCheck.check(project, options = SewoutOptions(origin = DesignOrigin.APP_GENERATED))
        assertTrue(report.hasBlockingIssues)
        val local = shape.localPattern()
        for (version in PesVersion.entries) {
            val error = assertFailsWith<IllegalArgumentException> {
                PesWriter.write(local, PesWriteSettings(version, hoop = HoopSpec("too small", 100, 100)))
            }
            assertContains(error.message!!, "PEC_HOOP")
        }
    }
    @Test fun sourceAndRegionGeometryAreDefensivelyFrozenAndCodecPreservesGeometry() {
        val parsed = VectorArt.parseSvg(fixture("inkstitch/fill/hole.svg"))
        val source = parsed.shapes.toMutableList()
        val shape = ShapeObject.fromArt("a", "hole", parsed.copy(shapes = source), 30.0)
        source.clear()
        assertTrue(shape.sourceArt.shapes.isNotEmpty())
        assertFailsWith<UnsupportedOperationException> { (shape.regions as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (shape.regions.first().paths as MutableList).clear() }
        val decoded=ProjectJson.decode(ProjectJson.encode(Project(objects=listOf(shape))))
        assertEquals(shape,decoded.objects.single())
        assertEquals(shape.localPattern().stitches,decoded.objects.single().localPattern().stitches)
    }
}
