// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import io.github.linker2k.myembro.engine.geometry.SvgPath
import io.github.linker2k.myembro.engine.geometry.Affine2D
import io.github.linker2k.myembro.engine.inkstitch.svg.image.autotrace.*
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.json.*
import kotlin.test.*

/** Frozen AndroidCenterlineGlyph chain from 95b6e59; no platform rasterizer runs in this test. */
class CenterlineGlyphTest {
    private fun bytes(file:String)=checkNotNull(javaClass.getResourceAsStream("/lettering/centerline-glyph/$file")).use { it.readBytes() }
    private fun fixtures():Map<String,BinaryGlyphRaster> {
        val manifest=Json.parseToJsonElement(bytes("manifest.json").decodeToString()).jsonObject
        return manifest.getValue("glyphs").jsonArray.associate { entry ->
            val v=entry.jsonObject
            fun number(key:String)=v.getValue(key).jsonPrimitive.int
            val raw=bytes(v.getValue("file").jsonPrimitive.content)
            assertEquals(v.getValue("sha256").jsonPrimitive.content,
                MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it.toInt() and 255) })
            var offset=0
            repeat(3) { offset=raw.indexOf(10.toByte(),offset)+1 }
            v.getValue("character").jsonPrimitive.content to BinaryGlyphRaster(number("width"),number("height"),
                raw.copyOfRange(offset,raw.size),RasterToSource(number("left"),number("top")))
        }
    }
    private fun ByteArray.indexOf(value:Byte,start:Int):Int=(start until size).first { this[it]==value }

    @Test fun fourGlyphsAndTwoNamesAreBitExactAtThreeHeights() {
        val rasters=fixtures()
        val old=rasters.mapValues { (_,raster)->original(raster) }
        val moved=rasters.mapValues { (_,raster)->
            val input=raster.pixels.copyOf()
            CenterlineGlyph.extract(raster).also { assertContentEquals(input,raster.pixels) }
        }
        for(character in rasters.keys) {
            assertContentEquals(centerlineBytes(old.getValue(character)),centerlineBytes(moved.getValue(character)))
            assertEquals(old.getValue(character),moved.getValue(character))
        }
        var cases=0
        for(text in listOf("정민","안녕")) for(height in listOf(10.0,15.0,20.0)) {
            fun data(results:Map<String,CenterlineGlyph.Result>):CenterlineSatinData {
                val glyphs=text.mapIndexed { index,c->LayoutGlyph(index,1000.0,results.getValue(c.toString()).paths) }
                val layout=layoutGlyphs(listOf(glyphs),1000.0,height,1.0,LetteringAlignment.CENTER,9.0)
                // Fixed source-unit fixture width shared by both branches, not an automatic width rule.
                return CenterlineSatinData(layout.contours,layout.characters,
                    text.flatMap { results.getValue(it.toString()).components },text.indices.associateWith { 75.0 })
            }
            val before=data(old);val after=data(moved)
            assertEquals(before,after)
            val thread=EmbThread(0xff000000.toInt(),"Black 40wt")
            val expected=HangulSatin.generate(before,height,1000.0,thread)
            val actual=HangulSatin.generate(after,height,1000.0,thread)
            assertContentEquals(stitchBytes(expected),stitchBytes(actual),"$text $height ordered stitches")
            assertEquals(expected.threads,actual.threads);assertEquals(expected.metadata,actual.metadata)
            assertEquals(expected.warnings,actual.warnings)
            assertContentEquals(PesWriter.write(expected),PesWriter.write(actual),"$text $height PES bytes")
            println("CENTERLINE_MOVE PASS $text height_mm=$height stitches=${actual.stitchCount} centerline_bits=exact stitch_bytes=exact PES_bytes=exact")
            cases++
        }
        assertEquals(6,cases)
    }
    @Test fun rasterTransformPreservesLegacyOperationOrderAndSupportsOtherScales() {
        for(left in listOf(-3,0,17)) for(top in listOf(-106,-7,0)) for(x in listOf(0.0,8.0,12.3456789)) {
            val y=x+0.123456789
            val output=RasterToSource(left,top).toSource(x,y)
            assertEquals(((x-8+left)*1000/128).toBits(),output.first.toBits())
            assertEquals(((y-8+top)*1000/128).toBits(),output.second.toBits())
        }
        assertEquals(10.0 to 30.0,RasterToSource(2,3,1,2,200,100).toSource(4.0,14.0))
    }
    @Test fun blankAndInvalidRastersHaveExplicitContracts() {
        val blank=BinaryGlyphRaster(17,17,ByteArray(17*17) { 255.toByte() },RasterToSource(0,0))
        assertEquals(original(blank),CenterlineGlyph.extract(blank))
        assertEquals(CenterlineGlyph.Result(emptyList(),emptyList()),CenterlineGlyph.extract(blank))
        assertFailsWith<IllegalArgumentException> { BinaryGlyphRaster(0,17,byteArrayOf(),RasterToSource(0,0)) }
        assertFailsWith<IllegalArgumentException> { BinaryGlyphRaster(17,17,byteArrayOf(),RasterToSource(0,0)) }
        assertFailsWith<IllegalArgumentException> { BinaryGlyphRaster(1,1,byteArrayOf(1),RasterToSource(0,0)) }
    }
    private fun centerlineBytes(result:CenterlineGlyph.Result):ByteArray=ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(result.paths.size)
            result.paths.forEach { path ->out.writeInt(path.size);path.forEach { (x,y)->out.writeLong(x.toBits());out.writeLong(y.toBits()) } }
            result.components.forEach(out::writeInt)
        }
    }.toByteArray()
    private fun stitchBytes(pattern:EmbPattern):ByteArray=ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(pattern.stitches.size)
            pattern.stitches.forEach { out.writeInt(it.x);out.writeInt(it.y);out.writeUTF(it.command.name) }
        }
    }.toByteArray()
    private fun original(raster:BinaryGlyphRaster):CenterlineGlyph.Result {
        val width=raster.width;val height=raster.height;val pixels=raster.pixels
        val left=raster.transform.left;val top=raster.transform.top
        val labeling=HangulComponentOrder.label(width,height,pixels,listOf(HangulComponentOrder.GlyphBounds(0,0,0,width,height)))
        val paths=AutoTraceCenterline.trace(width,height,AutoTraceThinning.thin(width,height,pixels))
        val fitted=AutoTraceFit.fit(paths);val output=mutableListOf<List<Pair<Double,Double>>>();val components=mutableListOf<Int>()
        val owners=paths.map { labeling.component(it).id }
        for((order,component) in labeling.ordered.withIndex()) {
            val indices=paths.indices.filter { owners[it]==component.id }
            require(indices.isNotEmpty()) { "Ink component has no centerline" }
            for(index in indices) {
                fun v(segment:AutoTraceFit.Segment,i:Int,axis:Int):String {
                    val p=segment.v[i];val number=if(axis==0)p.x else height.toFloat()-p.y
                    return String.format(Locale.ROOT,"%.6g",number)
                }
                val d=buildString {
                    val segments=fitted[index].segments;require(segments.isNotEmpty())
                    append("M ${v(segments.first(),0,0)} ${v(segments.first(),0,1)} ")
                    for(s in segments) if(s.degree==1)append("L ${v(s,3,0)} ${v(s,3,1)} ")
                    else append("C ${v(s,1,0)} ${v(s,1,1)} ${v(s,2,0)} ${v(s,2,1)} ${v(s,3,0)} ${v(s,3,1)} ")
                }
                val points=SvgPath.flatten(d,Affine2D.IDENTITY,tolerance=0.1).single().points
                output+=points.map { (it.x-8+left)*1000/128 to (it.y-8+top)*1000/128 }
                components+=order
            }
        }
        return CenterlineGlyph.Result(output,components)
    }
}
