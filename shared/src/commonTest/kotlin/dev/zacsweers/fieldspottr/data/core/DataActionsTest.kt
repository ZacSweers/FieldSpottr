// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/** The guard that turns a wait which can never end into an immediate failure. */
class DataActionsTest {

  private val failures = mutableListOf<Throwable>()

  private fun TestScope.actions() = DataActions(backgroundScope) { failures += it }

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
