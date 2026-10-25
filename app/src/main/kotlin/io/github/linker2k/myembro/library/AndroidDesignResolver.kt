// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.library

import android.content.Context
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.engine.agent.DesignAsset
import io.github.linker2k.myembro.engine.agent.DesignResolver
import java.io.File

/** Bind on the host's serialized IO/edit executor, using only existing installed app storage. */
class AndroidDesignResolver(context: Context) : DesignResolver {
    private val app=context.applicationContext
    private val library=DesignLibrary(app)
    private val sources=DesignSourceResolver(File(app.filesDir,"designs"))
    private val bundled by lazy {
        listOf(DesignAsset.read("builtin:myembro-logo",app.getString(R.string.library_logo_name),"myembro_logo.pes",
            app.assets.open(DefaultLogoInstaller.ASSET).use { it.readBytes() })) +
            BundledDesigns.files(app).mapNotNull { file -> runCatching {
                DesignAsset.read("builtin:${file.name}",file.nameWithoutExtension,file.name,file.readBytes())
            }.getOrNull() }
    }
    override fun builtins() = bundled.toList()
    override fun designs(): List<DesignAsset> {
        val favorites=app.getSharedPreferences("myembro_library",Context.MODE_PRIVATE)
            .getStringSet("favorites",emptySet()).orEmpty()
        return library.list().mapNotNull { file -> runCatching {
            val bytes=sources.resolve(file.name) ?: return@mapNotNull null
            DesignAsset("library:${file.name}",file.nameWithoutExtension,file.name,
                DesignFormats.read(bytes,file.name,sources),
                if(file.extension.equals("myembro",true)) null else bytes,
                file.lastModified(),file.name in favorites)
        }.getOrNull() }
    }
}
