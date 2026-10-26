// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceGenerationTest {
    @Test fun aVerifyErrorBecomesAnExceptionTheCallersAlreadyRefuseWith() {
        val cause = VerifyError("Verifier rejected class org.locationtech.jts.operation.relateng.RelatePointLocator")
        val e = assertThrows(DeviceGenerationException::class.java) { deviceGeneration { throw cause } }
        assertTrue(e is Exception); assertSame(cause, e.cause)
        assertTrue(e.message!!.startsWith("DEVICE_GENERATION_FAILED: java.lang.VerifyError"))
        assertThrows(DeviceGenerationException::class.java) { deviceGeneration { throw NoClassDefFoundError("x") } }
    }

    @Test fun otherErrorsAndExceptionsAreUntouched() {
        assertThrows(OutOfMemoryError::class.java) { deviceGeneration { throw OutOfMemoryError() } }
        assertThrows(IllegalArgumentException::class.java) { deviceGeneration { throw IllegalArgumentException("hoop") } }
        assertEquals(3, deviceGeneration { 3 })
    }

    @Test fun everyShapeGeneratingExportPathIsWrapped() {
        // Source guard: the four paths that flatten projects/shapes outside the editor keep their protection.
        val root = java.io.File("src/main/kotlin/io/github/linker2k/myembro")
        fun text(p: String) = java.io.File(root, p).readText()
        assertTrue(text("ui/ViewerDesign.kt").contains("deviceGeneration { project.flatten(sewingOptions) }"))
        assertEquals(2, Regex("deviceGeneration \\{").findAll(text("library/DesignFormats.kt")).count())
        assertTrue(text("ui/emoji/EmojiViewModel.kt").contains("catch (_: LinkageError)"))
        assertEquals(2, Regex("catch \\(_: LinkageError\\)").findAll(text("ui/photo/PhotoViewModel.kt")).count())
    }
}
