// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.model.*
import io.github.linker2k.myembro.ui.*
import io.github.linker2k.myembro.ui.editor.block.*
import io.github.linker2k.myembro.ui.theme.MyembroTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[34],qualifiers="en-rUS-w480dp-h1000dp")
class ColorRenderingUiTest {
    @get:Rule val compose=createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val red=EmbThread(0xffcc3333.toInt())
    private val blue=EmbThread(0xff3366cc.toInt())
    private val pattern=EmbPattern(listOf(Stitch(0,0,Command.STITCH),Stitch(100,0,Command.STITCH)),listOf(red))

    /** Software draw avoids Android Window PixelCopy, which does not receive frames in Robolectric. */
    private fun pixel(tag:String, corner:Boolean=false, leftEdge:Boolean=false): Int {
        compose.waitForIdle()
        val bounds=compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow
        val point=if(leftEdge) Offset(bounds.left+.5f,bounds.center.y) else if(corner) Offset(bounds.left+2,bounds.top+2) else bounds.center
        var result=0
        compose.runOnIdle {
            val root=compose.activity.findViewById<View>(android.R.id.content)
            val origin=IntArray(2);root.getLocationInWindow(origin)
            val image=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888)
            root.draw(Canvas(image))
            result=image.getPixel((point.x-origin[0]).toInt(),(point.y-origin[1]).toInt())
            image.recycle()
        }
        return result
    }
    private fun assertClipped(dark:Boolean) {
        compose.setContent { MyembroTheme(darkTheme=dark) {
            Column {
                Box(Modifier.fillMaxWidth().height(40.dp).background(Color.Red).testTag("title-area"))
                StitchCanvas(RenderData(pattern),Int.MAX_VALUE,Hoop.H100,false,0,
                    Modifier.fillMaxWidth().height(160.dp),0xff123456.toInt(),0xff999999.toInt())
                Box(Modifier.fillMaxWidth().height(40.dp).background(Color.Blue).testTag("below-preview"))
            }
        } }
        assertEquals(0xffff0000.toInt(),pixel("title-area"),"Preview must not paint over the sheet title")
        assertEquals(0xff0000ff.toInt(),pixel("below-preview"))
    }
    @Test fun stitchCanvasDoesNotPaintOverLightSheetTitle()=assertClipped(false)
    @Test fun stitchCanvasDoesNotPaintOverDarkSheetTitle()=assertClipped(true)

    @Test fun unchangedLayerObjectRepaintsWhenAppearanceChangesAndUndoRestoresIt() {
        val obj=StitchObject("s","Synthetic",pattern)
        val base=Project(objects=listOf(obj),fabricColorArgb=0xffabcdef.toInt())
        var project by mutableStateOf(base)
        compose.setContent { MyembroTheme { LayerThumbnail(obj,project,Modifier.size(100.dp).testTag("layer")) } }
        val before=pixel("layer")
        assertEquals(base.fabricColorArgb,pixel("layer",true))
        compose.runOnIdle { project=base.copy(threadColors=listOf(ProjectThreadColor(red,blue))) }
        assertNotEquals(before,pixel("layer"),"Same object instance must repaint its thread color")
        compose.runOnIdle { project=project.copy(fabricColorArgb=0xff101010.toInt()) }
        assertEquals(0xff101010.toInt(),pixel("layer",true))
        compose.runOnIdle { project=base }
        assertEquals(before,pixel("layer"))
    }
    @Test fun blackSwatchHasAVisibleOutlineOnTheSameDarkBackground() {
        val black=0xff151515.toInt()
        compose.setContent { MyembroTheme(darkTheme=true) {
            Box(Modifier.background(Color(black)).padding(16.dp)) {
                ColorSwatch(black,Modifier.testTag("black-swatch"))
            }
        } }
        assertEquals(black,pixel("black-swatch"))
        assertNotEquals(black,pixel("black-swatch",leftEdge=true))
    }
    @Test fun blockCanvasPaintsTheSelectedFabricAndResolvedThread() {
        val state=BlockEditState(listOf(BlockRegion("s",listOf(RegionPath(doubleArrayOf(0.0,10.0,10.0,0.0),
            doubleArrayOf(0.0,0.0,10.0,10.0),true)),blue.color,BlockStitchDraft())))
        compose.setContent { MyembroTheme {
            RegionCanvas(state,Modifier.size(160.dp).testTag("blocks"),Color(0xff123456)) { _,_,_ -> }
        } }
        assertEquals(blue.color,pixel("blocks"))
        assertEquals(0xff123456.toInt(),pixel("blocks",true))
    }
}
