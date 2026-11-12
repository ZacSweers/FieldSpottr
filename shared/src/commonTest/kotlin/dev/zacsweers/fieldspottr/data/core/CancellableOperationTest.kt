// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import dev.zacsweers.fieldspottr.data.core.testing.TestFetch
import dev.zacsweers.fieldspottr.data.core.testing.assertCancelled
import dev.zacsweers.fieldspottr.data.core.testing.assertPending
import dev.zacsweers.fieldspottr.data.core.testing.assertRunning
import dev.zacsweers.fieldspottr.data.core.testing.assertSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.awaitCancelled
import dev.zacsweers.fieldspottr.data.core.testing.awaitSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.testDataScope
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * [CancellableOperation.cancel] on handles from [ActionRunner.runCancellable] and
 * [ConflatedActionRunner.runCancellable], and the plain handles that `run` gives instead.
 */
class CancellableOperationTest {

  @Test
  fun `a command cancelled while queued never runs and the next one does`() = runTest {
    val data = testDataScope()
    val actions = data.newActionRunner()
    val ran = mutableListOf<String>()
    val release = CompletableDeferred<Unit>()

    val first = actions.runCancellable {
      ran += "A"
      release.await()
    }
    val second = actions.runCancellable { ran += "B" }
    val third = actions.runCancellable { ran += "C" }
    testScheduler.runCurrent()
    second.assertPending()

    second.cancel()
    release.complete(Unit)
    testScheduler.runCurrent()

    assertThat(ran).containsExactly("A", "C")
    first.assertSucceeded()
    second.assertCancelled()
    third.assertSucceeded()
    data.assertNoFailures()
  }

  @Test
  fun `a command cancelled while running sees it and settles once its cleanup ends`() = runTest {
    val data = testDataScope()
    val actions = data.newActionRunner()
    val started = CompletableDeferred<Unit>()
    val finishCleanup = CompletableDeferred<Unit>()
    var sawCancellation = false
    var cleanedUp = false

    val handle = actions.runCancellable {
      try {
        started.complete(Unit)
        awaitCancellation()
      } catch (e: CancellationException) {
        sawCancellation = true
        throw e
      } finally {
        withContext(NonCancellable) { finishCleanup.await() }
        cleanedUp = true
      }
    }
    started.await()

    handle.cancel()
    testScheduler.runCurrent()
    assertThat(sawCancellation).isTrue()
    // Still unwinding, so the handle has not settled
    handle.assertRunning()
    assertThat(cleanedUp).isFalse()

    finishCleanup.complete(Unit)
    assertThat(handle.await()).isEqualTo(OperationState.Cancelled)
    assertThat(cleanedUp).isTrue()
    // Cancellation is not a failure, so the failure handler hears nothing
    data.assertNoFailures()
  }

  @Test
  fun `a running serial command that is cancelled lets the next one run`() = runTest {
    val data = testDataScope()
    val actions = data.newActionRunner()
    val ran = mutableListOf<String>()

    val first = actions.runCancellable {
      ran += "A"
      awaitCancellation()
    }
    val second = actions.runCancellable { ran += "B" }
    testScheduler.runCurrent()
    second.assertPending()

    first.cancel()
    first.awaitCancelled()
    second.awaitSucceeded()
    assertThat(ran).containsExactly("A", "B")
    data.assertNoFailures()
  }

  @Test
  fun `cancelling a settled command or cancelling twice changes nothing`() = runTest {
    val data = testDataScope()
    val actions = data.newActionRunner()

    val done = actions.runCancellable {}
    done.awaitSucceeded()
    done.cancel()
    done.cancel()
    done.assertSucceeded()

    val running = actions.runCancellable { awaitCancellation() }
    testScheduler.runCurrent()
    running.cancel()
    running.cancel()
    running.awaitCancelled()
    running.cancel()
    running.assertCancelled()
    data.assertNoFailures()
  }

  @OptIn(DelicateDataLayerApi::class)
  @Test
  fun `a concurrent command can be cancelled`() = runTest {
    val data = testDataScope()
    val actions = data.newActionRunner()

    val handle = actions.runCancellable(Ordering.Concurrent) { awaitCancellation() }
    testScheduler.runCurrent()
    handle.cancel()

    handle.awaitCancelled()
    data.assertNoFailures()
  }

  @Test
  fun `a conflated command cancelled by its holder lets the next one start`() = runTest {
    val data = testDataScope()
    val searches = data.newConflatedActionRunner()
    val ran = mutableListOf<String>()
    val finishCleanup = CompletableDeferred<Unit>()

    val a = searches.runCancellable {
      ran += "A"
      try {
        awaitCancellation()
      } finally {
        withContext(NonCancellable) { finishCleanup.await() }
      }
    }
    testScheduler.runCurrent()
    a.cancel()
    testScheduler.runCurrent()
    a.assertRunning()

    // Waits for A's cleanup, as a newer command always does. Its plain handle cannot cancel it.
    val b = searches.run { ran += "B" }
    assertThat(b is CancellableOperation).isFalse()
    testScheduler.runCurrent()
    b.assertPending()
    finishCleanup.complete(Unit)
    testScheduler.runCurrent()

    assertThat(ran).containsExactly("A", "B")
    a.assertCancelled()
    b.assertSucceeded()
    data.assertNoFailures()
  }

  @Test
  fun `a cancel key command cancelled while it waits for a cleanup never runs`() = runTest {
    val data = testDataScope()
    val actions = data.newActionRunner()
    val ordering = Ordering.Keyed("k", OnPrevious.Cancel)
    val ran = mutableListOf<String>()
    val finishCleanup = CompletableDeferred<Unit>()

    val a =
      actions.runCancellable(ordering) {
        ran += "A"
        try {
          awaitCancellation()
        } finally {
          withContext(NonCancellable) { finishCleanup.await() }
        }
      }
    testScheduler.runCurrent()
    // B cancels A and waits for its cleanup. Its holder then cancels B too.
    val b = actions.runCancellable(ordering) { ran += "B" }
    testScheduler.runCurrent()
    b.cancel()
    val c = actions.runCancellable(Ordering.Keyed("k")) { ran += "C" }
    testScheduler.runCurrent()
    c.assertPending()

    finishCleanup.complete(Unit)
    testScheduler.runCurrent()
    assertThat(ran).containsExactly("A", "C")
    a.assertCancelled()
    b.assertCancelled()
    c.assertSucceeded()
    data.assertNoFailures()
  }

  @Test
  fun `only runCancellable hands out a handle that can cancel`() = runTest {
    val data = testDataScope()
    val query =
      data.query(QueryStore.inMemory<Int>(), Freshness.staleAfter(1.hours), fetch = TestFetch { 1 })
    val actions = data.newActionRunner()
    val searches = data.newConflatedActionRunner()

    assertThat(actions.run {} is CancellableOperation).isFalse()
    assertThat(searches.run {} is CancellableOperation).isFalse()
    assertThat(query.requestFetch(force = true) is CancellableOperation).isFalse()
    assertThat(query.invalidate() is CancellableOperation).isFalse()
    val command = actions.runCancellable {}
    assertThat(command).isInstanceOf<CancellableOperation>()
    assertThat(searches.runCancellable {}).isInstanceOf<CancellableOperation>()
    assertThat(Operation.all(command, command) is CancellableOperation).isFalse()
    testScheduler.runCurrent()
    data.assertNoFailures()
  }

  @Test
  fun `a conflated runner built from a lambda hands out a plain handle from run`() = runTest {
    val data = testDataScope()
    val actions = data.newActionRunner()
    val ordering = Ordering.Keyed("search", OnPrevious.Cancel)
    val searches = ConflatedActionRunner { block -> actions.runCancellable(ordering, block) }
    val ran = mutableListOf<String>()

    val first = searches.run {
      ran += "A"
      awaitCancellation()
    }
    assertThat(first is CancellableOperation).isFalse()
    testScheduler.runCurrent()
    val second = searches.runCancellable { ran += "B" }

    // The plain view follows the command that the newer one cancelled
    first.awaitCancelled()
    second.awaitSucceeded()
    assertThat(ran).containsExactly("A", "B")
    data.assertNoFailures()
  }

  @Test
  fun `a plain handle's command still stops when a newer cancel command replaces it`() = runTest {
    val data = testDataScope()
    val actions = data.newActionRunner()
    val ordering = Ordering.Keyed("k", OnPrevious.Cancel)

    val first = actions.run(ordering) { awaitCancellation() }
    testScheduler.runCurrent()
    val second = actions.run(ordering) {}

    first.awaitCancelled()
    second.awaitSucceeded()
    data.assertNoFailures()
  }
}
