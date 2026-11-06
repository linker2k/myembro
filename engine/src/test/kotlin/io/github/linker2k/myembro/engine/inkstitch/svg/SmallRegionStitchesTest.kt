// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.inkstitch.svg
import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.inkstitch.svg.image.*
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.sewout.*
import kotlin.test.*
class SmallRegionStitchesTest {
 @Test fun oneMillimeterBoundaryUsesExistingStitchTypes() {
  assertEquals(StitchTypeRule.Type.RUNNING,SmallRegionStitches.type(0.999,StitchTypeRule.Type.TATAMI))
  assertEquals(StitchTypeRule.Type.TATAMI,SmallRegionStitches.type(1.0,StitchTypeRule.Type.TATAMI))
  assertEquals(StitchTypeRule.Type.SATIN,SmallRegionStitches.type(1.001,StitchTypeRule.Type.SATIN))
 }
 @Test fun subMillimeterEyesSurviveSvgOcclusionAsRunningRegions() {
  val svg="""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20"><rect width="20" height="20" fill="#336699"/><circle cx="6" cy="7" r="0.4" fill="#222222"/><circle cx="14" cy="7" r="0.4" fill="#222222"/></svg>"""
  val r=VectorArt.render(VectorArt.parseSvg(svg),20.0,captureRegions=true)
  assertEquals(setOf(0,1,2),r.parts.map { it.shape }.toSet())
  assertEquals(2,r.parts.count { it.method==StitchTypeRule.Type.RUNNING })
  assertEquals(0,r.tinyOmittedCount)
  assertFalse(PesWriter.writeChecked(r.pattern).report.hasBlockingIssues)
 }
 @Test fun photoPreservesOnePixelPaintBeforeTheNeedleRepresentationDecision() {
  val edge=32
  val pixels=IntArray(edge*edge) { i -> val x=i%edge;val y=i/edge
   if(x==16 && y==16)0xff222222.toInt() else if(x in 5..26 && y in 5..26)0xff336699.toInt() else -1 }
  val a=ColorDrawingPipeline.trace(edge,edge,pixels,16.0,2,minAreaMm2=3.0,maxContours=1)
  val b=ColorDrawingPipeline.trace(edge,edge,pixels,16.0,2,minAreaMm2=0.0)
  assertEquals(2,a.layers.size)
  assertEquals(a.layers.map { it.outline.paths },b.layers.map { it.outline.paths })
  val r=ColorDrawingPipeline.generate(a)
  assertTrue(r.regions.any { it.method==StitchTypeRule.Type.RUNNING })
  assertFalse(PesWriter.writeChecked(r.pattern).report.hasBlockingIssues)
 }
 @Test fun onlyUnrepresentableTinyRegionsAreOmittedAndCounted() {
  val svg="""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20"><rect width="20" height="20" fill="#336699"/><rect x="6" y="7" width="0.00001" height="0.00001" fill="#222222"/></svg>"""
  val r=VectorArt.render(VectorArt.parseSvg(svg),20.0)
  assertEquals(1,r.tinyOmittedCount)
  assertEquals(1,r.excludedCount)
  assertEquals("1",r.pattern.metadata["myembro.tiny_regions_omitted"])
  assertFalse(PesWriter.writeChecked(r.pattern).report.hasBlockingIssues)
 }
 @Test fun densityBlockersNeverCauseSilentRegionRemoval() {
  val group=io.github.linker2k.myembro.engine.inkstitch.stitch_plan.StitchGroup(
   color=io.github.linker2k.myembro.engine.model.EmbThread(0xff222222.toInt()),
   stitches=(0 until 40).map { io.github.linker2k.myembro.engine.inkstitch.stitch_plan.Stitch((it%5)*0.11*PIXELS_PER_MM,((it/5)%5)*0.11*PIXELS_PER_MM) },
   lockStart=io.github.linker2k.myembro.engine.inkstitch.stitch_plan.InkLockStitch(),
   lockEnd=io.github.linker2k.myembro.engine.inkstitch.stitch_plan.InkLockStitch())
  val r=SmallRegionStitches.running(listOf(group),HoopSpec.H100)
  assertFalse(r.excluded)
  val pattern=io.github.linker2k.myembro.engine.inkstitch.stitch_plan.GeneratedPatternProcessor.fromGroups(r.groups,
   pecJumpTrims=true,lockAtThreadBoundaries=true,omitZeroLengthMotions=true)
  val failure=assertFailsWith<PesOutputSafety.Rejected> { PesWriter.writeChecked(pattern) }
  assertTrue(failure.report.findings.any { it.code==FindingCode.DENSITY_HOTSPOT && it.severity==Severity.BLOCKER })
 }

}
