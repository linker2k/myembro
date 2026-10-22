// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.sources

import java.io.File
import kotlinx.serialization.json.*
import kotlin.test.*

class FreeDesignSourcesTest {
    private fun asset(): String = listOf(File("src/main/assets/${FreeDesignSources.ASSET}"),
        File("app/src/main/assets/${FreeDesignSources.ASSET}")).first { it.isFile }.readText()

    @Test fun directoryHasVerifiedBilingualTermsFormatsAndUniqueIds() {
        val sources = FreeDesignSources.parse(asset())
        assertEquals(5, sources.size)
        assertTrue(sources.all { it.includesPes && "PES" in it.formats && it.termsUrls.isNotEmpty() })
        assertEquals("not_allowed", sources.single { it.id == "designs_by_juju" }.commercialUse)
        assertEquals("not_required", sources.single { it.id == "brother_sewingcraft" }.registration)
        assertTrue(sources.all { it.verifiedOn == "2026-10-22" })
        assertEquals(sources.first().description.ko, sources.first().description.localized("ko"))
        assertEquals(sources.first().description.en, sources.first().description.localized("de"))
    }

    @Test fun unsupportedVersionsUnsafeUrlsAndDuplicateIdsAreRejected() {
        val root = Json.parseToJsonElement(asset()).jsonObject
        assertFailsWith<IllegalArgumentException> { FreeDesignSources.parse(JsonObject(root + ("version" to JsonPrimitive(2))).toString()) }
        val one = root.getValue("sources").jsonArray.first().jsonObject
        val unsafe = JsonObject(one + ("url" to JsonPrimitive("javascript:alert(1)")))
        assertFailsWith<IllegalArgumentException> { FreeDesignSources.parse(JsonObject(root + ("sources" to JsonArray(listOf(unsafe)))).toString()) }
        assertFailsWith<IllegalArgumentException> { FreeDesignSources.parse(JsonObject(root + ("sources" to JsonArray(listOf(one, one)))).toString()) }
    }
}
