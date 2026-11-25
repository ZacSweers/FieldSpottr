// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isNotSameInstanceAs
import assertk.assertions.isSameInstanceAs
import dev.zacsweers.fieldspottr.data.core.impl.RealActionRunner
import dev.zacsweers.fieldspottr.data.core.testing.TestFailures
import dev.zacsweers.fieldspottr.data.core.testing.assertCancelled
import dev.zacsweers.fieldspottr.data.core.testing.assertFailed
import dev.zacsweers.fieldspottr.data.core.testing.assertPending
import dev.zacsweers.fieldspottr.data.core.testing.assertRunning
import dev.zacsweers.fieldspottr.data.core.testing.assertSucceeded
import kotlin.test.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * Reject keys on [ActionRunner.run] and [ActionRunner.runCancellable]: a call is rejected while a
 * command accepted under an equal key has not settled, and a rejection changes nothing for the
 * commands already accepted.
 */
class CommandAdmissionTest {

  private val failures = TestFailures()
  private val ran = mutableListOf<String>()

  private fun TestScope.actions(): RealActionRunner = RealActionRunner(backgroundScope, failures)

  @Test
  fun `a second submission is rejected and the accepted commands keep their order`() = runTest {
    val actions = actions()
    val release = CompletableDeferred<Unit>()

    val a =
      actions.run(admission = Admission.Reject("a")) {
        release.await()
        ran += "a"
      }
    val b = actions.run(admission = Admission.Reject("b")) { ran += "b" }
    val again = actions.run(admission = Admission.Reject("a")) { ran += "a again" }

    // Rejected before anything ran, with the key that rejected it
    assertThat(again.assertFailed<CommandRejectedException>().key).isEqualTo("a")
    a.assertPending()
    b.assertPending()
    release.complete(Unit)
    testScheduler.runCurrent()

    assertThat(ran).containsExactly("a", "b")
    a.assertSucceeded()
    b.assertSucceeded()
    // A rejection is an answer to the caller, so no failure handler hears of it
    failures.assertNone()
  }

  @Test
  fun `a reject key is free again once its command succeeds or fails`() = runTest {
    val actions = actions()

    actions.run(admission = Admission.Reject("export")) { ran += "first" }
    testScheduler.runCurrent()
    actions.run(admission = Admission.Reject("export")) { error("disk full") }
    testScheduler.runCurrent()
    val third = actions.run(admission = Admission.Reject("export")) { ran += "third" }
    testScheduler.runCurrent()

    third.assertSucceeded()
    assertThat(ran).containsExactly("first", "third")
    assertThat(failures.take().map { it.message }).containsExactly("disk full")
    assertThat(actions.keysHeld).isEqualTo(0)
  }

  @Test
  fun `a caller that sees its command settle may submit its reject key again at once`() = runTest {
    val actions = actions()
    val first = actions.run(admission = Admission.Reject("export")) { ran += "first" }
    var again: Operation? = null
    // Unconfined, so this resumes inside the state change, before the command's job completes
    // and lets go of the key
    backgroundScope.launch(Dispatchers.Unconfined) {
      first.state.first { it is OperationState.Terminal }
      again = actions.run(admission = Admission.Reject("export")) { ran += "again" }
    }
    testScheduler.runCurrent()

    first.assertSucceeded()
    checkNotNull(again).assertSucceeded()
    assertThat(ran).containsExactly("first", "again")
    assertThat(actions.keysHeld).isEqualTo(0)
  }

  @Test
  fun `commands that share a reject key reject each other whatever their ordering`() = runTest {
    val actions = actions()
    val release = CompletableDeferred<Unit>()

    // Different work on different keys, one group: none may be pending beside another
    val upload =
      actions.run(Ordering.Keyed("photo-1"), admission = Admission.Reject("sync")) {
        release.await()
      }
    val download =
      actions.run(Ordering.Keyed("photo-2"), admission = Admission.Reject("sync")) { ran += "down" }
    val serial = actions.run(admission = Admission.Reject("sync")) { ran += "serial" }
    @OptIn(DelicateDataLayerApi::class)
    val concurrent =
      actions.run(Ordering.Concurrent, admission = Admission.Reject("sync")) { ran += "concurrent" }
    // A command outside the group runs beside it
    val other =
      actions.run(Ordering.Keyed("photo-2"), admission = Admission.Reject("export")) {
        ran += "export"
      }
    testScheduler.runCurrent()

    upload.assertRunning()
    download.assertFailed<CommandRejectedException>()
    serial.assertFailed<CommandRejectedException>()
    concurrent.assertFailed<CommandRejectedException>()
    other.assertSucceeded()
    assertThat(ran).containsExactly("export")
    release.complete(Unit)
  }

  @Test
  fun `a concurrent command holds its reject key until it settles`() = runTest {
    val actions = actions()
    val release = CompletableDeferred<Unit>()

    @OptIn(DelicateDataLayerApi::class)
    val first =
      actions.run(Ordering.Concurrent, admission = Admission.Reject("sync")) { release.await() }
    testScheduler.runCurrent()
    val rejected = actions.run(admission = Admission.Reject("sync")) { ran += "second" }
    release.complete(Unit)
    testScheduler.runCurrent()
    val accepted = actions.run(admission = Admission.Reject("sync")) { ran += "third" }
    testScheduler.runCurrent()

    first.assertSucceeded()
    rejected.assertFailed<CommandRejectedException>()
    accepted.assertSucceeded()
    assertThat(ran).containsExactly("third")
    assertThat(actions.keysHeld).isEqualTo(0)
  }

  @Test
  fun `a command cancelled while queued frees its reject key`() = runTest {
    val actions = actions()
    val release = CompletableDeferred<Unit>()
    actions.run { release.await() }
    testScheduler.runCurrent()

    val queued =
      actions.runCancellable(admission = Admission.Reject("export")) { ran += "cancelled" }
    actions
      .run(admission = Admission.Reject("export")) { ran += "rejected" }
      .assertFailed<CommandRejectedException>()
    queued.cancel()
    testScheduler.runCurrent()
    val next = actions.run(admission = Admission.Reject("export")) { ran += "next" }
    release.complete(Unit)
    testScheduler.runCurrent()

    queued.assertCancelled()
    next.assertSucceeded()
    assertThat(ran).containsExactly("next")
    assertThat(actions.keysHeld).isEqualTo(0)
  }

  @Test
  fun `a command cancelled while it runs keeps its reject key until its cleanup ends`() = runTest {
    val actions = actions()
    val cleanup = CompletableDeferred<Unit>()
    val running =
      actions.runCancellable(admission = Admission.Reject("export")) {
        try {
          CompletableDeferred<Unit>().await()
        } finally {
          withContext(NonCancellable) { cleanup.await() }
        }
      }
    testScheduler.runCurrent()

    running.cancel()
    testScheduler.runCurrent()
    // Its block still unwinds, so its work is not over
    actions
      .run(admission = Admission.Reject("export")) { ran += "too early" }
      .assertFailed<CommandRejectedException>()
    cleanup.complete(Unit)
    testScheduler.runCurrent()
    val next = actions.run(admission = Admission.Reject("export")) { ran += "next" }
    testScheduler.runCurrent()

    running.assertCancelled()
    next.assertSucceeded()
    assertThat(ran).containsExactly("next")
  }

  @Test
  fun `a replaced command frees its reject key`() = runTest {
    val actions = actions()
    val release = CompletableDeferred<Unit>()
    val saving = Ordering.Keyed("draft", OnPrevious.Supersede)
    actions.run(saving) { release.await() }
    testScheduler.runCurrent()

    val replaced = actions.run(saving, admission = Admission.Reject("save")) { ran += "replaced" }
    // No reject key, so it is accepted and replaces the queued save
    actions.run(saving) { ran += "newest" }
    testScheduler.runCurrent()
    replaced.assertCancelled()
    val next = actions.run(admission = Admission.Reject("save")) { ran += "next" }
    release.complete(Unit)
    testScheduler.runCurrent()

    next.assertSucceeded()
    assertThat(ran).containsExactly("next", "newest")
    assertThat(actions.keysHeld).isEqualTo(0)
  }

  @Test
  fun `a rejected call replaces and cancels nothing`() = runTest {
    val actions = actions()
    val release = CompletableDeferred<Unit>()
    val cancelling = Ordering.Keyed("search", OnPrevious.Cancel)
    val running =
      actions.run(cancelling, admission = Admission.Reject("search")) {
        release.await()
        ran += "running"
      }
    testScheduler.runCurrent()

    // Accepted, it would cancel the running command
    val rejected =
      actions.run(cancelling, admission = Admission.Reject("search")) { ran += "rejected" }
    release.complete(Unit)
    testScheduler.runCurrent()

    rejected.assertFailed<CommandRejectedException>()
    running.assertSucceeded()
    assertThat(ran).containsExactly("running")
  }

  @Test
  fun `a rejected cancellable call returns a failed handle whose cancel does nothing`() = runTest {
    val actions = actions()
    val release = CompletableDeferred<Unit>()
    val accepted =
      actions.runCancellable(admission = Admission.Reject("export")) { release.await() }
    testScheduler.runCurrent()

    val rejected =
      actions.runCancellable(admission = Admission.Reject("export")) { ran += "rejected" }
    rejected.cancel()
    testScheduler.runCurrent()

    rejected.assertFailed<CommandRejectedException>()
    accepted.assertRunning()
    // A plain call's rejected handle is plain too
    assertThat(actions.run(admission = Admission.Reject("export")) {} is CancellableOperation)
      .isFalse()
    release.complete(Unit)
  }

  @Test
  fun `an ordering key and a reject key that are equal do not collide`() = runTest {
    val actions = actions()
    val release = CompletableDeferred<Unit>()

    actions.run(Ordering.Keyed("note-1")) { release.await() }
    val accepted =
      actions.run(Ordering.Keyed("note-2"), admission = Admission.Reject("note-1")) { ran += "ran" }
    testScheduler.runCurrent()

    accepted.assertSucceeded()
    release.complete(Unit)
  }

  @Test
  fun `a runner whose scope ended keeps no reject key`() = runTest {
    val scope =
      CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext.job))
    val actions = RealActionRunner(scope, failures)
    actions.run(admission = Admission.Reject("a")) { CompletableDeferred<Unit>().await() }
    actions.run(admission = Admission.Reject("b")) {}
    testScheduler.runCurrent()

    scope.cancel()
    testScheduler.runCurrent()
    // Submitted after the scope ended, so it settles at once and holds nothing
    actions.run(admission = Admission.Reject("c")) {}.assertCancelled()

    assertThat(actions.keysHeld).isEqualTo(0)
  }

  @Test
  fun `a call that loses its reject key to a call made meanwhile is rejected`() = runTest {
    val actions = actions()
    val release = CompletableDeferred<Unit>()
    var winner: Operation? = null
    // The first lookup of this key runs after the call read the runner's state and before it
    // links, so the other call lands in between, as a second thread's would
    val key =
      HookedKey("export") {
        winner =
          actions.run(admission = Admission.Reject(HookedKey("export"))) {
            release.await()
            ran += "winner"
          }
      }

    val loser = actions.run(admission = Admission.Reject(key)) { ran += "loser" }
    release.complete(Unit)
    testScheduler.runCurrent()

    loser.assertFailed<CommandRejectedException>()
    checkNotNull(winner).assertSucceeded()
    assertThat(ran).containsExactly("winner")
    assertThat(actions.keysHeld).isEqualTo(0)
  }

  @Test
  fun `a call that another command links beside is still accepted in its order`() = runTest {
    val actions = actions()
    var between: Operation? = null
    // Another key's command links while this call is between its read and its link
    val key =
      HookedKey("a") { between = actions.run(admission = Admission.Reject("b")) { ran += "b" } }

    val a = actions.run(admission = Admission.Reject(key)) { ran += "a" }
    val again = actions.run(admission = Admission.Reject(HookedKey("a"))) { ran += "a again" }
    testScheduler.runCurrent()

    a.assertSucceeded()
    checkNotNull(between).assertSucceeded()
    again.assertFailed<CommandRejectedException>()
    // b linked first, and a read the runner again and linked behind it
    assertThat(ran).containsExactly("b", "a")
  }

  @Test
  fun `a rejection deep in a backlog compares a bounded number of keys`() = runTest {
    val actions = actions()
    val release = CompletableDeferred<Unit>()
    val counter = EqualsCounter()
    actions.run { release.await() }
    testScheduler.runCurrent()

    val accepted =
      List(BACKLOG) { i ->
        actions.run(admission = Admission.Reject(CountedKey(i, counter))) { ran += "$i" }
      }
    val rejected =
      List(BACKLOG) { i ->
        actions.run(admission = Admission.Reject(CountedKey(i, counter))) { ran += "again $i" }
      }

    rejected.forEach { it.assertFailed<CommandRejectedException>() }
    release.complete(Unit)
    testScheduler.runCurrent()
    accepted.forEach { it.assertSucceeded() }
    assertThat(ran).isEqualTo(List(BACKLOG) { "$it" })
    assertThat(actions.keysHeld).isEqualTo(0)
    // Each call and each release looks its key up in a map. A walk along the line of waiting
    // commands would compare each new key with every one of them, about BACKLOG squared in all.
    assertThat(counter.count).isLessThanOrEqualTo(16 * BACKLOG)
    failures.assertNone()
  }

  @Test
  fun `commands cancelled across a large backlog free their keys and the rest keep their order`() =
    runTest {
      val actions = actions()
      val release = CompletableDeferred<Unit>()
      val counter = EqualsCounter()
      actions.run { release.await() }
      testScheduler.runCurrent()

      val accepted =
        List(BACKLOG) { i ->
          actions.runCancellable(admission = Admission.Reject(CountedKey(i, counter))) {
            ran += "$i"
          }
        }
      accepted.filterIndexed { i, _ -> i % 3 == 0 }.forEach { it.cancel() }
      testScheduler.runCurrent()
      val retried =
        List(BACKLOG) { i ->
          actions.run(admission = Admission.Reject(CountedKey(i, counter))) { ran += "retry $i" }
        }

      // A cancelled command's key is free once it settled. Every other key still rejects.
      for ((i, handle) in retried.withIndex()) {
        if (i % 3 == 0) {
          accepted[i].assertCancelled()
          handle.assertPending()
        } else {
          handle.assertFailed<CommandRejectedException>()
        }
      }
      release.complete(Unit)
      testScheduler.runCurrent()
      val kept = (0 until BACKLOG).filter { it % 3 != 0 }
      assertThat(ran).isEqualTo(kept.map { "$it" } + (0 until BACKLOG step 3).map { "retry $it" })
      assertThat(actions.keysHeld).isEqualTo(0)
      assertThat(counter.count).isLessThanOrEqualTo(16 * BACKLOG)
      failures.assertNone()
    }

  @Test
  fun `a join deep in a backlog compares only the newest command's key`() = runTest {
    val actions = actions()
    val release = CompletableDeferred<Unit>()
    val counter = EqualsCounter()
    actions.run { release.await() }
    testScheduler.runCurrent()

    val queued =
      List(BACKLOG) { i ->
        actions.run(admission = Admission.Join(CountedKey(i, counter))) { ran += "$i" }
      }
    val joined =
      actions.run(admission = Admission.Join(CountedKey(BACKLOG - 1, counter))) { ran += "joined" }
    val older = actions.run(admission = Admission.Join(CountedKey(0, counter))) { ran += "0 again" }

    assertThat(joined).isSameInstanceAs(queued.last())
    assertThat(older).isNotSameInstanceAs(queued.first())
    // One comparison per call, against the newest command alone
    assertThat(counter.count).isLessThanOrEqualTo(BACKLOG + 2)
    release.complete(Unit)
    testScheduler.runCurrent()
    assertThat(ran).isEqualTo(List(BACKLOG) { "$it" } + "0 again")
    assertThat(failures.all).isEmpty()
  }

  /** Counts the `equals` calls of every [CountedKey] that shares it. */
  private class EqualsCounter {
    var count = 0
  }

  /** A key that counts how often it is compared with another key. */
  private class CountedKey(val id: Int, val counter: EqualsCounter) {
    override fun hashCode(): Int = id

    override fun equals(other: Any?): Boolean {
      counter.count++
      return other is CountedKey && other.id == id
    }
  }

  /**
   * A key equal to every [HookedKey] with the same [name], which runs [onFirstLookup] the first
   * time a map asks for its hash.
   */
  private class HookedKey(val name: String, private var onFirstLookup: (() -> Unit)? = null) {
    override fun hashCode(): Int {
      val hook = onFirstLookup
      onFirstLookup = null
      hook?.invoke()
      return name.hashCode()
    }

    override fun equals(other: Any?): Boolean = other is HookedKey && other.name == name
  }

  private companion object {
    const val BACKLOG = 5_000
  }
}
