// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.engine.sewout

import io.github.linker2k.myembro.engine.model.*

/** Counts observable needle-to-needle jump connections, not individual split JUMP commands.
 * Initial positioning, zero-distance moves and trailing travel without another needle do not count.
 * A TRIM request is not evidence of a physical cut on a machine without automatic jump trimming.
 */
data class JumpThreadCleanup(val connections: Int, val manualCutCount: Int?) {
    companion object {
        fun assess(pattern: EmbPattern, machine: SewoutMachine, setting: SwitchSetting = SwitchSetting.UNKNOWN) = assess(pattern.stitches,machine,setting)
        fun assessPes(bytes: ByteArray, machine: SewoutMachine, setting: SwitchSetting = SwitchSetting.UNKNOWN) = assess(boundedPecStitches(bytes),machine,setting)
        internal fun assess(stitches: List<Stitch>, machine: SewoutMachine, setting: SwitchSetting = SwitchSetting.UNKNOWN): JumpThreadCleanup {
            var needle=false;var moved=false;var x=0;var y=0;var count=0
            for(s in stitches)when(s.command) {
                Command.END -> break
                Command.STITCH -> {if(needle && moved)count++;needle=true;moved=false;x=s.x;y=s.y}
                Command.JUMP -> {if(needle && (s.x!=x || s.y!=y))moved=true;x=s.x;y=s.y}
                Command.COLOR_CHANGE -> {needle=false;moved=false;x=s.x;y=s.y}
                Command.STOP, Command.TRIM -> Unit
            }
            val required=when {
                machine.automaticJumpTrim.value==true && setting==SwitchSetting.ENABLED -> 0
                machine.automaticJumpTrim.value==false || machine.manualJumpCleanup.value==true || setting==SwitchSetting.DISABLED -> count
                else -> null
            }
            return JumpThreadCleanup(count,required)
        }
    }
}
