// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import dev.zacsweers.fieldspottr.data.core.testing.LaggingOutboxStores
import dev.zacsweers.fieldspottr.data.core.testing.TestFailures
import dev.zacsweers.fieldspottr.data.core.testing.testClock
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable

/**
 * An optimistic store's clear after the owner's scope stopped, as on logout. Nothing collects the
 * store's data or pending rows, so the set learns of each change only from its own handles.
 */
class OptimisticQueryStoreClearTest {
  @Serializable data class Add(val tag: String)

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
}

/** Adds the tag once, and makes a list from nothing. */
private fun addOnce(current: List<String>?, add: OptimisticQueryStoreClearTest.Add): List<String> {
  val tags = current.orEmpty()
  return if (add.tag in tags) {
    tags
  } else {
    tags + add.tag
  }
}

private val START = Instant.fromEpochSeconds(1_700_000_000)

/** The tag list's outbox, whose server answers nothing. */
private val Tags = OutboxKey<OptimisticQueryStoreClearTest.Add>("clearTags")
