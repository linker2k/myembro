// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.agent

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.edit.commands.*
import kotlinx.serialization.json.*
import kotlin.test.*

class BlockToolsTest {
    private fun shape() = ShapeObject.fromSvg("shape", "hole", """<svg viewBox="0 0 30 30"><path fill="#ff0000" fill-rule="evenodd" d="M0,0H30V30H0Z M10,10H20V20H10Z"/></svg>""", 30.0)
    private fun args(s: String) = Json.parseToJsonElement(s).jsonObject
    @Test fun localAreaSubtractsHolesAndSettingsMatchCodec() {
        val obj = shape(); val p = Project(objects=listOf(obj))
        val result = ToolDispatcher().dispatch(p,"7","list_blocks",args("""{"objectId":"shape"}"""))
        assertTrue(result.ok, result.output.toString()); assertSame(p,result.project)
        assertEquals("7",result.output.getValue("revision").jsonPrimitive.content)
        val region=result.output.getValue("regions").jsonArray.single().jsonObject
        assertEquals(800.0,region.getValue("areaMm2").jsonPrimitive.double,0.01)
        assertEquals(ShapeObjectCodec.encodeBlock(obj.regions.single().stitch),region["stitch"])
        assertEquals("#%06X".format(java.util.Locale.ROOT, obj.regions.single().color.color and 0xFFFFFF),region.getValue("color").jsonPrimitive.content)
        assertTrue(result.output.getValue("meanderPatterns").jsonArray.isNotEmpty())
    }
    @Test fun missingAndFrozenObjectsRefuseInspection() {
        val k=ToolDispatcher();val p=Project()
        assertEquals("MISSING_OBJECT",k.dispatch(p,"0","list_blocks",args("""{"objectId":"missing"}""")).code)
        val frozen=StitchObject("frozen","frozen",shape().localPattern())
        assertEquals("INVALID_OBJECT_TYPE",k.dispatch(p.add(frozen),"0","list_blocks",args("""{"objectId":"frozen"}""")).code)
    }
    @Test fun jsonPatchIsExactlyExistingCommandAndInvalidRangesReject() {
        val p=Project(objects=listOf(shape()));val k=ToolDispatcher()
        val a=args("""{"transactionId":"t","objectId":"shape","regionIds":["region-0"],"change":{"angle":135}}""")
        val direct=assertIs<CommandResult.Applied>(apply(p,BlockTools.command(a)))
        val result=k.dispatch(p,"0","set_block_stitch",a)
        assertTrue(result.ok,result.output.toString());assertEquals(direct.project,result.project)
        for(change in listOf("{\"row_spacing_mm\":0.099}","{\"fill_method\":\"guided_fill\"}")) {
            val failure=k.dispatch(p,"0","set_block_stitch",JsonObject(a+("change" to Json.parseToJsonElement(change))))
            assertFalse(failure.ok);assertSame(p,failure.project)
            assertEquals(if("guided" in change) "SEWOUT_BLOCKED" else "INVALID_BLOCK_SETTINGS",failure.code)
        }
    }
    @Test fun malformedOptionalPatchesFailClosed() {
        val base=args("""{"transactionId":"t","objectId":"shape","regionIds":["region-0"],"change":{}}""")
        for(change in listOf("{\"angle\":\"135\"}","{\"angle\":null}","{\"unknown\":1}","{\"automatic_angle\":1}","{\"meander_scale_percent\":[]}"))
            assertNotNull(BlockTools.validate("set_block_stitch",JsonObject(base+("change" to Json.parseToJsonElement(change)))))
        assertNotNull(BlockTools.validate("set_block_stitch",JsonObject(base+("regionIds" to args("""{"ids":["region-0","region-0"]}""").getValue("ids")))))
        assertNull(BlockTools.validate("set_block_stitch",base))
    }
}
