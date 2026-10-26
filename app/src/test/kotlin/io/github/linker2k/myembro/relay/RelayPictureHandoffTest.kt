// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.relay

import io.github.linker2k.myembro.engine.agent.AgentScenario
import io.github.linker2k.myembro.ui.editor.*
import io.github.linker2k.myembro.ui.photo.LatestPictureInbox
import io.github.linker2k.myembro.ui.settings.EmbroiderySettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class RelayPictureHandoffTest {
    @Test fun importOpensLatestPictureWithoutEditorConsentPreparationOrDiskWrites() = runBlocking {
        val base=AgentScenario.builtin("myembro_logo.pes","logo","H100")
        val session=EditorSession(base)
        val executor=EditorAgentExecutor({session},{EmbroiderySettings()},{listOf(base.hoop)},publish={it()})
        val controller=executor.relayController();val revision=controller.session.revision
        val inbox=LatestPictureInbox()
        val attachments=RelayPhotoAttachments({_,_->error("must not decode")},{_,_->error("must not preview")},null)
        val tools=RelayPhotoTools(attachments,controller,{error("must not export")},{error("must not publish edits")},{false},
            openPicture={picture,title->val id=inbox.reserve();inbox.offer(id,bytes=picture.bytes,title=title,colors=picture.colors)})
        for(title in listOf("first","last")) {
            val result=tools.call("import_picture",buildJsonObject {
                put("format","svg");put("data","<svg viewBox=\"0 0 10 10\"><rect width=\"10\" height=\"10\"/></svg>")
                put("colors",3);put("title",title)
            })
            assertFalse(result.isError)
        }
        assertEquals("last",inbox.pending.value?.title)
        assertEquals(revision,controller.session.revision)
        assertEquals(base,controller.session.project)
        assertTrue(attachments.ids().isEmpty())
        val answer=RelayPicture.opened("svg","last")
        assertEquals(JsonPrimitive(false),answer["added"])
        assertEquals(JsonPrimitive("awaiting_user_confirmation"),answer["status"])
        println("RELAY_PICTURE_HANDOFF latest=last editor_mutations=0 disk_writes=0 awaiting_user_confirmation=true")
    }
}
