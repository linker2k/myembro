// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.agent

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.edit.commands.*
import io.github.linker2k.myembro.engine.emoji.*
import io.github.linker2k.myembro.engine.geometry.pictureWidthRange
import io.github.linker2k.myembro.engine.inkstitch.svg.VectorArt
import io.github.linker2k.myembro.engine.sewout.*
import kotlinx.serialization.json.*
import kotlin.test.*

class EmojiToolTest {
    private fun args(s: String)=Json.parseToJsonElement(s).jsonObject
    private fun prepare(width: Double=20.0,id: String="red-heart")=args("""{"emojiId":"$id","frameId":"H100","widthMm":$width}""")
    private fun add(result: String)=args("""{"transactionId":"t","resultId":"$result","objectId":"heart","name":"Heart"}""")
    private fun ready(kernel: ToolDispatcher,width: Double=20.0): ToolDispatcher.Result {
        val result=kernel.dispatch(Project(),"0","prepare_emoji",prepare(width))
        assertTrue(result.ok,result.output.toString());return result
    }
    @Test fun `heart twenty millimeters prepare add and prepare PES is PASS with generated origin`() {
        val kernel=ToolDispatcher();val result=ready(kernel)
        val added=kernel.dispatch(Project(),"0","add_generated_art",add(result.output.string("resultId")))
        assertTrue(added.ok,added.output.toString())
        val obj=assertIs<StitchObject>(added.project.find("heart"));assertNull(obj.sourceBytes)
        assertEquals(DesignOrigin.APP_GENERATED,SewoutCheck.originOf(added.project.flatten()))
        val checked=kernel.dispatch(added.project,"0","prepare_pes",args("""{"projectRevision":"0","machineId":"se700","frameId":"H100","pesVersion":"V6"}"""))
        assertTrue(checked.ok,checked.output.toString());assertNotNull(checked.pesBytes)
        assertEquals("PASS",checked.output["checks"]!!.jsonObject["report"]!!.jsonObject.string("verdict"))
        println("EMOJI_HEART 20mm prepare -> add -> prepare_pes PASS")
    }
    @Test fun `minimum maximum and plus minus one tenth use the existing picture policy`() {
        val resolver=BundledEmojiResolver();val e=resolver.catalog.emoji.single { it.id=="red-heart" }
        val range=pictureWidthRange(HoopSpec.H100,VectorArt.aspectRatio(VectorArt.parseSvg(resolver.localSvg(e)!!.toString(Charsets.UTF_8))))
        assertEquals(20.0,range.start);assertEquals(99.9,range.endInclusive)
        val kernel=ToolDispatcher()
        for(w in listOf(range.start-0.1,range.endInclusive+0.1)) {
            val result=kernel.dispatch(Project(),"0","prepare_emoji",prepare(w))
            assertEquals("WIDTH_OUT_OF_RANGE",result.code);assertNull(result.output["resultId"])
        }
        for(w in listOf(range.start,range.start+0.1,range.endInclusive-0.1,range.endInclusive)) ready(kernel,w)
    }
    @Test fun `bundled thirty korean search and unavailable rows retain catalog behavior`() {
        val kernel=ToolDispatcher()
        val local=kernel.dispatch(Project(),"0","list_emoji",args("""{"query":"","groupId":""}"""))
        val rows=local.output.getValue("emoji").jsonArray
        assertEquals(30,rows.size);assertTrue(rows.all { it.jsonObject.getValue("available").jsonPrimitive.boolean })
        val hearts=kernel.dispatch(Project(),"0","list_emoji",args("""{"query":"하트","groupId":""}"""))
        assertTrue(hearts.output.getValue("emoji").jsonArray.any { it.jsonObject.string("id")=="red-heart" })
        val cactus=kernel.dispatch(Project(),"0","prepare_emoji",prepare(id="cactus"))
        assertEquals("DOWNLOAD_REQUIRED",cactus.code);assertNull(cactus.output["resultId"])
    }
    @Test fun `downloaded entries are listed and prepared locally without a downloader`() {
        val bundled=BundledEmojiResolver();val heart=bundled.catalog.emoji.single {it.id=="red-heart"}
        val local=heart.copy(id="local-heart",bundled=false)
        var reads=0
        val resolver=object: EmojiResolver {
            override val catalog=EmojiCatalog(bundled.catalog.commit,bundled.catalog.rawBase,bundled.catalog.emoji+local)
            override fun isAvailable(emoji: CatalogEmoji)=emoji.bundled || emoji.id==local.id
            override fun localSvg(emoji: CatalogEmoji): ByteArray? {reads++;return if(emoji.id==local.id) bundled.localSvg(heart) else bundled.localSvg(emoji)}
        }
        val kernel=ToolDispatcher(emojiResolver=resolver)
        val listed=kernel.dispatch(Project(),"0","list_emoji",args("""{"query":"","groupId":""}"""))
        assertEquals(31,listed.output.getValue("emoji").jsonArray.size);assertEquals(0,reads)
        assertTrue(kernel.dispatch(Project(),"0","prepare_emoji",prepare(id=local.id)).ok)
        assertEquals(1,reads)
    }
    @Test fun `unknown emoji and forged result ids reject and checksum mismatch fails closed`() {
        val kernel=ToolDispatcher();val p=Project()
        assertEquals("EMOJI_NOT_FOUND",kernel.dispatch(p,"0","prepare_emoji",prepare(id="../../secret")).code)
        val bad=kernel.dispatch(p,"0","add_generated_art",add("invented"))
        assertEquals("RESULT_NOT_FOUND",bad.code);assertSame(p,bad.project)
        val base=BundledEmojiResolver()
        val corrupt=object: EmojiResolver by base { override fun localSvg(emoji: CatalogEmoji)="bad".toByteArray() }
        assertEquals("ASSET_INTEGRITY_ERROR",ToolDispatcher(emojiResolver=corrupt).dispatch(p,"0","prepare_emoji",prepare()).code)
        val unreadable=object: EmojiResolver by base { override fun localSvg(emoji: CatalogEmoji): ByteArray? = throw java.io.IOException("missing") }
        assertEquals("EMOJI_READ_FAILED",ToolDispatcher(emojiResolver=unreadable).dispatch(p,"0","prepare_emoji",prepare()).code)
    }
    @Test fun `prepared geometry equals the unchanged vector path and preparation does not touch history`() {
        val resolver=BundledEmojiResolver();val kernel=ToolDispatcher(emojiResolver=resolver);val session=EditSession()
        val controller=kernel.bind(session);val before=session.snapshot()
        val result=controller.dispatch("prepare_emoji",prepare())
        assertTrue(result.ok,result.output.toString());assertEquals(before,session.snapshot())
        val id=result.output.string("resultId")
        assertTrue(controller.dispatch("begin_edit_turn",args("""{"transactionId":"t","expectedRevision":"0"}""")).ok)
        assertTrue(controller.dispatch("add_generated_art",add(id)).ok)
        assertEquals(before.project,session.project)
        assertTrue(controller.dispatch("commit_edit_turn",args("""{"transactionId":"t","expectedRevision":"0"}""")).ok)
        val e=resolver.catalog.emoji.single {it.id=="red-heart"}
        val expected=VectorArt.render(VectorArt.parseSvg(resolver.localSvg(e)!!.toString(Charsets.UTF_8)),20.0,name=e.name).pattern
        val actual=(session.project.find("heart") as StitchObject).source
        assertEquals(expected.stitches,actual.stitches);assertEquals(expected.threads,actual.threads);assertEquals(expected.metadata,actual.metadata)
        assertEquals(1,session.snapshot().undoCount);session.undo(session.revision);assertEquals(before.project,session.project)
        // Opaque results belong to one dispatcher/document lifetime, never supplied as arbitrary geometry.
        assertEquals("RESULT_NOT_FOUND",ToolDispatcher().dispatch(Project(),"0","add_generated_art",add(id)).code)
    }
    @Test fun `photo accepts attachment references only and requires an approved attachment`() {
        val p=Project();val kernel=ToolDispatcher()
        val a=args("""{"attachmentId":"picked-photo","mode":"outline","colors":2,"widthMm":20}""")
        assertEquals("ATTACHMENT_NOT_FOUND",kernel.dispatch(p,"0","prepare_photo",a).code)
        for(k in listOf("assetId","path","url","bytes"))
            assertEquals("INVALID_ARGUMENTS",kernel.dispatch(p,"0","prepare_photo",JsonObject(a+(k to JsonPrimitive("anything")))).code)
    }
}
