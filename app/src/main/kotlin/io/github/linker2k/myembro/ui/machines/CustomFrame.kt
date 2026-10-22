// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.machines

import io.github.linker2k.myembro.engine.edit.HoopSpec
import io.github.linker2k.myembro.ui.Hoop
import io.github.linker2k.myembro.ui.format.Dimensions
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import kotlinx.serialization.json.*

enum class DimensionUnit { MM, IN }

/** User measurements are stored as integer 0.1 mm, exactly like editor/project coordinates. */
data class CustomFrame(val id: String, val name: String, val width: Int, val height: Int) {
    init {
        require(id.isNotBlank() && name.isNotBlank() && name.length <= 80)
        require(width in 1..100_000 && height in 1..100_000)
    }
    val spec: HoopSpec get() = HoopSpec(name, width, height)

    companion object {
        fun fromInput(name: String, width: String, height: String, unit: DimensionUnit, id: String = UUID.randomUUID().toString()): CustomFrame {
            fun parse(input: String): Int {
                val value = input.trim().replace(',', '.').toBigDecimal()
                require(value.signum() > 0)
                val factor = if (unit == DimensionUnit.IN) BigDecimal("254") else BigDecimal.TEN
                return value.multiply(factor).setScale(0, RoundingMode.HALF_UP).intValueExact().also { require(it in 1..100_000) }
            }
            return CustomFrame(id, name.trim(), parse(width), parse(height))
        }
    }
}

data class FrameOption(val id: String, val spec: HoopSpec, val preset: Hoop? = null) {
    val dimensions: String get() = if (preset != null) Dimensions.hoopSize(preset.widthMm, preset.heightMm)
        else Dimensions.size(spec.width / 10.0, spec.height / 10.0)
    fun fits(width: Int, height: Int): Boolean = width <= spec.width && height <= spec.height
}

object FrameCatalog {
    val presets: List<FrameOption> get() = Hoop.entries.map { FrameOption(it.name, HoopSpec(it.label, it.widthMm * 10, it.heightMm * 10), it) }
    fun options(custom: List<CustomFrame>): List<FrameOption> = presets + custom.map { FrameOption(it.id, it.spec) }
}

/** JSON is version tolerant: one corrupt entry must not discard other saved frames. */
object CustomFrameCodec {
    fun encode(frames: List<CustomFrame>): String = JsonArray(frames.map { frame -> buildJsonObject {
        put("id", frame.id); put("name", frame.name); put("width", frame.width); put("height", frame.height)
    } }).toString()
    fun decode(value: String?): List<CustomFrame> {
        val array = runCatching { Json.parseToJsonElement(value ?: "[]").jsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { item -> runCatching {
            val o = item.jsonObject
            CustomFrame(o.getValue("id").jsonPrimitive.content, o.getValue("name").jsonPrimitive.content,
                o.getValue("width").jsonPrimitive.int, o.getValue("height").jsonPrimitive.int)
        }.getOrNull() }.distinctBy { it.id }.take(100)
    }
}
