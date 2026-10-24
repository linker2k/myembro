// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.sewout

import io.github.linker2k.myembro.engine.edit.ProjectJson
import io.github.linker2k.myembro.engine.geometry.SvgPath
import io.github.linker2k.myembro.engine.inkstitch.elements.Stroke
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.EmbPatternAdapter
import io.github.linker2k.myembro.engine.inkstitch.stitch_plan.StitchPlanBuilder
import io.github.linker2k.myembro.engine.inkstitch.utils.Point
import io.github.linker2k.myembro.engine.io.PesWriter
import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.model.EmbThread
import kotlinx.serialization.json.*
import java.util.zip.GZIPInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.*
import kotlin.test.*

/** Seven representative public inputs. All fixtures/provenance are in the repository; no /tmp dependencies. */
class S1PolicyRegressionTest {
    private fun input(path: String) = checkNotNull(javaClass.getResourceAsStream("/$path")) { path }
    private fun assertGate(pattern: EmbPattern, expectedRepeatedBlock: Boolean = false) {
        val report = SewoutCheck.checkPes(PesWriter.write(pattern), options = SewoutOptions(origin=DesignOrigin.APP_GENERATED))
        assertTrue(report.analysisComplete)
        val blockers=report.findings.filter { it.severity==Severity.BLOCKER }
        if (expectedRepeatedBlock) {
            assertTrue(blockers.isNotEmpty())
            assertTrue(blockers.all { it.code==FindingCode.REPEATED_PENETRATION },blockers.toString())
        } else assertTrue(blockers.isEmpty(),blockers.toString())
    }
    private fun sewtest(stem: String) {
        val project = input("sewout/s1/$stem.myembro").bufferedReader().use { ProjectJson.decode(it.readText()) }
        // Historical S1 generated prefixes contain redundant zero moves; v6 must reject, never fix them here.
        assertGate(project.flatten(),expectedRepeatedBlock=true)
    }
    @Test fun runningText5mm() = sewtest("TXT05")
    @Test fun runningText8mm() = sewtest("TXT08")
    @Test fun runningText12mm() = sewtest("TXT12")
    @Test fun circleRunningOutline() = sewtest("CIRCLE")
    @Test fun squareRunningOutline() = sewtest("SQUARE")

    private fun oracle(id: String) {
        val root = input("inkstitch-oracle/$id.svg").use { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it).documentElement }
        val view = root.getAttribute("viewBox").split(Regex("\\s+")).map(String::toDouble)
        val widthMm = root.getAttribute("width").removeSuffix("mm").toDouble()
        val scalePx = widthMm / view[2] * (96.0/25.4)
        val path = root.getElementsByTagName("path").item(0) as org.w3c.dom.Element
        // These two fixed inputs contain straight M/L/H/V/Z segments only. Read actual SVG geometry.
        val paths = SvgPath.flatten(path.getAttribute("d")).map { line ->
            line.points.map { Point((it.x-view[0])*scalePx,(it.y-view[1])*scalePx) }
        }
        val groups = Stroke(paths,color=EmbThread(0xFFB74E35.toInt())).toStitchGroups(null)
        val pattern = EmbPatternAdapter.toEmbPattern(StitchPlanBuilder.fromGroups(groups))
        assertGate(pattern)
        val reference = input("inkstitch-oracle/$id.coordinates.json.gz").use { stream ->
            GZIPInputStream(stream).bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        }
        val actual = pattern.stitches.filter { it.command==Command.STITCH }.map { it.x/10.0 to it.y/10.0 }
        val expected = reference.getValue("needles_mm").jsonArray.map { row ->
            row.jsonArray.let { it[0].jsonPrimitive.double to it[1].jsonPrimitive.double }
        }
        assertTrue(abs(actual.size-expected.size).toDouble()/expected.size <= .05,
            "$id needle counts ${actual.size} vs ${expected.size}")
        fun centered(points: List<Pair<Double,Double>>): List<Pair<Double,Double>> {
            val x=(points.minOf { it.first }+points.maxOf { it.first })/2
            val y=(points.minOf { it.second }+points.maxOf { it.second })/2
            return points.map { it.first-x to it.second-y }
        }
        val a=centered(actual);val b=centered(expected)
        fun directed(from: List<Pair<Double,Double>>,to: List<Pair<Double,Double>>) = from.maxOf { p ->
            to.minOf { q -> hypot(p.first-q.first,p.second-q.second) }
        }
        assertTrue(max(directed(a,b),directed(b,a)) <= .2+1e-9,"$id Hausdorff exceeds 0.2mm")
    }
    @Test fun tiesDefaultMatchesOfficialRectangle() = oracle("ties--rectangle--008mm--upstream-default")
    @Test fun stitchPlanDefaultMatchesOfficialSharpCorner() = oracle("stitch_plan--sharp_corner--010mm--upstream-default")
}
