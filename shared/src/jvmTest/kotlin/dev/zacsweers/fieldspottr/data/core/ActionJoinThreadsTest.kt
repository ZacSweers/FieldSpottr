// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import dev.zacsweers.fieldspottr.data.core.impl.RealActionRunner
import dev.zacsweers.fieldspottr.data.core.testing.TestThread
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/** Join keys while several threads submit at once and commands settle on real threads. */
class ActionJoinThreadsTest {
  @Test
  fun `a read joined from many threads never returns what was read before a write it follows`() =
    runBlocking {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      val failures = Collections.synchronizedList(mutableListOf<Throwable>())
      val actions = RealActionRunner(scope) { failures += it }
      val note = Ordering.Keyed("note")
      // How many writes have run, which each read records when its block runs
      val version = AtomicInteger()
      // The version each read call's block saw, for the calls whose block ran
      val seen = ConcurrentHashMap<Int, Int>()
      val calls = AtomicInteger()
      val steps = ConcurrentLinkedQueue<Step>()
      try {
        val barrier = CyclicBarrier(THREADS)
        val submitters =
          List(THREADS) {
            TestThread {
              barrier.await(10, SECONDS)
              repeat(STEPS) {
                val written = CompletableDeferred<Int>()
                val write = actions.run(note) { written.complete(version.incrementAndGet()) }
                // Several reads in a row, so some join each other and some join one that settles
                repeat(READS) {
                  val call = calls.incrementAndGet()
                  val read =
                    actions.run(note, admission = Admission.Join("fetch")) {
                      seen[call] = version.get()
                      yield()
                    }
                  steps += Step(write, written, read, call)
                }
              }
            }
          }
        submitters.forEach { it.joinOrFail(30_000) }
        withTimeout(30_000) { steps.forEach { it.read.await() } }

        // Each handle comes from exactly one call whose block ran, and the others joined it
        val callsByHandle = IdentityHashMap<Operation, MutableList<Int>>()
        for (step in steps) {
          callsByHandle.getOrPut(step.read) { mutableListOf() } += step.call
        }
        assertThat(seen.size).isEqualTo(callsByHandle.size)
        for ((handle, handleCalls) in callsByHandle) {
          assertThat(handle.state.value).isEqualTo(OperationState.Succeeded)
          assertThat(handleCalls.count { seen.containsKey(it) }).isEqualTo(1)
        }
        // A read never shares a run that started before the write its caller made first
        for (step in steps) {
          val readVersion =
            seen.getValue(callsByHandle.getValue(step.read).single { seen.containsKey(it) })
          assertThat(readVersion, "call ${step.call}").isGreaterThanOrEqualTo(step.written.await())
          assertThat(step.write.state.value).isEqualTo(OperationState.Succeeded)
        }
        check(waitForKeys(actions)) { "The key was never released" }
        assertThat(failures).isEmpty()
      } finally {
        scope.cancel()
      }
    }

  /** One read call, with the write its thread made just before it. */
  private class Step(
    val write: Operation,
    val written: CompletableDeferred<Int>,
    val read: Operation,
    val call: Int,
  )

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
    const val THREADS = 8
    const val STEPS = 500
    const val READS = 3
  }
}
