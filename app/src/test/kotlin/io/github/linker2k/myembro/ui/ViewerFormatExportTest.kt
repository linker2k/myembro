// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui
import android.app.Application
import android.net.Uri
import androidx.compose.runtime.MutableState
import androidx.lifecycle.ViewModelStore
import io.github.linker2k.myembro.PesFileProvider
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.model.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[26])
class ViewerFormatExportTest {
    private fun viewer(bytes:ByteArray):ViewerViewModel {
        val vm=ViewerViewModel(RuntimeEnvironment.getApplication())
        fun field(name:String,value:Any?){ViewerViewModel::class.java.getDeclaredField(name).apply{isAccessible=true}.set(vm,value)}
        fun state(name:String,value:Any?){
            @Suppress("UNCHECKED_CAST")
            val s=ViewerViewModel::class.java.getDeclaredField(name+"\$delegate").apply{isAccessible=true}.get(vm) as MutableState<Any?>
            s.value=value
        }
        state("render",RenderData(EmbPattern(listOf(Stitch(10,10,Command.STITCH)),listOf(PecThreads.forIndex(5)))))
        state("fileName","rose.pes");field("sourceName","rose.pes");field("sourceBytes",bytes)
        return vm
    }
    @Test fun savePickerSnapshotsTheSelectedFormatAndExtension(){
        for(f in ExportFormat.entries){
            val vm=viewer(byteArrayOf());val store=ViewModelStore().apply{put("viewer",vm)}
            try{
                assertEquals("rose_2.${f.extension}",vm.prepareSave(f))
                val request=ViewerViewModel::class.java.getDeclaredField("pendingSave").apply{isAccessible=true}.get(vm)
                assertEquals(f,request.javaClass.getDeclaredField("format").apply{isAccessible=true}.get(request))
                vm.save(null)
            }finally{store.clear()}
        }
    }
    @Test fun blockedDesignNeverCreatesAShareFileInAnyFormat(){
        val p=EmbPattern(listOf(100 to -50,100 to 50,-100 to 50,-100 to -50,100 to -50).map{Stitch(it.first,it.second,Command.STITCH)},listOf(PecThreads.forIndex(5)))
        val bytes=PesWriter.write(EmbPattern(List(31){i->Stitch(i%2,(i/2)%2,Command.STITCH)},p.threads),PesWriteSettings(inkstitchCommands=true))
        val folder=java.io.File(RuntimeEnvironment.getApplication().cacheDir,"pes_exports")
        val before=folder.walkTopDown().filter{it.isFile}.map{it.path}.toSet()
        for(f in ExportFormat.entries){
            val vm=viewer(bytes);val store=ViewModelStore().apply{put("viewer",vm)}
            try {
                vm.share(f)
                val until=System.nanoTime()+5_000_000_000L
                while(vm.exporting&&System.nanoTime()<until){org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();Thread.sleep(10)}
                assertFalse(vm.exporting);assertNull(vm.shareUri);assertContains(vm.exportMessage.orEmpty(),"DENSITY_HOTSPOT")
                assertEquals(before,folder.walkTopDown().filter{it.isFile}.map{it.path}.toSet())
            }finally{store.clear()}
        }
    }

    @Test fun labelsAndSharedMimeFollowTheSelectedFormat(){
        val app=RuntimeEnvironment.getApplication()
        for(language in listOf("en","ko")){
            val config=android.content.res.Configuration(app.resources.configuration).apply{setLocale(java.util.Locale.forLanguageTag(language))}
            val resources=app.createConfigurationContext(config).resources
            for(f in ExportFormat.entries){
                assertEquals(if(language=="ko")"${f.name}로 저장" else "Save as ${f.name}",resources.getString(R.string.export_sheet_save,f.name))
                assertEquals(if(language=="ko")"${f.name} 파일 공유" else "Share ${f.name} file",resources.getString(R.string.export_sheet_share,f.name))
            }
        }
        assertEquals("application/octet-stream",PesFileProvider().getType(Uri.parse("content://export/rose.vp3")))
    }
}
