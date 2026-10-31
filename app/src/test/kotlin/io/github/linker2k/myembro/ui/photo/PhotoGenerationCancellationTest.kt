// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui.photo

import kotlinx.coroutines.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
class PhotoGenerationCancellationTest {
    @Test fun crossingOneMinuteDoesNotCancelButExplicitCancellationInterruptsWork() = runBlocking {
        val clock = AtomicLong(1_000)
        val vm = PhotoViewModel(RuntimeEnvironment.getApplication())
        vm.generationClock = clock::get
        val started = CompletableDeferred<Unit>()
        val interrupted = AtomicBoolean()
        val worker = launch {
            vm.runGeneration {
                runInterruptible(Dispatchers.Default) {
                    started.complete(Unit)
                    try { Thread.sleep(Long.MAX_VALUE) }
                    catch (cancelled: InterruptedException) { interrupted.set(true); throw cancelled }
                }
            }
        }
        started.await()
        clock.set(61_001)
        withTimeout(5_000) { while (vm.generationElapsedMs < 60_001) delay(10) }
        assertTrue(worker.isActive)
        worker.cancelAndJoin()
        assertTrue(interrupted.get())
        assertTrue(worker.isCancelled)
    }
}
