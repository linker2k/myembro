// SPDX-License-Identifier: GPL-3.0-or-later
// Calls existing model GlyphStrokeWidths; no width-estimation algorithm is implemented here.
import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.geom.PathIterator;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import kotlin.Pair;
import io.github.linker2k.myembro.engine.lettering.GlyphStrokeWidths;

public class MeasureSweepWidths {
 public static void main(String[] args) throws Exception {
  var render=new FontRenderContext(null,true,true);
  try(var out=new PrintWriter(args[2])) {
   out.println("font\tcodepoint\twidth_at_15mm\tsamples");
   for(String line:Files.readAllLines(Path.of(args[0]))) {
    var fields=line.split("\t"); var font=Font.createFont(Font.TRUETYPE_FONT,new File(fields[1])).deriveFont(128f);
    for(int code:args[1].codePoints().toArray()) {
     if(!font.canDisplay(code)) throw new IllegalArgumentException("Missing glyph "+fields[0]+" "+code);
     var shape=font.createGlyphVector(render,new String(Character.toChars(code))).getOutline();
     var contours=new ArrayList<List<Pair<Double,Double>>>(); List<Pair<Double,Double>> current=null;
     var iterator=shape.getPathIterator(null,0.1); double[] p=new double[6];
     while(!iterator.isDone()) {
      int type=iterator.currentSegment(p);
      if(type==PathIterator.SEG_MOVETO) {current=new ArrayList<>();contours.add(current);current.add(new Pair<>(p[0],p[1]));}
      else if(type==PathIterator.SEG_LINETO) current.add(new Pair<>(p[0],p[1]));
      else if(type==PathIterator.SEG_CLOSE && !current.isEmpty()) current.add(current.get(0));
      else throw new IllegalArgumentException("Unflattened outline");
      iterator.next();
     }
     var glyph=new GlyphStrokeWidths.Glyph(0,code,contours);
     var input=new GlyphStrokeWidths.FontOutlines(fields[0],shape.getBounds2D().getHeight(),List.of(glyph));
     var row=GlyphStrokeWidths.INSTANCE.measure(input,15.0,0.1).getRows().get(0);
     if(row.getMedianWidthMm()==null) throw new IllegalArgumentException("No measurable width");
     out.println(fields[0]+"\t"+code+"\t"+row.getMedianWidthMm()+"\t"+row.getSampleCount());out.flush();
    }
   }
  }
 }
}
