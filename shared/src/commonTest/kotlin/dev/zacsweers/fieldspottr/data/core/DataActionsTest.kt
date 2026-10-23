// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/** How the runner settles handles, and the guard that fails a wait which can never end. */
class DataActionsTest {

  private val failures = mutableListOf<Throwable>()

  private fun TestScope.actions() = DataActions(backgroundScope) { failures += it }

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
      assertThat(failures.isEmpty()).isEqualTo(true)
    }

  @Test
  fun `a command whose scope ends while it runs settles cancelled even when its block returns`() =
    runTest {
      val scope =
        CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext.job))
      val actions = DataActions(scope) { failures += it }
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
      assertThat(failures.isEmpty()).isEqualTo(true)
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
    assertThat(outer.await()).isFailedWithIllegalState()
    // The inner command still runs once the outer one is done
    assertThat(inner.await()).isEqualTo(OperationState.Succeeded)
  }

  @Test
  fun `a command awaiting its own handle fails at once`() = runTest {
    val actions = actions()
    lateinit var self: Operation

    self = actions.run { self.await() }
    assertThat(self.await()).isFailedWithIllegalState()
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
    assertThat(outer.await()).isFailedWithIllegalState()
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
    assertThat(failures.isEmpty()).isEqualTo(true)
  }

  @Test
  fun `a mutation that awaits a request on its own query fails at once`() = runTest {
    val query =
      DataQuery.Factory(backgroundScope, onFailure = { failures += it }, clock = FakeClock(START))
        .create(QueryStore.inMemory(), fetch = { 1 }, freshness = Freshness.staleAfter(1.hours))

    val mutation = query.mutate(requireFresh = false) { query.requestFetch(force = false).await() }
    assertThat(mutation.await()).isFailedWithIllegalState()
  }

  private fun assertk.Assert<OperationState.Terminal>.isFailedWithIllegalState() = given { state ->
    assertThat(state).isInstanceOf<OperationState.Failed>()
    assertThat((state as OperationState.Failed).cause).isInstanceOf<IllegalStateException>()
  }

  private companion object {
    val START = Instant.parse("2026-06-10T12:00:00Z")
  }
}
