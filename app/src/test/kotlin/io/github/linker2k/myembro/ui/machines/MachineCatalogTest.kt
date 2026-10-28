// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.machines

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.model.Bounds
import io.github.linker2k.myembro.engine.sewout.*
import io.github.linker2k.myembro.ui.Hoop
import io.github.linker2k.myembro.ui.settings.EmbroiderySettings
import kotlin.test.*

class MachineCatalogTest {
    @Test fun uniqueIdsSourcesAndEngineFactsAgree() {
        assertEquals(MachineProfiles.all.size, MachineProfiles.all.map { it.id }.toSet().size)
        for (p in MachineProfiles.all) {
            assertTrue(p.sourceUrls.isNotEmpty(), p.id)
            assertTrue(p.sourceUrls.all { it.startsWith("https://") }, p.id)
            if(p.id=="free") continue
            val engine=SewoutMachines.all.single { it.id==p.id }
            assertEquals(p.maximumArea.width/10.0,engine.maxWidthMm.value,p.id)
            assertEquals(p.maximumArea.height/10.0,engine.maxHeightMm.value,p.id)
            assertEquals(p,MachineProfiles.find(p.id),p.id)
            assertEquals(p,MachineProfiles.find(p.displayName),p.id)
        }
        assertTrue(MachineProfiles.all.any { it.region==MachineRegion.US })
        assertTrue(MachineProfiles.all.any { it.region==MachineRegion.KOREA })
    }
    @Test fun every100mmMachineUsesTheExact999Boundary() {
        val small=MachineProfiles.all.filter {it.maximumArea.width==1000 && it.maximumArea.height==1000}
        assertTrue(small.size>3)
        for(p in small) {
            assertTrue(p.fits(Bounds(0,0,998,998)),p.id)
            assertTrue(p.fits(Bounds(0,0,999,999)),p.id)
            assertFalse(p.fits(Bounds(0,0,1000,999)),p.id)
            assertFalse(p.fits(Bounds(0,0,999,1000)),p.id)
        }
    }
    @Test fun pe800WidthHeightAreNotTransposedAndUseOneUnitMargin() {
        val p=assertNotNull(MachineProfiles.find("PE800"))
        assertEquals(1300,p.maximumArea.width);assertEquals(1800,p.maximumArea.height)
        for(w in listOf(1298,1299)) for(h in listOf(1798,1799)) assertTrue(p.fits(Bounds(0,0,w,h)))
        assertFalse(p.fits(Bounds(0,0,1300,1799)))
        assertFalse(p.fits(Bounds(0,0,1299,1800)))
        assertTrue(p.requiresPhysicalVerification)
        assertFalse(MachineProfiles.SE400.requiresPhysicalVerification)
    }
    @Test fun originalStoredNamesDefaultsAndManualLimitsArePreserved() {
        assertEquals(MachineProfiles.SE400,EmbroiderySettings().machineProfile)
        assertEquals(Hoop.H100,EmbroiderySettings().defaultHoop)
        for (p in listOf(MachineProfiles.SE400,MachineProfiles.SE600,MachineProfiles.SE700)) {
            assertEquals(p,EmbroiderySettings(machineModel=p.displayName).machineProfile)
            assertEquals(3,p.hoops.size)
        }
        assertEquals(12,MachineProfiles.SE400.storageLimits.single().maxPatterns)
        assertEquals(20,MachineProfiles.SE700.storageLimits.single().maxPatterns)
        val korean=assertNotNull(MachineProfiles.find("LB6950"))
        assertNotEquals(MachineProfiles.SE600.id,korean.id)
        assertEquals(MachineProfiles.SE600.maximumArea,korean.maximumArea)
        assertTrue(korean.storageLimits.isEmpty())
        assertNull(SewoutMachines.all.single {it.id==korean.id}.maxStitches.value)
        assertNull(MachineProfiles.find("unconfirmed SE400 clone"))
    }
    @Test fun modelSpecificLargeFramesReachSettingsWithoutChangingStoredPresetIds() {
        val p=assertNotNull(MachineProfiles.find("kr-nv4000d"))
        val settings=EmbroiderySettings(machineModel=p.displayName)
        val max=settings.frames.single {it.spec.width==1800 && it.spec.height==3000}
        assertTrue(max.id.startsWith("machine:"))
        assertEquals(max.spec,settings.copy(defaultFrameId=max.id).defaultFrame.spec)
        assertTrue(settings.availableEditorHoops.any {it.width==1800 && it.height==3000})
        assertEquals(HoopSpec.H100,settings.defaultFrame.spec)
        assertEquals(max,FrameCatalog.defaultFor(p))
        assertEquals(Hoop.H100,FrameCatalog.defaultFor(MachineProfiles.SE400)?.preset)
        assertEquals(Hoop.H100,FrameCatalog.defaultFor(MachineProfiles.SE600)?.preset)
        assertEquals(Hoop.H100,FrameCatalog.defaultFor(MachineProfiles.SE700)?.preset)
    }
}
