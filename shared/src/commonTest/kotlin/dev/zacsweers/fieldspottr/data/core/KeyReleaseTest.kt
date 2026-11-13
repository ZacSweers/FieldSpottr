// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isSameInstanceAs
import dev.zacsweers.fieldspottr.data.core.impl.RealActionRunner
import dev.zacsweers.fieldspottr.data.core.testing.assertCancelled
import dev.zacsweers.fieldspottr.data.core.testing.assertPending
import dev.zacsweers.fieldspottr.data.core.testing.assertRunning
import dev.zacsweers.fieldspottr.data.core.testing.assertSucceeded
import kotlin.test.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * When the runner lets go of a key. A key stays held while any command on it has not settled, also
 * after the newest command on it was cancelled and settled first.
 */
class KeyReleaseTest {

  @Test
  fun `a command waits for a running one after a cancelled command behind it settled`() = runTest {
    for (ordering in ORDERINGS) {
      val failures = mutableListOf<Throwable>()
      val actions = RealActionRunner(backgroundScope) { failures += it }
      val ran = mutableListOf<String>()
      val release = CompletableDeferred<Unit>()

      // Keeps running after a Cancel command cancels it, like a write that cannot be cancelled
      val a =
        actions.run(ordering) {
          ran += "A"
          withContext(NonCancellable) { release.await() }
        }
      testScheduler.runCurrent()
      val b = actions.runCancellable(ordering) { ran += "B" }
      testScheduler.runCurrent()
      b.cancel()
      testScheduler.runCurrent()
      b.assertCancelled()
      a.assertRunning()

      val c = actions.run(ordering) { ran += "C" }
      testScheduler.runCurrent()
      assertThat(ran, "$ordering").containsExactly("A")
      c.assertPending()

      release.complete(Unit)
      testScheduler.runCurrent()
      assertThat(ran, "$ordering").containsExactly("A", "C")
      c.assertSucceeded()
      assertThat(actions.keysHeld, "$ordering").isEqualTo(0)
      assertThat(failures).isEmpty()
    }
  }

  @Test
  fun `queued commands cancelled in any order keep the key until the running one ends`() = runTest {
    for (order in permutations(listOf(0, 1, 2))) {
      val actions = RealActionRunner(backgroundScope, onFailure = {})
      val ran = mutableListOf<String>()
      val release = CompletableDeferred<Unit>()

      actions.run(KEY) {
        ran += "A"
        release.await()
      }
      testScheduler.runCurrent()
      val queued = List(3) { i -> actions.runCancellable(KEY) { ran += "B$i" } }
      for (i in order) {
        queued[i].cancel()
        testScheduler.runCurrent()
        assertThat(actions.keysHeld, "$order").isEqualTo(1)
      }

      val c = actions.run(KEY) { ran += "C" }
      testScheduler.runCurrent()
      c.assertPending()
      release.complete(Unit)
      testScheduler.runCurrent()

      assertThat(ran, "$order").containsExactly("A", "C")
      queued.forEach { it.assertCancelled() }
      c.assertSucceeded()
      assertThat(actions.keysHeld, "$order").isEqualTo(0)
    }
  }

  @Test
  fun `a command queued between cancelled ones still runs in order`() = runTest {
    val actions = RealActionRunner(backgroundScope, onFailure = {})
    val ran = mutableListOf<String>()
    val release = CompletableDeferred<Unit>()

    actions.run(KEY) {
      ran += "A"
      release.await()
    }
    testScheduler.runCurrent()
    val oldest = actions.runCancellable(KEY) { ran += "B0" }
    val kept = actions.run(KEY) { ran += "B1" }
    val newest = actions.runCancellable(KEY) { ran += "B2" }
    newest.cancel()
    testScheduler.runCurrent()
    oldest.cancel()
    testScheduler.runCurrent()

    val c = actions.run(KEY) { ran += "C" }
    testScheduler.runCurrent()
    kept.assertPending()
    c.assertPending()
    release.complete(Unit)
    testScheduler.runCurrent()

    assertThat(ran).containsExactly("A", "B1", "C")
    kept.assertSucceeded()
    c.assertSucceeded()
    assertThat(actions.keysHeld).isEqualTo(0)
  }

  @Test
  fun `a request joins the running command once a cancelled one behind it settled`() = runTest {
    val actions = RealActionRunner(backgroundScope, onFailure = {})
    val ran = mutableListOf<String>()
    val release = CompletableDeferred<Unit>()

    val fetch =
      actions.runOrJoin(KEY, Fetch, joins = { false }) {
        ran += "fetch"
        release.await()
      }
    testScheduler.runCurrent()
    val cancelled = actions.runCancellable(KEY) { ran += "cancelled" }
    cancelled.cancel()
    testScheduler.runCurrent()

    val request = actions.runOrJoin(KEY, Fetch, joins = { it.tag == Fetch }) { ran += "request" }
    assertThat(request).isSameInstanceAs(fetch)
    release.complete(Unit)
    testScheduler.runCurrent()

    assertThat(ran).containsExactly("fetch")
    fetch.assertSucceeded()
    assertThat(actions.keysHeld).isEqualTo(0)
  }

  private data object Fetch

  private companion object {
    val KEY = Ordering.Keyed("k")
    val ORDERINGS =
      listOf(
        Ordering.Serial,
        Ordering.Keyed("k", OnPrevious.Wait),
        Ordering.Keyed("k", OnPrevious.Supersede),
        Ordering.Keyed("k", OnPrevious.Cancel),
      )

    fun <T> permutations(items: List<T>): List<List<T>> =
      if (items.size <= 1) {
        listOf(items)
      } else {
        items.flatMap { first -> permutations(items - first).map { listOf(first) + it } }
      }
  }
}
