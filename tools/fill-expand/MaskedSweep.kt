// SPDX-License-Identifier: GPL-3.0-or-later
// Args: repository root, output directory, optional-user-proxy image path (never copied to repo).
import io.github.linker2k.myembro.engine.inkstitch.svg.image.ColorDrawingPipeline
import io.github.linker2k.myembro.engine.inkstitch.svg.image.PictureFillMask
import io.github.linker2k.myembro.engine.inkstitch.svg.PIXELS_PER_MM
import io.github.linker2k.myembro.engine.inkstitch.stitches.FillGeometry
import io.github.linker2k.myembro.engine.geometry.*
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.sewout.*
import io.github.linker2k.myembro.engine.inkstitch.svg.StitchTypeRule
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.util.AffineTransformation
import org.locationtech.jts.algorithm.locate.IndexedPointInAreaLocator
import org.locationtech.jts.geom.Location
import org.locationtech.jts.operation.union.UnaryUnionOp
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.*
fun holes(preview:ColorDrawingPipeline.Preview,result:ColorDrawingPipeline.Result,e:Double):Pair<Int,Int> {
 val mask=preview.backgroundReferenceRegion
 val holes=FillGeometry.polygons(mask.geometry).flatMap { p -> (0 until p.numInteriorRing).map { p.factory.createPolygon(p.getInteriorRingN(it).coordinates) } }
 if(holes.isEmpty()) return 0 to 0
 val scale=PIXELS_PER_MM/10
 val closing=PictureFillMask.closing(preview.colorRegionUnion,e)
 val clip=PolygonRegion(AffineTransformation.scaleInstance(scale,scale).transform(closing.geometry))
 val shapes=result.regions.filter { !it.excluded && it.method==StitchTypeRule.Type.TATAMI }.map { r ->
  val native=PolygonRegion.fromPaths(r.paths.map { line -> Polyline(line.points.map { Point(it.x*10,it.y*10) },true) },FillRule.EVEN_ODD)
  if(e==0.0) native.geometry else {
   val svg=AffineTransformation.scaleInstance(scale,scale).transform(native.geometry)
   val expanded=PolygonRegion(FillGeometry.shrinkOrGrow(svg,e*PIXELS_PER_MM)).intersection(clip)
   AffineTransformation.scaleInstance(1/scale,1/scale).transform(expanded.geometry)
  }
 }
 val coverage=if(shapes.isEmpty()) mask.geometry.factory.createPolygon() else UnaryUnionOp.union(shapes)
 val fill=IndexedPointInAreaLocator(coverage);var total=0;var open=0
 for(hole in holes) {
  val loc=IndexedPointInAreaLocator(hole);val b=hole.envelopeInternal
  for(y in floor(b.minY).toInt()..ceil(b.maxY).toInt()) for(x in floor(b.minX).toInt()..ceil(b.maxX).toInt()) {
   val q=Coordinate(x+.5,y+.5)
   if(loc.locate(q)==Location.INTERIOR) { total++;if(fill.locate(q)==Location.EXTERIOR) open++ }
  }
 }
 return total to open
}
fun main(args:Array<String>) {
 val root=File(args[0]);val out=File(args[1]).apply { mkdirs() };val pes=File(out,"pes").apply { mkdirs() }
 File(out,"generation.tsv").printWriter().use { rows ->
 rows.println("input\texpand_mm\tstitches\tsewout\texcluded\trunning\tskipped_outlines\textent\thole_total_cells\thole_open_cells\tgenerate_ms\tfile")
 val inputs=listOf(Triple("face",File(root,"engine/src/test/resources/image-to-design/face.png"),30.0),Triple("robot",File(root,"engine/src/test/resources/image-to-design/robot.png"),30.0),Triple("proxy",File(args[2]),99.8))
 for((name,path,width) in inputs) {
  val src=ImageIO.read(path);val h=if(name=="proxy") src.height*192/src.width else 192
  val image=BufferedImage(192,h,BufferedImage.TYPE_INT_ARGB)
  image.createGraphics().apply { setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);drawImage(src,0,0,192,h,null);dispose() }
  if(name!="proxy")for(y in 105..108)for(x in 92..95)image.setRGB(x,y,0xfffefefe.toInt())
  val preview=ColorDrawingPipeline.trace(192,h,image.getRGB(0,0,192,h,null,0,192),width,4)
  // Construct mask once, outside generation timing; measured separately for transparency.
  val maskStart=System.nanoTime();preview.backgroundReferenceRegion
  println("MASK_MS\t$name\t${(System.nanoTime()-maskStart)/1e6}")
  for(e in listOf(0.0,0.1,0.2,0.3)) {
   val start=System.nanoTime();val result=ColorDrawingPipeline.generate(preview,expandMm=e);val ms=(System.nanoTime()-start)/1e6
   val bytes=PesWriter.write(result.pattern);val file=File(pes,"$name-$e.pes");file.writeBytes(bytes)
   val gate=SewoutCheck.checkPes(bytes,options=SewoutOptions(origin=DesignOrigin.APP_GENERATED))
   val b=PesReader.read(bytes).bounds!!;val extent="${b.minX},${b.minY},${b.maxX},${b.maxY}"
   val (total,open)=holes(preview,result,e)
   val row=listOf(name,e,result.pattern.stitchCount,gate.verdict,result.excludedCount,result.regions.count { !it.excluded && it.method==StitchTypeRule.Type.RUNNING },result.skippedOutlineCount,extent,total,open,ms,file.name).joinToString("\t")
   rows.println(row);rows.flush();println(row)
  }
 }
 }
}
