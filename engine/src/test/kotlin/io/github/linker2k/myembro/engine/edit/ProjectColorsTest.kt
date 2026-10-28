// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.edit

import io.github.linker2k.myembro.engine.io.PecThreads
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.io.PesReader
import io.github.linker2k.myembro.engine.model.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ProjectColorsTest {
    private val a=PecThreads.forIndex(5)
    private val b=PecThreads.forIndex(8)
    private val c=PecThreads.forIndex(14)
    private val pattern=EmbPattern(listOf(
        Stitch(0,0,Command.STITCH),Stitch(10,0,Command.STITCH),Stitch(0,10,Command.STITCH),
        Stitch(10,0,Command.COLOR_CHANGE),Stitch(10,10,Command.STITCH),Stitch(20,10,Command.STITCH),
        Stitch(20,10,Command.COLOR_CHANGE),Stitch(20,20,Command.STITCH),Stitch(30,20,Command.STITCH),
        Stitch(30,20,Command.END)),listOf(a,b,a))
    private fun project()=Project(objects=listOf(StitchObject("a","Synthetic",pattern)))

    @Test fun legacyDefaultAndNewAppearanceRoundTrip() {
        val original=project()
        val legacy=Json.parseToJsonElement(ProjectJson.encode(original)).jsonObject
        assertFalse("fabricColorArgb" in legacy);assertFalse("threadColors" in legacy)
        val old=ProjectJson.decode(legacy.toString())
        assertNull(old.fabricColorArgb);assertTrue(old.threadColors.isEmpty())
        assertEquals(original.flatten().stitches,old.flatten().stitches)
        val colored=original.copy(fabricColorArgb=0xff123456.toInt()).recolorThread(a,c)
        val restored=ProjectJson.decode(ProjectJson.encode(colored))
        assertEquals(colored.fabricColorArgb,restored.fabricColorArgb)
        assertEquals(colored.threadColors,restored.threadColors)
        assertEquals(colored.flatten().threads,restored.flatten().threads)
        assertEquals(colored.flatten().stitches,restored.flatten().stitches)
        assertEquals(colored.fabricColorArgb,restored.flatten().fabricColorArgb)
        assertSame((original.objects[0] as StitchObject).source,(colored.objects[0] as StitchObject).source)
    }

    @Test fun sameThreadInRepeatedBlocksAndObjectsChangesTogetherWithoutChangingAnyCommandsOrPesCoordinates() {
        val original=project().add(StitchObject("b","Second synthetic",pattern,transform=Transform(50.0,0.0)))
        val changed=original.recolorThread(a,c)
        assertEquals(listOf(a,b),original.usedThreads())
        assertEquals(listOf(c,b),changed.usedThreads())
        assertEquals(original.flatten().stitches,changed.flatten().stitches)
        assertEquals(original.flatten().threads.map { if(it.sameThread(a)) c else it },changed.flatten().threads)
        val before=PesReader.read(PesWriter.writeV6(original.flatten()))
        val after=PesReader.read(PesWriter.writeV6(changed.flatten()))
        assertEquals(before.stitches,after.stitches,"All actual PES needle positions AND commands stay identical")
        assertNotEquals(before.threads,after.threads)
        println("FABRIC_THREAD_PES_COORDINATES_AND_COMMANDS_IDENTICAL ${before.stitches.size}")
    }

    @Test fun fabricDoesNotChangePesBytesAndUndoRestoresAppearance() {
        val initial=project()
        val withFabric=initial.copy(fabricColorArgb=0xff000000.toInt())
        assertContentEquals(PesWriter.writeV6(initial.flatten()),PesWriter.writeV6(withFabric.flatten()))
        val history=History(initial)
        history.commit(withFabric.recolorThread(a,c))
        assertEquals(initial,history.undo())
        assertEquals(listOf(c,b),history.redo().usedThreads())
    }

    @Test fun textGeometryIsNotRegeneratedOrReplacedByAPaletteEdit() {
        val text=TextObject("text",text="ABC",thread=a)
        val original=Project(objects=listOf(text))
        val geometry=text.localPattern()
        val changed=original.recolorThread(a,c)
        assertSame(text,changed.objects.single())
        assertSame(geometry,(changed.objects.single() as TextObject).localPattern())
        assertEquals(original.flatten().stitches,changed.flatten().stitches)
        assertTrue(changed.flatten().threads.all { it.sameThread(c) })
    }
}
