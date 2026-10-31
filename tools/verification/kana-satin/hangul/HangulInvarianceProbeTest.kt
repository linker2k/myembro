package io.github.linker2k.myembro.lettering

import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.engine.lettering.*
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.sewout.PesOutputSafety
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HangulInvarianceProbeTest {
 @Test fun recordAll() {
  val root=File(System.getProperty("hangul.out","/tmp/myembro-kana-dakuten/before")).apply { mkdirs() }
  val first=requireNotNull(System.getProperty("hangul.first","44032")).toInt();val last=requireNotNull(System.getProperty("hangul.last","55203")).toInt()
  val workers=requireNotNull(System.getProperty("hangul.workers","4")).toInt()
  val output=File(root,"hashes.jsonl");output.writeText("")
  val context=RuntimeEnvironment.getApplication();val face=context.resources.getFont(R.font.myembro_sans)
  val points=AndroidGlyphOutlines.extract("자",face).flatten()
  val reference=points.maxOf { it.second }-points.minOf { it.second }
  File(root,"contract.json").writeText(JSONObject().put("first",first).put("last",last).put("heights","15,20,30").put("font","R.font.myembro_sans/Noto Sans KR 400").put("weight",face.weight).put("reference",reference).put("workers",workers).toString(2))
  val done=AtomicInteger();val started=System.nanoTime();val pool=Executors.newFixedThreadPool(workers)
  fun emit(row:JSONObject) = synchronized(output) { output.appendText(row.toString()+"\n") }
  for(code in first..last)pool.submit {
   val glyph=String(Character.toChars(code));val black=EmbThread(0xff000000.toInt(),"Black 40wt")
   var data:CenterlineSatinData?=null;var prepError:String?=null
   try {
    val contours=AndroidGlyphOutlines.extract(glyph,face)
    val traced=CenterlineGlyph.extract(AndroidCenterlineGlyph.rasterize(glyph,face))
    data=CenterlineSatinData(traced.paths,List(traced.paths.size){0},traced.components,mapOf(0 to HangulSatin.measuredWidthSource(contours,reference)))
   } catch(e:Exception) { prepError="${e.javaClass.name}: ${e.message}" }
   catch(e:StackOverflowError) { prepError="StackOverflowError in preparation" }
   for(height in listOf(15.0,20.0,30.0)) {
    val row=JSONObject().put("codepoint",code).put("glyph",glyph).put("height",height)
    try {
     require(data!=null) { prepError ?: "No prepared centerline" }
     val pattern=HangulSatin.generate(data,height,reference,black)
     val bytes=PesWriter.write(pattern)
     val sha=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
     row.put("status","PES").put("sha256",sha).put("bytes",bytes.size).put("stitches",pattern.stitchCount)
    } catch(e:Exception) { row.put("status","ERROR").put("error","${e.javaClass.name}: ${e.message}") }
    catch(e:StackOverflowError) { row.put("status","ERROR").put("error","StackOverflowError in generation") }
    emit(row)
   }
   val n=done.incrementAndGet();if(n%100==0) { println("HANGUL $n/${last-first+1} elapsed_s=${(System.nanoTime()-started)/1e9}");System.out.flush() }
  }
  pool.shutdown();check(pool.awaitTermination(6,TimeUnit.HOURS)) { "Hangul probe timeout" }
  check(done.get()==last-first+1)
  File(root,"finished.json").writeText(JSONObject().put("glyphs",done.get()).put("cases",done.get()*3).put("seconds",(System.nanoTime()-started)/1e9).toString(2))
 }
}
