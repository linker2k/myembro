// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.model.*
import io.github.linker2k.myembro.ui.editor.block.BlockEditState
import kotlin.test.*

class ColorAppearanceRenderTest {
    @Test fun blockRegionsUseProjectThreadColorsWithoutChangingGeometryOrSettings() {
        val shape=ShapeObject.fromSvg("s","Synthetic", """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20"><rect width="20" height="20" fill="#cc3333"/></svg>""",20.0)
        val replacement=EmbThread(0xff3366cc.toInt())
        val base=Project(objects=listOf(shape),fabricColorArgb=0xff101010.toInt())
        val project=base.recolorThread(base.usedThreads().first(),replacement)
        val original=BlockEditState.of(shape)
        val edited=BlockEditState.of(shape,project)
        assertEquals(original.regions.map {it.id},edited.regions.map {it.id})
        original.regions.zip(edited.regions).forEach { (a,b) ->
            assertEquals(replacement.color,b.color);assertEquals(a.stitch,b.stitch)
            a.paths.zip(b.paths).forEach { (x,y) -> assertContentEquals(x.xs,y.xs);assertContentEquals(x.ys,y.ys) }
        }
        edited.restore(setOf(edited.regions.first().id))
        assertEquals(setOf(original.regions.first().id),edited.selected)
    }
    @Test fun layerRenderUsesAppearanceAndRetainsNeedleCommands() {
        val source=EmbThread(0xffcc3333.toInt())
        val replacement=EmbThread(0xff3366cc.toInt())
        val raw=EmbPattern(listOf(Stitch(0,0,Command.STITCH),Stitch(10,10,Command.STITCH)),listOf(source))
        val obj=StitchObject("s","Synthetic",raw)
        val base=Project(objects=listOf(obj))
        val changed=base.copy(threadColors=listOf(ProjectThreadColor(source,replacement)))
        val before=layerThumbnailPattern(base,obj)
        val after=layerThumbnailPattern(changed,obj)
        assertEquals(before.stitches,after.stitches)
        assertEquals(listOf(replacement),after.threads)
        assertEquals(listOf(source),layerThumbnailPattern(base,obj).threads)
        assertSame(obj,changed.objects.single())
    }
}
