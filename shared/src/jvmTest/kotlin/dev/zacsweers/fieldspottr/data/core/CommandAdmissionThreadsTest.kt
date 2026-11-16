// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import dev.zacsweers.fieldspottr.data.core.impl.RealActionRunner
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout

/** Conflict keys while several threads submit at once and commands settle on real threads. */
class CommandAdmissionThreadsTest {
  @Test
  fun `of the threads that submit one conflict key at once exactly one is accepted`() =
    runBlocking {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      val failures = Collections.synchronizedList(mutableListOf<Throwable>())
      val actions = RealActionRunner(scope) { failures += it }
      try {
        repeat(ROUNDS) { round ->
          val release = CountDownLatch(1)
          val barrier = CyclicBarrier(THREADS)
          val handles = ConcurrentLinkedQueue<Operation>()
          val ran = ConcurrentLinkedQueue<Int>()
          val submitters =
            List(THREADS) { index ->
              thread {
                barrier.await(10, SECONDS)
                handles +=
                  actions.run(Ordering.Keyed(index), conflictKey = "sync") {
                    ran += index
                    runInterruptible { release.await() }
                  }
              }
            }
          submitters.forEach { it.join(10_000) }

          val rejected = handles.count {
            (it.state.value as? OperationState.Failed)?.cause is CommandRejectedException
          }
          assertThat(rejected, "round $round").isEqualTo(THREADS - 1)
          release.countDown()
          withTimeout(10_000) { handles.forEach { it.await() } }
          // Only the accepted command ran
          assertThat(ran.size, "round $round").isEqualTo(1)
        }
        check(waitForKeys(actions)) { "A conflict key was never released" }
        assertThat(failures).isEmpty()
      } finally {
        scope.cancel()
      }
    }

  @Test
  fun `accepted commands from many threads run in each thread's order and rejected ones never`() =
    runBlocking {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      val failures = Collections.synchronizedList(mutableListOf<Throwable>())
      val actions = RealActionRunner(scope) { failures += it }
      try {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        // Holds the serial line, so every command below is still pending when its copy arrives
        actions.run {
          started.countDown()
          runInterruptible { release.await() }
        }
        check(started.await(10, SECONDS)) { "The holding command never started" }
        val barrier = CyclicBarrier(THREADS)
        val ran = ConcurrentLinkedQueue<Pair<Int, Int>>()
        val copies = ConcurrentLinkedQueue<Operation>()
        val accepted = ConcurrentLinkedQueue<Operation>()
        val submitters =
          List(THREADS) { owner ->
            thread {
              barrier.await(10, SECONDS)
              for (step in 0 until COMMANDS) {
                val key = owner to step
                accepted += actions.run(conflictKey = key) { ran += key }
                copies += actions.run(conflictKey = key) { ran += -1 to -1 }
              }
            }
          }
        submitters.forEach { it.join(10_000) }

        for (copy in copies) {
          assertThat(
              (copy.state.value as? OperationState.Failed)?.cause is CommandRejectedException
            )
            .isEqualTo(true)
        }
        release.countDown()
        withTimeout(10_000) { accepted.forEach { it.await() } }
        // Every accepted command ran once, and each thread's commands kept the order it submitted
        assertThat(ran.size).isEqualTo(THREADS * COMMANDS)
        for (owner in 0 until THREADS) {
          val steps = ran.filter { it.first == owner }.map { it.second }
          assertThat(steps, "thread $owner").isEqualTo(List(COMMANDS) { it })
        }
        check(waitForKeys(actions)) { "A conflict key was never released" }
        assertThat(failures).isEmpty()
      } finally {
        scope.cancel()
      }
    }

  /** Waits for the last settled command's cleanup, which runs just after its handle settles. */
  private fun waitForKeys(actions: RealActionRunner): Boolean {
    repeat(1_000) {
      if (actions.keysHeld == 0) {
        return true
      }
      Thread.sleep(5)
    }
    return false
  }

  private companion object {
    const val ROUNDS = 200
    const val THREADS = 8
    const val COMMANDS = 200
  }
}
