// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

import io.github.linker2k.myembro.engine.inkstitch.elements.FillParameters
import io.github.linker2k.myembro.engine.inkstitch.svg.VectorArt
import io.github.linker2k.myembro.engine.io.PesWriter
import kotlinx.serialization.json.*
import kotlin.test.*

class SmallRegionProjectCompatibilityTest {
 private val svg="""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20"><rect width="20" height="20" fill="#224488"/><rect x="0.5" width="19.5" height="20" fill="#cc4422"/></svg>"""
 @Test fun preservedPaintDoesNotRenumberExistingBlockEditsOrInvalidateSavedOrder() {
  val shape=ShapeObject.fromSvg("old","Saved SVG",svg,20.0)
  val oldBlock=shape.regions.single { it.id=="region-0" }
  assertEquals(1,oldBlock.sourceShape)
  assertTrue(shape.regions.any { it.id.startsWith("small-region-") })
  val payload=JsonObject(ShapeObjectCodec.encode(shape)+mapOf(
   "regionOrder" to JsonArray(listOf(JsonPrimitive("region-0"))),
   "blocks" to buildJsonObject { put("region-0",ShapeObjectCodec.encodeBlock(BlockStitch(fill=FillParameters(angle=135.0),automatic_angle=false))) }))
  val restored=ShapeObjectCodec.decode(payload) as ShapeObject
  assertEquals(oldBlock.color,restored.regions.single { it.id=="region-0" }.color)
  assertEquals(135.0,restored.overrides.getValue("region-0").fill.angle)
  assertEquals("region-0",restored.regionOrder.first())
  assertEquals(shape.regions.size,restored.regionOrder.size)
  val reopened=ProjectJson.decode(ProjectJson.encode(Project(objects=listOf(restored)))).objects.single() as ShapeObject
  assertEquals(restored.regionOrder,reopened.regionOrder)
  assertEquals(restored.overrides,reopened.overrides)
 }
 @Test fun regionOwnershipAndStableIdsDoNotChangeGeneratedPesBytes() {
  val art=VectorArt.parseSvg(svg)
  val a=VectorArt.render(art,20.0,captureRegions=false)
  val b=VectorArt.render(art,20.0,captureRegions=true)
  assertContentEquals(PesWriter.write(a.pattern),PesWriter.write(b.pattern))
 }
}
