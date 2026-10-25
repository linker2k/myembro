// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import android.content.Context
import io.github.linker2k.myembro.engine.agent.*
import io.github.linker2k.myembro.library.GeneratedPesOrigins
import io.github.linker2k.myembro.ui.settings.SettingsPreferences
import java.io.File

/** Adapter over the same designs directory and atomic named-save helper as the existing editor. */
internal class AndroidDocumentStore(private val folder:File,private val recordGenerated:(ByteArray)->Unit = {}):DocumentStore {
    constructor(context:Context):this(File(context.applicationContext.filesDir,"designs"),{ GeneratedPesOrigins(context.applicationContext).record(it) })
    private fun target(destinationId:String,name:String,kind:DocumentKind):File {
        require(destinationId=="library") { "Unknown library destination" }
        val leaf=documentFileName(name,kind)
        return designSaveTarget(folder,leaf,kind.extension).also { require(it.canonicalFile.parentFile==folder.canonicalFile) }
    }
    override fun recordGeneratedPes(bytes:ByteArray) { recordGenerated(bytes) }
    @Synchronized override fun inspect(destinationId:String,name:String,kind:DocumentKind):DocumentState? {
        val file=target(destinationId,name,kind)
        return if(file.exists()) DocumentState(file.name,documentDigest(file.readBytes())) else null
    }
    @Synchronized override fun write(destinationId:String,name:String,kind:DocumentKind,bytes:ByteArray,expectedVersion:String?):StoredDocument {
        val file=target(destinationId,name,kind)
        if(inspect(destinationId,name,kind)?.version!=expectedVersion)throw DocumentConflict("Library target changed")
        val saved=writeNamedDesign(folder,file.name,kind.extension,expectedVersion!=null,bytes)
        return StoredDocument(saved.name,saved.toString(),documentDigest(bytes))
    }
}
internal fun androidAgentSettings(context:Context)=SettingsResolver {
    val settings=SettingsPreferences(context).read()
    AgentSettings(settings.machineProfile?.id.orEmpty(),settings.machineModel,settings.defaultFrame.id,settings.defaultFrame.spec)
}

/** Phone host binding; reuse for the document and refresh frame bindings when preferences change. */
internal fun androidAgentDispatcher(context:Context):ToolDispatcher = ToolDispatcher(
    frames=SettingsPreferences(context).read().frames.associate { it.id to it.spec },
    fontResolver=io.github.linker2k.myembro.ui.lettering.AndroidTextFontResolver(context),
    documentStore=AndroidDocumentStore(context),settingsResolver=androidAgentSettings(context),
)

/** Ask AI kernel: the phone binding above plus the Ask AI frame set, design library and local emoji. */
internal fun androidAgentKernel(context:Context,fonts:io.github.linker2k.myembro.engine.agent.FontResolver,frames:Map<String,io.github.linker2k.myembro.engine.edit.HoopSpec>)=ToolDispatcher(
    frames=frames,fontResolver=fonts,documentStore=AndroidDocumentStore(context),settingsResolver=androidAgentSettings(context),
    designResolver=io.github.linker2k.myembro.library.AndroidDesignResolver(context),
    emojiResolver=io.github.linker2k.myembro.emoji.AndroidEmojiResolver(context),
)
