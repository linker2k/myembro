// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.photo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-memory only: latest input wins, including an older asynchronous URI inspection. */
class LatestPictureInbox {
    class Request(val id:Long,val uri:String?,val bytes:ByteArray?,val title:String?,val colors:Int?)
    private var revision=0L
    private val value=MutableStateFlow<Request?>(null)
    val pending=value.asStateFlow()
    @Synchronized fun reserve():Long { value.value=null;return ++revision }
    @Synchronized fun offer(id:Long,uri:String?=null,bytes:ByteArray?=null,title:String?=null,colors:Int?=null):Boolean {
        if(id!=revision) return false
        require((uri==null)!=(bytes==null))
        value.value=Request(id,uri,bytes?.copyOf(),title,colors);return true
    }
    @Synchronized fun take(id:Long):Request? = value.value?.takeIf { it.id==id }?.also { value.value=null }
}
object PictureInbox {
    const val ACTION_OPEN="io.github.linker2k.myembro.action.OPEN_PICTURE"
    val requests=LatestPictureInbox()
}
