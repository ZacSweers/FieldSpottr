// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import dev.zacsweers.fieldspottr.data.core.testing.Add
import dev.zacsweers.fieldspottr.data.core.testing.LaggingOutboxStores
import dev.zacsweers.fieldspottr.data.core.testing.TestFailures
import dev.zacsweers.fieldspottr.data.core.testing.TestFetch
import dev.zacsweers.fieldspottr.data.core.testing.addOnce
import dev.zacsweers.fieldspottr.data.core.testing.testClock
import dev.zacsweers.fieldspottr.data.core.testing.testDataScope
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** A query and an optimistic view while a store read or the row list lags behind a write. */
class HeldReadRaceTest {
  @Test
  fun `passive state shows a value written to the store right after a refresh`() = runTest {
    val clock = testClock(START)
    val store = QueryStore.inMemory<Int>()
    val query =
      testDataScope(clock = clock)
        .query(store, Freshness.staleAfter(1.hours), fetch = TestFetch { 1 })
    backgroundScope.launch { query.stateAutoFetch.collect {} }
    runCurrent()
    query.refresh().awaitOrThrow()

    store.write(Fetched(2, clock.now()))

    assertThat(query.state.first().value).isEqualTo(2)
  }

  @Test
  fun `a forced refresh fetches once when the read of the check it took over fails`() = runTest {
    val store = GatedReadStore(QueryStore.inMemory(Fetched(0, START - 2.hours)))
    val fetch = TestFetch { 1 }
    val query =
      testDataScope(clock = testClock(START))
        .query(store, Freshness.staleAfter(1.hours), fetch = fetch)
    query.fetchIfStale()
    runCurrent()
    store.readStarted.await()

    val forced = query.refresh()
    runCurrent()
    store.readResult.completeExceptionally(IllegalStateException("store read failed"))

    assertThat(forced.await()).isEqualTo(OperationState.Succeeded)
    assertThat(fetch.callCount).isEqualTo(1)
  }

  @Test
  fun `two changes on one key show in send order while the row list is held`() = runTest {
    val failures = TestFailures()
    val gate = CompletableDeferred<Unit>()
    val sent = mutableListOf<String>()
    val data =
      DataScope.Factory(
          onFailure = failures,
          online = MutableStateFlow(true),
          // The list shows its first value only, so the store never sees either row listed
          stores = LaggingOutboxStores(firstListOnly = true),
          clock = testClock(START),
        )
        .create(
          backgroundScope,
          setOf(
            Tags.sender { change, _ ->
              gate.await()
              sent += change.mutation.tag
            }
          ),
        )
    val tags =
      data.optimisticStores(
        Tags,
        base = { QueryStore.inMemory(Fetched(listOf("a"), START)) },
        apply = ::addOnce,
      )("x")
    val seen = mutableListOf<List<String>>()
    backgroundScope.launch { tags.data.collect { seen += it?.value.orEmpty() } }
    runCurrent()

    tags.mutate(Add("b"))
    tags.mutate(Add("c"))
    runCurrent()
    assertThat(seen.last()).isEqualTo(listOf("a", "b", "c"))
    gate.complete(Unit)
    advanceTimeBy(2.seconds)
    runCurrent()

    assertThat(sent).isEqualTo(listOf("b", "c"))
    assertThat(seen.last()).isEqualTo(listOf("a", "b", "c"))
    // No value showed the newer change without the older one, or ahead of it
    assertThat(seen.filter { "c" in it && ("b" !in it || it.indexOf("c") < it.indexOf("b")) })
      .isEmpty()
    failures.assertNone()
  }

  /** [delegate] whose first read of [data] waits for [readResult], which a test fails or ends. */
  private class GatedReadStore<T>(private val delegate: InMemoryQueryStore<T>) : QueryStore<T> {
    val readStarted = CompletableDeferred<Unit>()
    val readResult = CompletableDeferred<Unit>()
    private var gated = true

    override val data: Flow<Fetched<T>?> = flow {
      if (gated) {
        gated = false
        readStarted.complete(Unit)
        readResult.await()
      }
      emitAll(delegate.data)
    }

    override suspend fun writeFetched(fetched: Fetched<T>, keepsStored: KeepsStored) =
      delegate.writeFetched(fetched, keepsStored)

    override suspend fun clearFetched(keepsStored: KeepsStored) = delegate.clearFetched(keepsStored)

    override suspend fun invalidate() = delegate.invalidate()
  }
}

private val START = Instant.fromEpochSeconds(1_700_000_000)
private val Tags = OutboxKey<KeyedMutation<String, Add>>("heldTags")
