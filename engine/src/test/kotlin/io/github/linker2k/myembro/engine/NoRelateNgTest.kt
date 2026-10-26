// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine

import java.io.File
import java.net.URLClassLoader
import kotlin.test.*

/** Regression for ART VerifyError on the phone. Engine source must never opt into that package. */
class NoRelateNgTest {
    private val forbidden = listOf("org", "locationtech", "jts", "operation", "relateng").joinToString(".")

    @Test fun engineSourcesDoNotReferenceUnsupportedRelationPackage() {
        val root=File("src")
        assertTrue(root.isDirectory)
        val sources=root.walkTopDown().filter { it.isFile && it.extension in setOf("kt","java") }.toList()
        assertTrue(sources.isNotEmpty())
        val violations=sources.flatMap { file -> file.readLines().mapIndexedNotNull { i,line ->
            "${file.relativeTo(root)}:${i+1}".takeIf { forbidden in line || forbidden.replace('.','/') in line }
        } }
        assertEquals(emptyList(),violations,"Unsupported JTS package references: $violations")
        println("NO_UNSUPPORTED_RELATION_PACKAGE sources=${sources.size} violations=${violations.size}")
    }

    @Test fun classicRelationRunsWithUnsupportedClassesCompletelyUnavailable() {
        val jar=org.locationtech.jts.geom.Geometry::class.java.protectionDomain.codeSource.location
        var forbiddenLoads=0
        val loader=object : URLClassLoader(arrayOf(jar),null) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if(name.startsWith(forbidden)) { forbiddenLoads++;throw ClassNotFoundException("Forbidden relation engine") }
                return super.loadClass(name,resolve)
            }
        }
        loader.use {
            val reader=it.loadClass("org.locationtech.jts.io.WKTReader").getConstructor().newInstance()
            val read=reader.javaClass.getMethod("read",String::class.java)
            val geometry=it.loadClass("org.locationtech.jts.geom.Geometry")
            val relate=it.loadClass("org.locationtech.jts.operation.relate.RelateOp").getMethod("relate",geometry,geometry)
            val inputs=listOf("LINESTRING(0 0,10 10)","LINESTRING(0 10,10 0)","LINESTRING(10 10,20 0)",
                "LINESTRING(2 2,8 8)","LINESTRING(0 0,10 10,0 10,10 0,0 0)")
            val matrices=inputs.map { wkt -> read.invoke(reader,wkt) }
            var crossing=0
            for(a in matrices) for(b in matrices) {
                val matrix=relate.invoke(null,a,b)
                if(matrix.javaClass.getMethod("isCrosses",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType).invoke(matrix,1,1)==true) crossing++
            }
            assertTrue(crossing>0);assertEquals(0,forbiddenLoads)
            println("CLASSIC_RELATION_ISOLATION pairs=${matrices.size*matrices.size} forbidden_class_loads=$forbiddenLoads")
        }
    }
}
