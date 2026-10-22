// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

object MyembroShapes {
    val Card = RoundedCornerShape(20.dp)
    val Button = RoundedCornerShape(16.dp)
    val Chip = RoundedCornerShape(12.dp)
}

val MyembroShapeScheme = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = MyembroShapes.Chip,
    medium = MyembroShapes.Button, large = MyembroShapes.Card, extraLarge = RoundedCornerShape(28.dp),
)
