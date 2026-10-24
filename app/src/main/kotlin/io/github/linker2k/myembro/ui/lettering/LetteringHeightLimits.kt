// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.lettering

import io.github.linker2k.myembro.engine.lettering.*

internal typealias LetteringHeightLimits = io.github.linker2k.myembro.engine.lettering.LetteringHeightLimits
internal typealias AutomaticLetteringState = io.github.linker2k.myembro.engine.lettering.AutomaticLetteringState
internal fun automaticLetteringState(fontName: String, fontId: String, revision: String, text: String,
    heightMm: Double, geometry: LetteringGeometry, limits: LetteringHeightLimits,
    inkRange: ClosedFloatingPointRange<Double>? = null,
    policy: AutomaticLetteringPolicy = AutomaticLetteringPolicy.Shared): AutomaticLetteringState =
    io.github.linker2k.myembro.engine.lettering.automaticLetteringState(fontName,fontId,revision,text,heightMm,geometry,limits,inkRange,policy)
internal fun letteringHeightPermitted(automatic: AutomaticLetteringState?,height:Double) =
    io.github.linker2k.myembro.engine.lettering.letteringHeightPermitted(automatic,height)
internal fun singleLineWeightEnabled(singleLine:Boolean,predigitized:Boolean,fontId:String) =
    io.github.linker2k.myembro.engine.lettering.singleLineWeightEnabled(singleLine,predigitized,fontId)
internal fun letteringStyleForWeight(automatic:AutomaticLetteringState?,bold:Boolean) =
    io.github.linker2k.myembro.engine.lettering.letteringStyleForWeight(automatic,bold)
