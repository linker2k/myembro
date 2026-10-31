package io.github.linker2k.myembro.ui.lettering

import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import io.github.linker2k.myembro.lettering.AndroidGlyphOutlines
import io.github.linker2k.myembro.lettering.AndroidCenterlineGlyph
import io.github.linker2k.myembro.fonts.FontRepository
import io.github.linker2k.myembro.fonts.LetterFontGeometry
import io.github.linker2k.myembro.engine.lettering.*
import io.github.linker2k.myembro.engine.inkstitch.svg.PIXELS_PER_MM
import io.github.linker2k.myembro.engine.inkstitch.svg.image.autotrace.HangulComponentOrder
import io.github.linker2k.myembro.engine.inkstitch.elements.utils.StrokeToSatin
import io.github.linker2k.myembro.engine.inkstitch.stitches.AutoSatin
import io.github.linker2k.myembro.engine.inkstitch.utils.Point
import io.github.linker2k.myembro.engine.model.*
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.sewout.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KanaDakutenProbeTest {
 private val out=File(requireNotNull(System.getProperty("kana.out"))).apply { mkdirs() }
 private val black=EmbThread(0xff000000.toInt(),"Black 40wt")
 private fun emit(row:JSONObject) { File(out,"rows.jsonl").appendText(row.toString()+"\n");println(row.toString());System.out.flush() }
 private fun result(row:JSONObject,p:EmbPattern) {
  val outcome=try { val checked=PesWriter.writeChecked(p,machine=SewoutMachines.SE400,origin=DesignOrigin.APP_GENERATED);checked.report to checked.bytes }
  catch(e:PesOutputSafety.Rejected) { row.put("output_errors",e.outputErrors.joinToString("; "));e.report to null }
  val report=outcome.first;val bytes=outcome.second
  val s=report.statistics
  row.put("status",report.verdict.name).put("complete",report.analysisComplete).put("peak",s.peakCellPenetrations)
   .put("stitches",s.stitchCount).put("jumps",s.jumpCount).put("trims",s.trimCount)
   .put("width_mm",s.bounds?.widthMm).put("height_mm",s.bounds?.heightMm)
   .put("blockers",report.findingSeverities.filterValues { it==Severity.BLOCKER }.keys.joinToString(","))
   .put("warnings",report.findingSeverities.filterValues { it==Severity.WARNING }.keys.joinToString(","))
  if(bytes!=null && report.analysisComplete && !report.hasBlockingIssues) {
   val file=File(out,"pes/${row.getString("font")}-${row.getString("glyph").codePointAt(0).toString(16)}-${row.get("height")}-${row.getString("mode")}.pes")
   file.parentFile.mkdirs();file.writeBytes(bytes);row.put("saved",true)
  } else row.put("saved",false)
 }
 private fun uncovered(data:CenterlineSatinData,height:Double,reference:Double):Int {
  val scale=height/reference*PIXELS_PER_MM;var total=0
  for(component in data.components.distinct().sorted()) {
   val sections=data.paths.indices.filter { data.components[it]==component }.map { i ->
    val path=data.paths[i].map { (x,y)->Point(x*scale,y*scale) };val width=data.widths.getValue(0)*scale
    val start=if(path.first()==path.last()) StrokeToSatin.setFirstNode(path,width) else path
    StrokeToSatin.filteredRungs(requireNotNull(StrokeToSatin.convertPathToSatin(start,width)))
   }
   val route=AutoSatin.route(sections,black)
   require(route.discardedSatins==0 && route.groups.isNotEmpty()) { "Incomplete centerline satin" }
   for((i,op) in route.operations.withIndex()) if(op.underpath) {
    var cursor=op.start
    val later=route.operations.drop(i+1).filter { !it.underpath && it.element==op.element }.sortedBy { it.start }
    for(cover in later) if(cover.start<=cursor+1e-9) cursor=maxOf(cursor,cover.end)
    if(cursor<op.end-1e-9)total++
   }
  }
  return total
 }

 @Test fun verify() {
  File(out,"rows.jsonl").writeText("")
  val started=System.currentTimeMillis()
  val deadline=Long.MAX_VALUE
  val manifest=JSONObject().put("kana",org.json.JSONArray(((0x3041..0x3096)+(0x30a1..0x30fa)).map { code ->
   JSONObject().put("glyph",String(Character.toChars(code))).put("group",if(code<0x30a0)"hiragana" else "katakana")
  }))
  val items=(0 until manifest.getJSONArray("kana").length()).map { manifest.getJSONArray("kana").getJSONObject(it) }
  data class FontInput(val id:String,val face:Typeface,val cmap:FontCmap,val reference:Double,val referenceGlyph:String)
  val fonts=listOf("NotoSansJP.ttf","NotoSansSC.ttf","NotoSansCJKjp-Regular.otf").map { filename ->
   val file=File("${System.getProperty("kana.fonts","/tmp/myembro-cjk-satin-probe/fonts")}/$filename")
   val cmap=requireNotNull(FontCmap.read(file));val face=requireNotNull(Typeface.Builder(file).setWeight(400).build())
   val referenceGlyph=if(cmap.has("자")) "자" else "H"
   val points=AndroidGlyphOutlines.extract(referenceGlyph,face).flatten()
   emit(JSONObject().put("kind","font").put("font",file.nameWithoutExtension).put("weight",face.weight).put("bytes",file.length()).put("reference_glyph",referenceGlyph))
   FontInput(file.nameWithoutExtension,face,cmap,points.maxOf { it.second }-points.minOf { it.second },referenceGlyph)
  }
  var completed=0
  for(item in items)for(font in fonts) {
   if(System.currentTimeMillis()>=deadline) {
    emit(JSONObject().put("kind","finished").put("complete",false).put("completed",completed).put("expected",1584).put("reason","30-minute reporting deadline").put("elapsed_ms",System.currentTimeMillis()-started));return
   }
   val glyph=item.getString("glyph");val group=item.getString("group")
   val heights=if(group=="joyo") listOf(30.0,50.0) else listOf(15.0,20.0,30.0)
   val reference=font.reference
   if(!font.cmap.has(glyph)) {
    for(height in heights) { emit(JSONObject().put("kind","matrix").put("font",font.id).put("glyph",glyph).put("group",group).put("height",height).put("mode","satin").put("strokes",item.opt("strokes")).put("status","UNSUPPORTED").put("error","FontCmap missing glyph").put("saved",false));completed++ }
    continue
   }
   var components:Int?=null;var data:CenterlineSatinData?=null;var error:String?=null;var scanH=0;var scanV=0
   val prepStart=System.nanoTime()
   try {
    val contours=AndroidGlyphOutlines.extract(glyph,font.face)
    val raster=AndroidCenterlineGlyph.rasterize(glyph,font.face)
    fun runs(values:List<Byte>):Int { var prior=false;var count=0;for(v in values) { val ink=v==0.toByte();if(ink && !prior)count++;prior=ink };return count }
    scanH=(0 until raster.height).maxOf { y->runs((0 until raster.width).map { x->raster.pixels[y*raster.width+x] }) }
    scanV=(0 until raster.width).maxOf { x->runs((0 until raster.height).map { y->raster.pixels[y*raster.width+x] }) }
    val labels=HangulComponentOrder.label(raster.width,raster.height,raster.pixels,listOf(HangulComponentOrder.GlyphBounds(0,0,0,raster.width,raster.height)))
    components=labels.components.size
    val traced=CenterlineGlyph.extract(raster)
    data=CenterlineSatinData(traced.paths,List(traced.paths.size){0},traced.components,mapOf(0 to HangulSatin.measuredWidthSource(contours,reference)))
   } catch(e:Exception) { error="${e.javaClass.simpleName}: ${e.message}" }
   catch(e:StackOverflowError) { error="StackOverflowError during extraction" }
   val prepMs=(System.nanoTime()-prepStart)/1_000_000
   for(height in heights) {
    val row=JSONObject().put("kind","matrix").put("font",font.id).put("glyph",glyph).put("group",group).put("height",height).put("mode","satin")
     .put("strokes",item.opt("strokes")).put("components",components ?: JSONObject.NULL).put("reference_glyph",font.referenceGlyph)
     .put("scan_horizontal",scanH).put("scan_vertical",scanV).put("prep_ms",prepMs)
    if(item.has("selection"))row.put("selection",item.getString("selection"))
    val began=System.nanoTime()
    try {
     require(data!=null) { error ?: "Missing centerline" }
     row.put("uncovered_underpaths",uncovered(data,height,reference)).put("stroke_width_mm",data.widths.getValue(0)*height/reference)
     result(row,HangulSatin.generate(data,height,reference,black))
    } catch(e:Exception) { row.put("status","ERROR").put("error","${e.javaClass.simpleName}: ${e.message}").put("saved",false) }
    catch(e:StackOverflowError) { row.put("status","ERROR").put("error","StackOverflowError during satin generation").put("saved",false) }
    row.put("ms",(System.nanoTime()-began)/1_000_000);emit(row);completed++
   }
  }
  emit(JSONObject().put("kind","finished").put("complete",true).put("completed",completed).put("expected",1584).put("elapsed_ms",System.currentTimeMillis()-started))
 }
}
