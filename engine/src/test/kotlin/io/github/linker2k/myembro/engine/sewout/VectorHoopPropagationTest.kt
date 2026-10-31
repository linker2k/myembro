// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.sewout

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.inkstitch.svg.VectorArt
import io.github.linker2k.myembro.engine.io.*
import kotlin.test.*

class VectorHoopPropagationTest {
 private val svg="""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 120 160"><path fill="#224488" d="M0 0H120V160H0Z"/></svg>"""
 @Test fun selectedLargeHoopReachesRegionWriterAndRetainsTheWholeBlock() {
  val art=VectorArt.parseSvg(svg)
  val old=VectorArt.render(art,120.0)
  assertFailsWith<PesOutputSafety.Rejected> { PesWriter.write(old.pattern) }
  val result=VectorArt.render(art,120.0,captureRegions=true,hoop=HoopSpec.H130x180)
  assertEquals(0,result.excludedCount);assertEquals(0,result.droppedSliverCount)
  assertEquals(1,result.parts.size);assertEquals(1,result.regionPatterns.size)
  val bounds=result.pattern.bounds!!
  assertTrue(bounds.width in 1190..1210);assertTrue(bounds.height in 1590..1610)
  val checked=PesWriter.writeChecked(result.pattern,PesWriteSettings(hoop=HoopSpec.H130x180),origin=DesignOrigin.APP_GENERATED)
  assertFalse(checked.report.hasBlockingIssues)
  assertTrue(PecViewportCheck.check(checked.bytes,HoopSpec.H130x180).valid)
 }
 @Test fun omittedHoopAndExplicitDefaultProduceIdenticalNeedlesAndPesBytes() {
  val art=VectorArt.parseSvg(svg)
  val before=VectorArt.render(art,60.0)
  val after=VectorArt.render(art,60.0,hoop=HoopSpec.H100)
  assertEquals(before.pattern.stitches,after.pattern.stitches)
  assertEquals(before.parts.size,after.parts.size)
  assertContentEquals(PesWriter.write(before.pattern),PesWriter.write(after.pattern))
 }
 @Test fun sourceSliversAreReportedRatherThanHiddenAsZeroMissingBlocks() {
  val art=VectorArt.parseSvg("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20"><path fill="#224488" d="M0 0H20V20H0Z"/><path fill="#cc4422" d="M0.5 0H20V20H0.5Z"/></svg>""")
  val r=VectorArt.render(art,20.0)
  assertEquals(0,r.droppedSliverCount)
  assertTrue(r.parts.any { it.method==io.github.linker2k.myembro.engine.inkstitch.svg.StitchTypeRule.Type.RUNNING })
 }
}
