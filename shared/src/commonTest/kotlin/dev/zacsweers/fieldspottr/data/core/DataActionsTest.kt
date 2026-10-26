// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import dev.zacsweers.fieldspottr.data.core.impl.RealDataActions
import dev.zacsweers.fieldspottr.data.core.testing.TestDataScope
import dev.zacsweers.fieldspottr.data.core.testing.TestFailures
import dev.zacsweers.fieldspottr.data.core.testing.assertCancelled
import dev.zacsweers.fieldspottr.data.core.testing.assertFailed
import dev.zacsweers.fieldspottr.data.core.testing.assertPending
import dev.zacsweers.fieldspottr.data.core.testing.assertRunning
import dev.zacsweers.fieldspottr.data.core.testing.assertSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.testDataScope
import dev.zacsweers.fieldspottr.data.core.testing.testDataScopeFactory
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * How the runner settles handles and reports failures, and the guard that fails a wait which can
 * never end.
 */
class DataActionsTest {

  private lateinit var data: TestDataScope

  private fun TestScope.actions(): DataActions {
    if (!::data.isInitialized) {
      data = testDataScope()
    }
    return data.newActions()
  }

  @Test
  fun `a command cancelled while it runs settles cancelled even when its block returns`() =
    runTest {
      val actions = actions()
      val started = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()

      val first =
        actions.run(Ordering.Keyed("k", OnPrevious.Cancel)) {
          started.complete(Unit)
          // Like a blocking call that finishes whether or not anyone still waits
          withContext(NonCancellable) { release.await() }
        }
      started.await()
      val second = actions.run(Ordering.Keyed("k", OnPrevious.Cancel)) {}
      testScheduler.runCurrent()
      release.complete(Unit)
      assertThat(first.await()).isEqualTo(OperationState.Cancelled)
      assertThat(second.await()).isEqualTo(OperationState.Succeeded)
      // Cancellation is not a failure, so the failure handler hears nothing
      data.actionFailures.assertNone()
    }

  @Test
  fun `a command whose scope ends while it runs settles cancelled even when its block returns`() =
    runTest {
      val scope =
        CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext.job))
      val data = testDataScopeFactory().create(scope)
      val actions = data.newActions()
      val started = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()

      val command = actions.run {
        started.complete(Unit)
        withContext(NonCancellable) { release.await() }
      }
      started.await()
      scope.cancel()
      release.complete(Unit)
      assertThat(command.await()).isEqualTo(OperationState.Cancelled)
      data.actionFailures.assertNone()
    }

  @Test
  fun `awaiting a command queued behind the caller on its key fails at once`() = runTest {
    val actions = actions()
    lateinit var inner: Operation

    val outer =
      actions.run(Ordering.Keyed("k")) {
        inner = actions.run(Ordering.Keyed("k")) {}
        inner.await()
      }
    outer.await()
    outer.assertFailed<IllegalStateException>()
    // The inner command still runs once the outer one is done
    assertThat(inner.await()).isEqualTo(OperationState.Succeeded)
  }

  @Test
  fun `a command awaiting its own handle fails at once`() = runTest {
    val actions = actions()
    lateinit var self: Operation

    self = actions.run { self.await() }
    self.await()
    self.assertFailed<IllegalStateException>()
  }

  @OptIn(DelicateDataLayerApi::class)
  @Test
  fun `a concurrent command awaiting its own handle fails at once`() = runTest {
    val actions = actions()
    lateinit var self: Operation

    // Assigned before the block starts, since nothing runs until runCurrent()
    self = actions.run(Ordering.Concurrent) { self.await() }
    testScheduler.runCurrent()
    self.assertFailed<IllegalStateException>()
  }

  @Test
  fun `a runner whose scope ended keeps none of the keys given to it`() = runTest {
    val owner =
      CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext.job))
    val actions = RealDataActions(owner, onFailure = {})
    owner.cancel()
    var ran = 0

    val handles = List(20) { i -> actions.run(Ordering.Keyed("key$i")) { ran++ } }
    testScheduler.runCurrent()

    handles.forEach { it.assertCancelled() }
    assertThat(ran).isEqualTo(0)
    assertThat(actions.keysHeld).isEqualTo(0)
  }

  @Test
  fun `queued supersede commands run only the newest block`() = runTest {
    val actions = actions()
    val ran = mutableListOf<String>()

    val old = actions.run(Ordering.Keyed("save", OnPrevious.Supersede)) { ran += "old" }
    val new = actions.run(Ordering.Keyed("save", OnPrevious.Supersede)) { ran += "new" }
    old.assertPending()
    new.assertPending()
    testScheduler.runCurrent()

    assertThat(ran).containsExactly("new")
    old.assertCancelled()
    new.assertSucceeded()
  }

  @Test
  fun `queued cancel commands run only the newest block`() = runTest {
    val actions = actions()
    val ran = mutableListOf<String>()

    val old = actions.run(Ordering.Keyed("save", OnPrevious.Cancel)) { ran += "old" }
    val new = actions.run(Ordering.Keyed("save", OnPrevious.Cancel)) { ran += "new" }
    testScheduler.runCurrent()

    assertThat(ran).containsExactly("new")
    old.assertCancelled()
    new.assertSucceeded()
  }

  @Test
  fun `the newest of three queued replacing commands is the only one that runs`() = runTest {
    for (mode in listOf(OnPrevious.Supersede, OnPrevious.Cancel)) {
      val actions = actions()
      val ran = mutableListOf<String>()

      val a = actions.run(Ordering.Keyed("save", mode)) { ran += "a" }
      val b = actions.run(Ordering.Keyed("save", mode)) { ran += "b" }
      val c = actions.run(Ordering.Keyed("save", mode)) { ran += "c" }
      // Replaced when c was submitted, so neither older block starts once work runs
      testScheduler.runCurrent()

      assertThat(ran).containsExactly("c")
      a.assertCancelled()
      b.assertCancelled()
      c.assertSucceeded()
    }
  }

  @Test
  fun `a supersede command waits for a running one and replaces only those queued`() = runTest {
    val actions = actions()
    val ran = mutableListOf<String>()
    val release = CompletableDeferred<Unit>()

    val running =
      actions.run(Ordering.Keyed("save", OnPrevious.Supersede)) {
        ran += "running"
        release.await()
      }
    testScheduler.runCurrent()
    val queued = actions.run(Ordering.Keyed("save", OnPrevious.Supersede)) { ran += "queued" }
    val newest = actions.run(Ordering.Keyed("save", OnPrevious.Supersede)) { ran += "newest" }
    testScheduler.runCurrent()
    // The running block is never replaced, and the newest waits for it
    running.assertRunning()
    newest.assertPending()

    release.complete(Unit)
    testScheduler.runCurrent()
    assertThat(ran).containsExactly("running", "newest")
    running.assertSucceeded()
    queued.assertCancelled()
    newest.assertSucceeded()
  }

  @Test
  fun `a combined handle over a queued command on the caller's key fails at once`() = runTest {
    val actions = actions()

    val outer =
      actions.run(Ordering.Keyed("k")) {
        val other = actions.run(Ordering.Keyed("other")) {}
        val queued = actions.run(Ordering.Keyed("k")) {}
        Operation.all(listOf(other, queued)).await()
      }
    outer.await()
    outer.assertFailed<IllegalStateException>()
  }

  @Test
  fun `waits on other keys and other runners still work`() = runTest {
    val actions = actions()
    val otherRunner = actions()

    val outer =
      actions.run(Ordering.Keyed("a")) {
        check(actions.run(Ordering.Keyed("b")) {}.await() == OperationState.Succeeded)
        check(otherRunner.run(Ordering.Keyed("a")) {}.await() == OperationState.Succeeded)
      }
    assertThat(outer.await()).isEqualTo(OperationState.Succeeded)
    data.actionFailures.assertNone()
  }

  @Test
  fun `a data scope runner reports to its own failure handler when given one`() = runTest {
    val data = testDataScope()
    val own = TestFailures()
    val boom = IllegalStateException("boom")

    data.newActions(own).run(Ordering.Serial) { throw boom }.await()
    data.newActions().run(Ordering.Serial) { throw boom }.await()

    assertThat(own.all).containsExactly(boom)
    assertThat(data.actionFailures.all).containsExactly(boom)
  }

  @Test
  fun `a mutation that awaits a request on its own query fails at once`() = runTest {
    val query =
      testDataScope()
        .query(
          QueryStore.inMemory(),
          fetch = { 1 },
          freshness = Freshness.staleAfter(1.hours),
        )

    val mutation = query.mutate(requireFresh = false) { query.requestFetch(force = false).await() }
    mutation.await()
    mutation.assertFailed<IllegalStateException>()
  }
}
