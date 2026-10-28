// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.machines

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.model.Bounds
import io.github.linker2k.myembro.ui.Hoop
import io.github.linker2k.myembro.ui.settings.EmbroiderySettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MachineProfileTest {
    @Test fun `first launch defaults to the user's SE400 and keeps a 100mm default with optional frames`() {
        val settings = EmbroiderySettings()
        assertEquals(MachineProfiles.SE400, settings.machineProfile)
        assertEquals(Hoop.H100, settings.defaultHoop)
        assertEquals(Hoop.entries, settings.availableViewerHoops)
        assertTrue(settings.availableEditorHoops.any { it.width == 600 && it.height == 200 })
    }

    @Test fun `unsupported frames remain selectable without claiming compatibility`() {
        for (profile in listOf(MachineProfiles.SE400, MachineProfiles.SE600, MachineProfiles.SE700)) {
            val settings = EmbroiderySettings(profile.displayName, Hoop.H160).validated()
            assertEquals(Hoop.H160, settings.defaultHoop)
            assertTrue(Hoop.H130 in settings.availableViewerHoops)
            assertFalse(profile.supports(Hoop.H130))
            assertTrue(Hoop.H200 in settings.availableViewerHoops)
        }
        val custom = EmbroiderySettings("My custom machine", Hoop.H130).validated()
        assertNull(custom.machineProfile)
        assertEquals(Hoop.H130, custom.defaultHoop)
        assertTrue(custom.availableEditorHoops.isNotEmpty())
    }

    @Test fun `100mm boundary uses tenths of a millimeter and survives extreme coordinates`() {
        for (profile in listOf(MachineProfiles.SE400, MachineProfiles.SE600, MachineProfiles.SE700)) {
            assertTrue(profile.fits(Bounds(-499, -499, 500, 500)))
            assertTrue(profile.fits(Bounds(100, 200, 1099, 1199)))
            assertFalse(profile.fits(Bounds(-500, -500, 501, 500)))
            assertFalse(profile.fits(Bounds(0, 0, 1000, 1001)))
            assertFalse(profile.fits(Bounds(Int.MIN_VALUE, 0, Int.MAX_VALUE, 1)))
        }
    }

    @Test fun `repositionable hoops never expand the continuous sewing area`() {
        for (profile in listOf(MachineProfiles.SE400, MachineProfiles.SE600, MachineProfiles.SE700)) {
            val large = profile.hoops.single { it.requiresRepositioning }
            assertEquals(1700, large.coverage.height)
            assertEquals(HoopSpec.H100, large.sewingArea)
            assertTrue(profile.supports(large.coverage))
            assertNull(large.positionCount)
            assertTrue(large.coverage.positions.isEmpty())
            assertTrue(profile.supports(Hoop.SA431))
            assertFalse(profile.fits(Bounds(0, 0, 1000, 1700)))
        }
    }

    @Test fun `USB temporary disk limits are distinct from internal memory and unknown caps stay unknown`() {
        val temporary = MachineProfiles.SE400.storageLimits.single()
        assertEquals(StorageScope.TEMPORARY_USB_DISK, temporary.scope)
        assertEquals(12, temporary.maxPatterns)
        assertNull(temporary.maxStitchesPerPattern)
        assertTrue(MachineProfiles.SE600.storageLimits.isEmpty())
        val internal = MachineProfiles.SE700.storageLimits.single()
        assertEquals(StorageScope.INTERNAL_MEMORY, internal.scope)
        assertEquals(20, internal.maxPatterns)
        assertEquals(100_000, internal.maxStitchesPerPattern)
        for (profile in listOf(MachineProfiles.SE400, MachineProfiles.SE600, MachineProfiles.SE700)) {
            assertNull(profile.maxFilenameCharacters)
            assertNull(profile.androidOtgCableVerified)
        }
    }

    @Test fun `only exact model aliases resolve to a verified profile`() {
        assertEquals(MachineProfiles.SE400, MachineProfiles.find(" SE-400 "))
        assertEquals(MachineProfiles.SE600, MachineProfiles.find("BROTHER se600"))
        assertNull(MachineProfiles.find("Brother SE700 clone"))
        assertEquals("pe800", MachineProfiles.find("Brother PE800")?.id)
    }
}
