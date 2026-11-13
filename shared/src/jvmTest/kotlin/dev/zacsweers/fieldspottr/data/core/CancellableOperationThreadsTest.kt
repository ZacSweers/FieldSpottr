// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import dev.zacsweers.fieldspottr.data.core.impl.RealActionRunner
import dev.zacsweers.fieldspottr.data.core.impl.invokeOnSettled
import dev.zacsweers.fieldspottr.data.core.testing.assertPending
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
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

  @Test
  fun `queued commands cancelled from many threads leave the key with the running one`() =
    runBlocking {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      val failures = Collections.synchronizedList(mutableListOf<Throwable>())
      val actions = RealActionRunner(scope) { failures += it }
      try {
        repeat(ROUNDS) { round ->
          // Wait queues each command, and Supersede replaces all but the newest on submit
          val mode = listOf(OnPrevious.Wait, OnPrevious.Supersede)[round % 2]
          val ordering = Ordering.Keyed("k", mode)
          val started = CountDownLatch(1)
          val release = CountDownLatch(1)
          val running =
            actions.run(ordering) {
              started.countDown()
              runInterruptible { release.await() }
            }
          check(started.await(10, SECONDS)) { "The running command never started" }
          val queued = List(COMMANDS) { actions.runCancellable(ordering) {} }
          // Registered after the runner's own handler, so each count follows that key update
          val settled = CountDownLatch(COMMANDS)
          queued.forEach { it.invokeOnSettled { settled.countDown() } }
          val go = CountDownLatch(1)
          // Each thread cancels every command in its own order, so the newest command and the ones
          // ahead of it settle at the same time on several threads
          val cancellers =
            List(4) { seed ->
              thread {
                val order = queued.shuffled(Random(round * 4 + seed))
                go.await()
                order.forEach { it.cancel() }
              }
            }
          go.countDown()
          cancellers.forEach { it.join(10_000) }
          check(settled.await(10, SECONDS)) { "A cancelled command never settled" }

          // The running command still holds the key, so a new one waits for it
          assertThat(actions.keysHeld, "round $round").isEqualTo(1)
          // Each settled command is gone from the key, also one chosen just as it settled
          assertThat(queued.none { actions.reaches(it) }, "round $round").isTrue()
          val ran = AtomicBoolean(false)
          val next = actions.run(ordering) { ran.set(true) }
          Thread.sleep(2)
          assertThat(ran.get(), "round $round").isFalse()
          next.assertPending()

          release.countDown()
          withTimeout(10_000) {
            assertThat(running.await()).isEqualTo(OperationState.Succeeded)
            assertThat(next.await()).isEqualTo(OperationState.Succeeded)
          }
          check(actions.keysHeld == 0 || waitForKeys(actions)) {
            "The key was kept in round $round"
          }
        }
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
