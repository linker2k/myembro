// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import kotlin.test.*

/** Synthetic policy inputs test selection only; they are not approved physical font sizes. */
class AutomaticLetteringPolicyTest {
    private fun profile(style:StitchStyle=StitchStyle.RUNNING,geometry:LetteringGeometry=LetteringGeometry.OUTLINE,
        widths:ValidatedHeightRange?=null)=ValidatedLetteringProfile("fixture","sha:extractor",geometry,style,
            ValidatedHeightRange(8.0,10.0),LetteringEvidence.PE_DESIGN_CORPUS,"synthetic unit input, not production evidence",setOf(65,66),widths)
    @Test fun unknownOrChangedFontNeverInheritsSatinApproval() {
        val policy=AutomaticLetteringPolicy(listOf(profile()))
        assertTrue(policy.candidates("unknown","sha:extractor",LetteringGeometry.OUTLINE,"AB").isEmpty())
        assertTrue(policy.candidates("fixture","other",LetteringGeometry.OUTLINE,"AB").isEmpty())
        assertTrue(policy.candidates("fixture","sha:extractor",LetteringGeometry.OUTLINE,"AC").isEmpty())
        assertTrue(policy.candidates("fixture","sha:extractor",LetteringGeometry.OUTLINE,"").isEmpty())
    }
    @Test fun onlyApprovedHeightsSelectAMethodAndClampingDoesNotInventAnInterval() {
        val p=profile();val policy=AutomaticLetteringPolicy(listOf(p))
        val candidates=policy.candidates("fixture","sha:extractor",LetteringGeometry.OUTLINE,"A B")
        assertEquals(p,policy.select(candidates,8.0));assertEquals(p,policy.select(candidates,10.0))
        assertNull(policy.select(candidates,7.9));assertNull(policy.select(candidates,10.1))
        assertEquals(8.0,p.heights.clamp(2.0));assertEquals(10.0,p.heights.clamp(50.0))
    }
    @Test fun satinRequiresExplicitColumnsAndAnApprovedMeasuredWidth() {
        val p=profile(StitchStyle.SATIN,LetteringGeometry.SATIN_COLUMNS,ValidatedHeightRange(1.0,3.0))
        val policy=AutomaticLetteringPolicy(listOf(p))
        assertTrue(policy.candidates("fixture","sha:extractor",LetteringGeometry.OUTLINE,"A",ValidatedHeightRange(1.0,2.0)).isEmpty())
        assertTrue(policy.candidates("fixture","sha:extractor",LetteringGeometry.SATIN_COLUMNS,"A").isEmpty())
        assertTrue(policy.candidates("fixture","sha:extractor",LetteringGeometry.SATIN_COLUMNS,"A",ValidatedHeightRange(.9,2.0)).isEmpty())
        assertEquals(listOf(p),policy.candidates("fixture","sha:extractor",LetteringGeometry.SATIN_COLUMNS,"A",ValidatedHeightRange(1.0,3.0)))
    }
    @Test fun deferredMethodsAndInvalidEvidenceCannotBecomeAutomaticDefaults() {
        for(style in listOf(StitchStyle.FILL,StitchStyle.BEAN)) assertFailsWith<IllegalArgumentException> { profile(style) }
        assertFailsWith<IllegalArgumentException> { profile(StitchStyle.SATIN) }
        assertFailsWith<IllegalArgumentException> { profile().copy(evidence="") }
        assertFailsWith<IllegalArgumentException> { ValidatedHeightRange(0.0,10.0) }
        assertFailsWith<IllegalArgumentException> { ValidatedHeightRange(10.0,8.0) }
    }
    @Test fun emptyRulesRemainUnconfirmedInsteadOfUsingQualityHeuristics() {
        val policy=AutomaticLetteringPolicy.decode("""{"schemaVersion":1,"profiles":[]}""")
        assertTrue(policy.candidates("jua","revision",LetteringGeometry.OUTLINE,"자수").isEmpty())
        assertEquals(StitchStyle.RUNNING,policy.method(emptyList(),18.0))
    }
}
