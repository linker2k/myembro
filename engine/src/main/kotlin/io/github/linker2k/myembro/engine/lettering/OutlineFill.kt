// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.lettering

import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.model.EmbThread
import io.github.linker2k.myembro.engine.digitizing.OutlineTextFill
import kotlin.math.max

enum class FillBorder { NONE, RUNNING, BEAN }
enum class FillStrategy { AUTO, TATAMI }

/** Public dimensions are mm; a positive pull compensation expands exteriors, preserving counters. */
data class FillOptions(
    val densityMm: Double = 0.4,
    val angleDeg: Double = 0.0,
    val pullCompensationMm: Double = 0.2,
    val underlay: Boolean = true,
    val border: FillBorder = FillBorder.RUNNING,
    val strategy: FillStrategy = FillStrategy.AUTO,
    val autoDensity: Boolean = true,
) {
    init {
        require(densityMm.isFinite() && densityMm in 0.3..2.0)
        require(angleDeg.isFinite() && angleDeg in -180.0..180.0)
        require(pullCompensationMm.isFinite() && pullCompensationMm in 0.0..2.0)
    }
    fun densityForHeight(heightMm: Double, threadWeight: ThreadWeight = ThreadWeight.WT40): Double = when {
        !autoDensity -> densityMm
        threadWeight != ThreadWeight.WT40 -> threadWeight.spacingForHeight(heightMm)
        heightMm < 7.0 -> max(0.6, densityMm)
        else -> densityMm
    }
}

internal data class OutlineGlyph(val contours: List<List<Pair<Double, Double>>>, val thread: EmbThread, val heightMm: Double? = null)

/** Source-compatible legacy facade only. S4/model owns the fill implementation in digitizing. */
object OutlineFill {
    fun generate(contours:List<List<Pair<Double,Double>>>,options:FillOptions=FillOptions(),
        thread:EmbThread=EmbThread(0xFF000000.toInt(),"Black"),satin:Boolean=false,heightMm:Double?=null):EmbPattern =
        OutlineTextFill.generate(contours,options,thread,satin,heightMm)

    internal fun generateGlyphs(glyphs:List<OutlineGlyph>,options:FillOptions,satin:Boolean):EmbPattern =
        OutlineTextFill.generateGlyphs(glyphs,options,satin)
}
