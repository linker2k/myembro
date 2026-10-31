// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.lettering

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.lettering.*
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.SewingOptions
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlinx.coroutines.runBlocking
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KanaLetteringRenderingTest {
    @Test fun screenPathRendersKanaAndPreservesOfflineProjectOutput()=runBlocking {
        val context=RuntimeEnvironment.getApplication()
        for((text,expectedStyle) in mapOf("がぱ" to StitchStyle.SATIN,"プ" to StitchStyle.RUNNING,
            "かき" to StitchStyle.SATIN,"ご" to StitchStyle.SATIN)) {
            val automatic=automaticLetteringState(context,"noto-sans-kr",text,20.0)
            assertEquals(StitchStyle.SATIN,automatic.style)
            assertTrue(automatic.kanaSatin);assertFalse(automatic.centerlineSatin)
            val settings=LetteringSettings(text,"noto-sans-kr",20.0,1.0,LetteringAlignment.CENTER,
                false,LetteringCurve.STRAIGHT,0.0,stitchStyle=automatic.style)
            val rendered=renderLettering(context,settings,EmbThread(0xff000000.toInt(),"Black 40wt"),HoopSpec("Test",1000,1000),SewingOptions())
            val obj=rendered.textObject
            println("KANA_APP $text ${obj.stitchStyle} backend=${obj.localPattern().metadata["myembro.lettering.backend"]}")
            assertEquals(expectedStyle,obj.stitchStyle)
            if(expectedStyle==StitchStyle.SATIN)assertNotNull(obj.kanaSatin) else assertNull(obj.kanaSatin)
            assertTrue(obj.localPattern().stitchCount>0)
            assertNull(obj.centerlineSatin,"Kana must not enter the Hangul path")
            val replay=ProjectJson.decode(ProjectJson.encode(Project(objects=listOf(obj)))).objects.single() as TextObject
            assertContentEquals(PesWriter.write(obj.localPattern()),PesWriter.write(replay.localPattern()))
        }
    }
}
