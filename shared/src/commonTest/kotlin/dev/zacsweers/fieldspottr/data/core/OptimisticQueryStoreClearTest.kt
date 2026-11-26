// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasMessage
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import dev.zacsweers.fieldspottr.data.core.testing.Add
import dev.zacsweers.fieldspottr.data.core.testing.LaggingOutboxStores
import dev.zacsweers.fieldspottr.data.core.testing.TestFailures
import dev.zacsweers.fieldspottr.data.core.testing.addOnce
import dev.zacsweers.fieldspottr.data.core.testing.testClock
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * An optimistic store's clear after the owner's scope stopped, as on logout. Unless a test says
 * otherwise, nothing collects the store's data or pending rows, so the set learns of each change
 * only from its own handles.
 */
class OptimisticQueryStoreClearTest {
  /** A tag list over [stores], offline, in a data scope whose scope [owner] owns. */
  private class Harness(scope: TestScope, val stores: OutboxStores) {
    val owner = Job(scope.backgroundScope.coroutineContext.job)
    val failures = TestFailures()
    val tags =
      DataScope.Factory(
          onFailure = failures,
          online = MutableStateFlow(false),
          stores = stores,
          clock = scope.testClock(START),
        )
        .create(CoroutineScope(scope.backgroundScope.coroutineContext + owner), setOf(sender))
        .optimisticStore(Tags, base = QueryStore.inMemory<List<String>>(), apply = ::addOnce)

    private companion object {
      val sender = Tags.sender { _, _ -> }
    }
  }

  @Test
  fun `a logout clear drops a handed off change whose row is already gone`() = runTest {
    val stores = InMemoryOutboxStores()
    val h = Harness(this, stores)
    val handle = h.tags.mutate(Add("b"))
    handle.stored.await()
    h.owner.cancel()
    assertThat(handle.await()).isEqualTo(OperationState.Cancelled)
    runCurrent()

    stores.clearAll()
    h.tags.clear()

    assertThat(h.tags.data.first()).isNull()
    h.failures.assertNone()
  }

  @Test
  fun `a handed off change whose row is still stored keeps showing`() = runTest {
    val h = Harness(this, InMemoryOutboxStores())
    val handle = h.tags.mutate(Add("b"))
    handle.stored.await()
    h.owner.cancel()
    assertThat(handle.await()).isEqualTo(OperationState.Cancelled)
    runCurrent()

    assertThat(h.tags.data.first()?.value).isEqualTo(listOf("b"))
    advanceTimeBy(5.seconds)
    assertThat(h.tags.data.first()?.value).isEqualTo(listOf("b"))
    h.failures.assertNone()
  }

  /**
   * The clear discards one status write at a time, each taking a second. A change made meanwhile
   * comes after the clear began, so its row stays and it keeps showing.
   */
  @Test
  fun `a change made while a clear runs stays after the clear`() = runTest {
    val stores = LaggingOutboxStores(updateTakes = 1.seconds)
    val h = Harness(this, stores)
    h.tags.mutate(Add("b")).stored.await()
    val clearing = backgroundScope.launch { h.tags.clear() }
    runCurrent()
    h.tags.mutate(Add("c")).stored.await()
    advanceTimeBy(5.seconds)
    runCurrent()
    clearing.join()

    val rows = stores.delegate.open("clearTags", Add.serializer()).entries().first()
    assertThat(rows.map { it.payload }).containsExactly(Add("c"))
    assertThat(h.tags.data.first()?.value).isEqualTo(listOf("c"))
    h.failures.assertNone()
  }

  /**
   * The clear discards one status write at a time, each taking a second, and the second write
   * fails. A collector holds the only row list, which still shows both rows. The first change's row
   * is gone, so it leaves the view and the pending rows when the clear throws.
   */
  @Test
  fun `a clear whose second discard fails drops the first change`() = runTest {
    val stores = LaggingOutboxStores(updateTakes = 1.seconds, firstListOnly = true)
    val h = Harness(this, stores)
    h.tags.mutate(Add("b")).stored.await()
    h.tags.mutate(Add("c")).stored.await()
    backgroundScope.launch { h.tags.data.collect {} }
    runCurrent()
    val clearing = async { assertFailsWith<IllegalStateException> { h.tags.clear() } }
    advanceTimeBy(1.5.seconds)
    stores.failDiscards = true
    advanceTimeBy(1.seconds)
    runCurrent()

    assertThat(clearing.await()).hasMessage("Disk error")
    assertThat(h.tags.data.first()?.value).isEqualTo(listOf("c"))
    assertThat(h.tags.pending.first().map { it.mutation }).containsExactly(Add("c"))
  }

  /**
   * As above, with the caller cancelled while the second status write runs. A collector holds the
   * only row list, which still shows both rows.
   */
  @Test
  fun `a clear cancelled between two discards drops the first change`() = runTest {
    val stores = LaggingOutboxStores(updateTakes = 1.seconds, firstListOnly = true)
    val h = Harness(this, stores)
    h.tags.mutate(Add("b")).stored.await()
    h.tags.mutate(Add("c")).stored.await()
    backgroundScope.launch { h.tags.data.collect {} }
    runCurrent()
    val clearing = launch { h.tags.clear() }
    advanceTimeBy(1.5.seconds)
    clearing.cancel()
    runCurrent()

    assertThat(clearing.isCancelled).isTrue()
    assertThat(h.tags.data.first()?.value).isEqualTo(listOf("c"))
  }
}

private val START = Instant.fromEpochSeconds(1_700_000_000)

/** The tag list's outbox, whose server answers nothing. */
private val Tags = OutboxKey<Add>("clearTags")
