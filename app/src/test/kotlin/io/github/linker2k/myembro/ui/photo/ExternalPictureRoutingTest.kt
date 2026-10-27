// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.photo

import android.content.Intent
import io.github.linker2k.myembro.R
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.*

class ExternalPictureRoutingTest {
    private data class Source(val uri:String,val bytes:ByteArray)
    private val png=byteArrayOf(137.toByte(),80,78,71,13,10,26,10)
    private val svg="<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>".toByteArray()
    private val bmp="BM".toByteArray()+ByteArray(12)
    private val pes="#PES0001".toByteArray()
    private val dst="LA:synthetic".toByteArray()
    private val zip=byteArrayOf(80,75,3,4)
    private fun route(action:String,data:Source?=null,streams:List<Source> = emptyList(),clips:List<Source> = emptyList()):PictureRoute<Source> {
        val sources=externalInputUris(action,data,streams,clips) { it.uri.startsWith("content:") || it.uri.startsWith("file:") }
        return routePictureSources(sources) { PictureIntake.sniff(readPicturePrefix(ByteArrayInputStream(it.bytes),"opaque"),"opaque") }
    }
    @Test fun viewSendAndSendMultipleSniffContentAndKeepEmbroideryInImporter() {
        var rows=0
        for(action in listOf(Intent.ACTION_VIEW,Intent.ACTION_SEND,Intent.ACTION_SEND_MULTIPLE)) {
            for((i,bytes) in listOf(png,svg,bmp,pes,dst,zip).withIndex()) {
                val source=Source("content://sender/opaque-$i",bytes)
                val result=if(action==Intent.ACTION_VIEW) route(action,data=source) else route(action,streams=listOf(source))
                if(i<3) { assertEquals(source,result.picture);assertTrue(result.imports.isEmpty()) }
                else { assertNull(result.picture);assertEquals(listOf(source),result.imports) }
                rows++
            }
        }
        println("EXTERNAL_PICTURE_ROUTING rows=$rows PASS (VIEW/SEND/SEND_MULTIPLE x PNG/SVG/BMP/PES/DST/ZIP)")
    }
    @Test fun multipleShareUsesLastPictureAndNeverImportsDiscardedPictures() {
        val a=Source("content://sender/first",png);val b=Source("content://sender/last",bmp)
        val design=Source("content://sender/design",pes)
        val result=route(Intent.ACTION_SEND_MULTIPLE,streams=listOf(a,design,b),clips=listOf(a,design,b))
        assertEquals(b,result.picture);assertEquals(listOf(design),result.imports)
    }
    @Test fun clipOnlyDataOnlyAndFileSharesUseTheSamePictureRoute() {
        for(action in listOf(Intent.ACTION_SEND,Intent.ACTION_SEND_MULTIPLE)) {
            for(scheme in listOf("content","file")) {
                val source=Source("$scheme://sender/image",png)
                assertEquals(source,route(action,clips=listOf(source)).picture)
                assertEquals(source,route(action,data=source).picture)
                assertEquals(source,route(action,streams=listOf(source)).picture)
            }
        }
        assertNull(route(Intent.ACTION_SEND,data=Source("https://never-fetch/image",png)).picture)
    }
    @Test fun imageRecognitionDoesNotDrainTheProvidersStream() {
        val input=object:InputStream() {
            var reads=0
            override fun read():Int=error("Use bulk reads")
            override fun read(b:ByteArray,off:Int,len:Int):Int {
                check(reads++==0) { "Provider stream must not be drained after image magic" }
                png.copyInto(b,off);return png.size
            }
        }
        assertContentEquals(png,readPicturePrefix(input,"opaque"))
    }
    @Test fun layerAndBlockTitleResourcesFollowTheSelectedMode() {
        assertEquals(R.string.photo_color_name,photoObjectName(PhotoMode.COLOR))
        assertEquals(R.string.photo_name,photoObjectName(PhotoMode.SILHOUETTE))
        assertEquals(R.string.photo_outline_name,photoObjectName(PhotoMode.OUTLINE))
        assertEquals(3,PhotoMode.entries.map(::photoObjectName).toSet().size)
    }
}
