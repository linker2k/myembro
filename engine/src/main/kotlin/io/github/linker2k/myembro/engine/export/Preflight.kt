// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.export

import io.github.linker2k.myembro.engine.model.Command
import io.github.linker2k.myembro.engine.model.EmbPattern
import java.util.Locale

/** null is unconfirmed, not unlimited. Sources and scope are documented in docs/machines.md. */
data class MachineProfile(
    val name: String,
    val maxWidth: Int,
    val maxHeight: Int,
    val maxStitches: Int? = null,
    val maxColors: Int? = null,
    val maxFileNameLength: Int? = null,
    val maxTransferPatterns: Int? = null,
    val maxInternalPatterns: Int? = null,
    val maxInternalBytes: Long? = null,
) {
    companion object {
        val SE400 = MachineProfile("Brother SE400", 1000, 1000, maxStitches = 30000,
            maxTransferPatterns = 12, maxInternalPatterns = 12, maxInternalBytes = 512L * 1024)
    }
}

enum class ExportDestination { PC_TRANSFER, INTERNAL_MEMORY }

object Preflight {
    /** [patternCount]/[totalBytes] describe the entire destination batch, including this file. */
    fun check(
        pattern: EmbPattern,
        fileName: String,
        profile: MachineProfile = MachineProfile.SE400,
        destination: ExportDestination = ExportDestination.PC_TRANSFER,
        patternCount: Int = 1,
        totalBytes: Long? = null,
    ): List<String> {
        require(patternCount > 0 && (totalBytes == null || totalBytes >= 0))
        val active = pattern.stitches.takeWhile { it.command != Command.END } +
            listOfNotNull(pattern.stitches.firstOrNull { it.command == Command.END })
        val warnings = mutableListOf<String>()
        if (active.isNotEmpty()) {
            // Include needle-up motion because it also contributes to serialized PES/PEC extents.
            // Only the split initial move can extend bounds outside all input coordinates.
            // Subsequent split points lie between two coordinates already in active.
            val first = active.first()
            val parts = maxOf(1L, (maxOf(kotlin.math.abs(first.x.toLong()), kotlin.math.abs(first.y.toLong())) + 2046) / 2047)
            val firstX = Math.rint(first.x.toDouble() / parts).toLong()
            val firstY = Math.rint(first.y.toDouble() / parts).toLong()
            val width = maxOf(active.maxOf { it.x }.toLong(), firstX) - minOf(active.minOf { it.x }.toLong(), firstX)
            val height = maxOf(active.maxOf { it.y }.toLong(), firstY) - minOf(active.minOf { it.y }.toLong(), firstY)
            if (width > profile.maxWidth || height > profile.maxHeight) {
                warnings += "${profile.name}의 단일 자수 영역 ${profile.maxWidth / 10}×${profile.maxHeight / 10}mm를 초과합니다. 도안을 축소하거나 나누어 주세요."
            }
        }
        val stitches = active.count { it.command == Command.STITCH }
        profile.maxStitches?.let { if (stitches > it) warnings += "${profile.name}의 도안당 최대 ${it}땀을 초과했습니다(현재 ${stitches}땀)." }
        profile.maxColors?.let { limit ->
            val colors = 1 + active.count { it.command == Command.COLOR_CHANGE }
            if (colors > limit) warnings += "${profile.name}의 최대 ${limit}색을 초과했습니다(현재 ${colors}색)."
        }
        val leaf = fileName.replace('\\', '/').substringAfterLast('/')
        if (leaf != fileName) warnings += "${profile.name}은 폴더 안의 도안을 읽지 못합니다. 이동식 디스크의 최상위에 파일을 넣어 주세요."
        val base = leaf.substringBeforeLast('.', leaf)
        if (!base.matches(Regex("[A-Za-z0-9_-]+"))) {
            warnings += "파일명에는 영문·숫자·하이픈(-)·밑줄(_)을 사용해 주세요. 특수 문자나 한글이 있으면 ${profile.name}에 표시되지 않을 수 있습니다."
        }
        if (leaf.substringAfterLast('.', "").lowercase(Locale.ROOT) !in setOf("pes", "dst")) {
            warnings += "${profile.name}에 전송할 파일은 PES 또는 DST 형식이어야 합니다. ZIP은 먼저 풀어 주세요."
        }
        profile.maxFileNameLength?.let { if (base.length > it) warnings += "파일명이 ${it}자를 초과합니다." }
        val countLimit = if (destination == ExportDestination.PC_TRANSFER) profile.maxTransferPatterns else profile.maxInternalPatterns
        countLimit?.let { if (patternCount > it) warnings += "${profile.name}에 한 번에 넣을 도안이 ${it}개를 초과합니다(현재 ${patternCount}개). 전송할 도안을 나누어 주세요." }
        if (destination == ExportDestination.INTERNAL_MEMORY && totalBytes != null) {
            profile.maxInternalBytes?.let { if (totalBytes > it) warnings += "${profile.name} 내부 메모리의 전체 용량 ${it / 1024}KB를 초과합니다. 저장된 도안을 정리해 주세요." }
        }
        return warnings
    }
}
