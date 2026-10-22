// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.editor

import io.github.linker2k.myembro.engine.edit.DesignObject
import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.engine.edit.Transform
import io.github.linker2k.myembro.engine.model.Bounds
import kotlin.math.abs

internal fun Bounds.outside(hoop: HoopSpec): Boolean =
    minX < -hoop.width / 2.0 || maxX > hoop.width / 2.0 ||
        minY < -hoop.height / 2.0 || maxY > hoop.height / 2.0

/** Hit test in local coordinates, including mirrored and rotated designs. */
internal fun DesignObject.contains(x: Double, y: Double, tolerance: Double): Boolean {
    val b = localPattern().bounds ?: return false
    val t = transform
    val r = Math.toRadians(-t.rotationDeg)
    val dx = x - t.x
    val dy = y - t.y
    val localX = (dx * kotlin.math.cos(r) - dy * kotlin.math.sin(r)) / t.scaleX
    val localY = (dx * kotlin.math.sin(r) + dy * kotlin.math.cos(r)) / t.scaleY
    val tx = tolerance / abs(t.scaleX)
    val ty = tolerance / abs(t.scaleY)
    return localX >= b.minX - tx && localX <= b.maxX + tx &&
        localY >= b.minY - ty && localY <= b.maxY + ty
}

/** Preserve mirror signs, reject non-finite input and keep stitch coordinates bounded. */
internal fun Transform.scaled(factor: Double): Transform {
    if (!factor.isFinite() || factor <= 0) return this
    val minScale = minOf(abs(scaleX), abs(scaleY)).coerceAtLeast(0.0001)
    val maxScale = maxOf(abs(scaleX), abs(scaleY)).coerceAtLeast(minScale)
    val safe = factor.coerceIn(0.05 / minScale, maxOf(0.05 / minScale, 20.0 / maxScale))
    return copy(scaleX = scaleX * safe, scaleY = scaleY * safe)
}
