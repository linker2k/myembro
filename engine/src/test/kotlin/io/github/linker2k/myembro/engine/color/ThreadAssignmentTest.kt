// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.color

import io.github.linker2k.myembro.engine.io.*
import io.github.linker2k.myembro.engine.edit.*
import io.github.linker2k.myembro.engine.inkstitch.svg.VectorArt
import io.github.linker2k.myembro.engine.inkstitch.svg.image.ColorDrawingPipeline
import kotlin.test.*

class ThreadAssignmentTest {
    @Test fun matchesScikitImageSyntheticLabAndDeltaEOracle() {
        val lines=javaClass.getResourceAsStream("/color/ciede2000.tsv")!!.bufferedReader().readLines().filterNot { it.startsWith("#") }
        for(line in lines) {
            val v=line.split('\t');val a=v[0].removePrefix("0x").toInt(16);val b=v[1].removePrefix("0x").toInt(16)
            val x=ColorDifference.lab(a);val y=ColorDifference.lab(b)
            listOf(x.l,x.a,x.b,y.l,y.a,y.b).forEachIndexed { i,n->assertEquals(v[i+2].toDouble(),n,1e-10) }
            assertEquals(v[8].toDouble(),ColorDifference.ciede2000(a,b),1e-10)
            assertEquals(ColorDifference.ciede2000(a,b),ColorDifference.ciede2000(b,a),1e-10)
            assertEquals(0.0,ColorDifference.ciede2000(a,a),1e-10)
        }
        assertEquals(256,lines.size)
    }
    @Test fun publishedSharmaPairAndZeroChroma() {
        assertEquals(2.0425,ColorDifference.ciede2000(ColorDifference.Lab(50.0,2.6772,-79.7751),ColorDifference.Lab(50.0,0.0,-82.7485)),0.00005)
        assertEquals(0.0,ColorDifference.ciede2000(ColorDifference.Lab(50.0,0.0,0.0),ColorDifference.Lab(50.0,0.0,0.0)))
    }
    @Test fun closeColorsNeverMergeWhenCandidatesSuffice() {
        val result=ThreadAssignment.assign(listOf(DesignColor(0xffaaaa,2.0),DesignColor(0xffaaab,1.0)))
        assertEquals(2,result.assignments.map { it.thread.color }.toSet().size)
        assertTrue(result.warnings.isEmpty())
    }
    @Test fun brightnessPrecedesAreaAndInputOrder() {
        val result=ThreadAssignment.assign(listOf(DesignColor(0x111111,1000.0),DesignColor(0xeeeeee,1.0),DesignColor(0x777777,5.0)))
        assertEquals(listOf(0xeeeeee,0x777777,0x111111),result.assignments.map { it.rgb })
        val labs=ColorDifference.Lab(50.0,1.0,1.0)
        assertEquals(listOf(2,1),ThreadAssignment.order(listOf(ThreadAssignment.Prepared(1,1.0,labs),ThreadAssignment.Prepared(2,10.0,labs))).map { it.rgb })
    }
    @Test fun nearestUnusedThreadAndOwnedRestriction() {
        val owned=MyThreads(true,setOf(1,2,3))
        val result=ThreadAssignment.assign(listOf(DesignColor(0x222244,1.0),DesignColor(0x222245,2.0)),owned)
        val remaining=owned.candidates().toMutableList()
        result.assignments.forEach { a ->
            val expected=remaining.minBy { ColorDifference.ciede2000(a.rgb,it.color) }
            assertEquals(expected,a.thread);remaining.remove(a.thread)
        }
        assertTrue(result.assignments.all { it.thread in owned.owned })
    }
    @Test fun shortageReusesClosestAndWarnsOnlyAfterExhaustion() {
        val owned=MyThreads(true,setOf(1,2))
        val result=ThreadAssignment.assign(listOf(DesignColor(0x000000,1.0),DesignColor(0x777777,1.0),DesignColor(0xffffff,1.0)),owned)
        assertEquals(2,result.assignments.take(2).map { it.thread }.toSet().size)
        assertEquals(owned.owned.minBy { ColorDifference.ciede2000(result.assignments.last().rgb,it.color) },result.assignments.last().thread)
        assertEquals(listOf(ThreadAssignment.Shortage(3,2,1)),result.warnings)
        assertTrue(ThreadAssignment.assign(listOf(DesignColor(0xffffff,1.0),DesignColor(0,1.0)),owned).warnings.isEmpty())
    }
    @Test fun duplicateRgbAggregatesAreaAndEmptyPaletteNeedsNoThread() {
        val result=ThreadAssignment.assign(listOf(DesignColor(0xff123456.toInt(),2.0),DesignColor(0x123456,3.0)))
        assertEquals(1,result.assignments.size);assertEquals(5.0,result.assignments.single().area)
        assertEquals(result.assignments.single().thread,result.threadFor(0xff123456.toInt()))
        assertTrue(ThreadAssignment.assign(emptyList(),fallback=emptyList()).warnings.isEmpty())
    }
    private fun art()=VectorArt.parseSvg("""<svg viewBox="0 0 30 10"><path fill="#ffaaaa" d="M0 0H10V10H0Z"/><path fill="#ffaaab" d="M20 0H30V10H20Z"/></svg>""")
    @Test fun vectorRenderingUsesBatchAndReportsShortage() {
        val art=art();val result=VectorArt.render(art,30.0)
        assertEquals(2,result.parts.map { it.thread.color }.toSet().size)
        assertEquals(VectorArt.assignThreads(art).assignments.map { it.thread }.toSet(),result.parts.map { it.thread }.toSet())
        val limited=VectorArt.render(art.copy(myThreads=MyThreads(true,setOf(1))),30.0)
        assertTrue(limited.pattern.warnings.any { it.startsWith("THREAD_CANDIDATES_EXHAUSTED") })
    }
    @Test fun shapeCodecRetainsOwnedCandidatesAndOldDefaultStillLoads() {
        val shape=ShapeObject.fromArt("a","colors",art().copy(myThreads=MyThreads(true,setOf(1,2))),30.0)
        val restored=ProjectJson.decode(ProjectJson.encode(Project(objects=listOf(shape)))).objects.single() as ShapeObject
        assertEquals(shape.sourceArt.myThreads,restored.sourceArt.myThreads)
        assertEquals(shape.localPattern().threads,restored.localPattern().threads)
        assertEquals(shape.localPattern().stitches,restored.localPattern().stitches)
        val old=ShapeObject.fromArt("b","default",art(),30.0)
        val json=ProjectJson.encode(Project(objects=listOf(old)))
        assertFalse(json.contains("myThreads"))
        assertEquals(MyThreads(),(ProjectJson.decode(json).objects.single() as ShapeObject).sourceArt.myThreads)
    }
    @Test fun pictureUsesSameBatchAndWarnsForOwnedShortage() {
        val pixels=IntArray(40*40) { p->when { p%40 in 3..17 && p/40 in 3..36 -> 0xffff5555.toInt();p%40 in 22..36 && p/40 in 3..36 -> 0xffee5555.toInt();else->0xffffffff.toInt() } }
        val preview=ColorDrawingPipeline.trace(40,40,pixels,40.0,2)
        assertEquals(2,preview.layers.map { it.thread.color }.toSet().size)
        val limited=ColorDrawingPipeline.trace(40,40,pixels,40.0,2,myThreads=MyThreads(true,setOf(1)))
        assertEquals(1,limited.assignmentWarnings.size)
        assertTrue(ColorDrawingPipeline.generate(limited).pattern.warnings.any { it.startsWith("THREAD_CANDIDATES_EXHAUSTED") })
    }
}
