// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import dev.zacsweers.fieldspottr.data.core.impl.RealActionRunner
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/**
 * [CancellableOperation.cancel] called from several threads at once while commands start, run and
 * settle on real threads.
 */
class CancellableOperationThreadsTest {
  @Test
  fun `cancels from many threads settle every command without a failure`() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val failures = Collections.synchronizedList(mutableListOf<Throwable>())
    val actions = RealActionRunner(scope) { failures += it }
    try {
      repeat(ROUNDS) {
        val handles = List(COMMANDS) { actions.runCancellable { repeat(10) { yield() } } }
        val go = CountDownLatch(1)
        // Two threads cancel every other command, so some calls race each other and the start
        val cancellers =
          List(2) {
            thread {
              go.await()
              handles.filterIndexed { index, _ -> index % 2 == 0 }.forEach { it.cancel() }
            }
          }
        go.countDown()
        cancellers.forEach { it.join(10_000) }

        withTimeout(10_000) {
          for ((index, handle) in handles.withIndex()) {
            val state = handle.await()
            if (index % 2 == 1) {
              assertThat(state).isEqualTo(OperationState.Succeeded)
            }
          }
        }
      }
      check(actions.keysHeld == 0 || waitForKeys(actions)) { "The serial key was never released" }
      assertThat(failures).isEmpty()
    } finally {
      scope.cancel()
    }
  }

  /** Waits up to a few seconds for [actions] to release its key, which follows the settlement. */
  private fun waitForKeys(actions: RealActionRunner): Boolean {
    val done = CountDownLatch(1)
    thread(isDaemon = true) {
      while (actions.keysHeld != 0) {
        Thread.sleep(1)
      }
      done.countDown()
    }
    return done.await(5, SECONDS)
  }

  private companion object {
    const val ROUNDS = 50
    const val COMMANDS = 40
  }
}
