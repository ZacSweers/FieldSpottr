// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

class DataQueryTest {

  private val clock = FakeClock(START)
  private val stored = MutableStateFlow<Fetched<Int>?>(null)
  // No invalidate, so these tests cover the default that rewrites the value with a stale time
  private val store =
    QueryStore.of(stored, clear = { stored.value = null }) { value, fetchedAt ->
      stored.value = Fetched(value, fetchedAt)
    }
  private val failures = mutableListOf<Throwable>()
  private var fetches = 0
  private var fetchFails = false
  private var gate = CompletableDeferred(Unit)

  private fun TestScope.dataQuery(
    freshness: Freshness = Freshness.staleAfter(1.hours)
  ): DataQuery<Int> =
    DataQuery.Factory(backgroundScope, onFailure = { failures += it }, clock = clock)
      .create(
        store = store,
        fetch = {
          fetches++
          gate.await()
          check(!fetchFails) { "fetch failed" }
          (stored.value?.value ?: 0) + 1
        },
        freshness = freshness,
      )

  @Test
  fun `stored fetch time inside the window skips the fetch`() = runTest {
    stored.value = Fetched(1, START - 10.minutes)
    val query = dataQuery()

    assertThat(query.data.values().first()).isEqualTo(1)
    // Joins the check the collector started. advanceUntilIdle() skips backgroundScope work.
    query.requestFetch(force = false).await()
    assertThat(fetches).isEqualTo(0)
  }

  @Test
  fun `stored fetch time past the window fetches and stores the clock time`() = runTest {
    stored.value = Fetched(1, START - 2.hours)
    val query = dataQuery()

    assertThat(query.data.first { it?.value == 2 }).isEqualTo(Fetched(2, START))
    assertThat(fetches).isEqualTo(1)
  }

  @Test
  fun `stored fetch time ahead of the clock counts as stale`() = runTest {
    stored.value = Fetched(1, START + 1.hours)
    val query = dataQuery()

    query.data.first { it?.value == 2 }
    assertThat(fetches).isEqualTo(1)
  }

  @Test
  fun `an emptied store fetches again while collected`() = runTest {
    val query = dataQuery()

    backgroundScope.launch { query.data.collect {} }
    query.data.first { it?.value == 1 }
    // Lets the collector see the value. A value cleared before it does is conflated away.
    testScheduler.runCurrent()
    stored.value = null
    // Waits on the store itself, so only the running collector can have started the fetch
    stored.first { it != null }
    assertThat(fetches).isEqualTo(2)
  }

  @Test
  fun `dynamic freshness is asked on every check with the query's clock`() = runTest {
    stored.value = Fetched(1, START - 10.minutes)
    var window = 1.hours
    var checkedAt: Instant? = null
    val query =
      dataQuery(
        freshness =
          Freshness.dynamic { fetchedAt, now ->
            checkedAt = now
            now - fetchedAt >= window
          }
      )

    query.data.first()
    query.requestFetch(force = false).await()
    assertThat(fetches).isEqualTo(0)
    assertThat(checkedAt).isEqualTo(START)

    window = 5.minutes
    query.data.first { it?.value == 2 }
    assertThat(fetches).isEqualTo(1)
  }

  @Test
  fun `collectors join the fetch in flight`() = runTest {
    gate = CompletableDeferred()
    val query = dataQuery()

    val collectors = List(2) { async { query.data.first { it?.value == 1 } } }
    testScheduler.runCurrent()
    gate.complete(Unit)
    collectors.awaitAll()
    assertThat(fetches).isEqualTo(1)
  }

  @Test
  fun `a forced request fetches even when the data is fresh`() = runTest {
    stored.value = Fetched(1, START - 10.minutes)
    val query = dataQuery()

    assertThat(query.requestFetch(force = true).await()).isEqualTo(OperationState.Succeeded)
    assertThat(fetches).isEqualTo(1)
    assertThat(stored.value).isEqualTo(Fetched(2, START))
  }

  @Test
  fun `failed fetch fails the handle and reports the cause`() = runTest {
    fetchFails = true
    val query = dataQuery()

    assertThat(query.requestFetch(force = true).await()).isInstanceOf<OperationState.Failed>()
    assertThat(query.status.value).isInstanceOf<FetchStatus.Failed>()
    assertThat(failures).hasSize(1)
    assertThat(stored.value).isEqualTo(null)
  }

  @Test
  fun `invalidate marks the data stale without fetching`() = runTest {
    stored.value = Fetched(1, START - 10.minutes)
    val query = dataQuery()

    assertThat(query.invalidate().await()).isEqualTo(OperationState.Succeeded)
    assertThat(fetches).isEqualTo(0)
    assertThat(stored.value).isEqualTo(Fetched(1, QueryStore.INVALIDATED))
    assertThat(query.data.first { it?.value == 2 }).isEqualTo(Fetched(2, START))
    assertThat(fetches).isEqualTo(1)
  }

  @Test
  fun `invalidate lands after a fetch already in flight`() = runTest {
    gate = CompletableDeferred()
    val query = dataQuery()

    val fetch = query.requestFetch(force = false)
    testScheduler.runCurrent()
    val invalidate = query.invalidate()
    gate.complete(Unit)
    fetch.await()
    invalidate.await()
    // The fetch's write came first, so the value it stored is the one marked stale
    assertThat(stored.value).isEqualTo(Fetched(1, QueryStore.INVALIDATED))
  }

  @Test
  fun `forced requests made together share one fetch`() = runTest {
    gate = CompletableDeferred()
    val query = dataQuery()

    val first = query.requestFetch(force = true)
    testScheduler.runCurrent()
    // Like a second pull to refresh while the first one still spins
    val second = query.requestFetch(force = true)
    gate.complete(Unit)
    assertThat(first.await()).isEqualTo(OperationState.Succeeded)
    assertThat(second.await()).isEqualTo(OperationState.Succeeded)
    assertThat(fetches).isEqualTo(1)
  }

  @Test
  fun `a forced request after a mutation starts its own fetch`() = runTest {
    gate = CompletableDeferred()
    val query = dataQuery()

    val before = query.requestFetch(force = true)
    testScheduler.runCurrent()
    query.mutate(requireFresh = false) { it.write(100, START) }
    val after = query.requestFetch(force = true)
    gate.complete(Unit)
    // The fetch from before the write is cancelled, and the one after it lands last
    assertThat(before.await()).isEqualTo(OperationState.Cancelled)
    assertThat(after.await()).isEqualTo(OperationState.Succeeded)
    assertThat(stored.value).isEqualTo(Fetched(101, START))
  }

  @Test
  fun `stored shows the store without fetching`() = runTest {
    stored.value = Fetched(1, START - 2.hours)
    val query = dataQuery()

    assertThat(query.stored.first()).isEqualTo(Fetched(1, START - 2.hours))
    testScheduler.runCurrent()
    // Stale, yet nothing fetched
    assertThat(fetches).isEqualTo(0)
  }

  @Test
  fun `a request after invalidate fetches again`() = runTest {
    gate = CompletableDeferred()
    val query = dataQuery()

    val first = query.requestFetch(force = false)
    testScheduler.runCurrent()
    query.invalidate()
    val second = query.requestFetch(force = false)
    gate.complete(Unit)
    first.await()
    second.await()
    // The second request queued behind the invalidation instead of joining the first fetch
    assertThat(fetches).isEqualTo(2)
    assertThat(stored.value).isEqualTo(Fetched(2, START))
  }

  @Test
  fun `a superseded forced fetch that ignores cancellation never writes`() = runTest {
    val state = MutableStateFlow<Fetched<Int>?>(null)
    val writes = mutableListOf<Int>()
    val store =
      QueryStore.of(state, clear = { state.value = null }) { value, fetchedAt ->
        writes += value
        state.value = Fetched(value, fetchedAt)
      }
    val firstStarted = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    var calls = 0
    val query =
      DataQuery.Factory(backgroundScope, onFailure = { failures += it }, clock = clock)
        .create(
          store = store,
          fetch = {
            calls++
            if (calls == 1) {
              firstStarted.complete(Unit)
              // Like a callback API that finishes whether or not anyone still waits
              withContext(NonCancellable) { release.await() }
            }
            calls
          },
          freshness = Freshness.staleAfter(1.hours),
        )

    val first = query.requestFetch(force = true)
    firstStarted.await()
    // After an invalidation, a forced request cancels the older forced fetch instead of joining it
    query.invalidate()
    val second = query.requestFetch(force = true)
    testScheduler.runCurrent()
    release.complete(Unit)
    assertThat(first.await()).isEqualTo(OperationState.Cancelled)
    assertThat(second.await()).isEqualTo(OperationState.Succeeded)
    assertThat(writes).containsExactly(2)
  }

  @Test
  fun `a mutation waits for the fetch in flight and later requests wait for it`() = runTest {
    gate = CompletableDeferred()
    val query = dataQuery()

    val fetch = query.requestFetch(force = false)
    testScheduler.runCurrent()
    val mutation = query.mutate(requireFresh = false) { it.write(100, START) }
    val later = query.requestFetch(force = false)
    gate.complete(Unit)
    fetch.await()
    mutation.await()
    later.await()
    // The fetch's older result landed first, and the later request found the mutation's write fresh
    assertThat(stored.value).isEqualTo(Fetched(100, START))
    assertThat(fetches).isEqualTo(1)
  }

  @Test
  fun `a mutation that requires fresh data fetches stale data first`() = runTest {
    stored.value = Fetched(1, START - 2.hours)
    val query = dataQuery()
    var seen: Int? = null

    query.mutate(requireFresh = true) { seen = it.data.first()?.value }.await()
    assertThat(seen).isEqualTo(2)
    assertThat(fetches).isEqualTo(1)
    // Fresh now, so this one skips the fetch
    query.mutate(requireFresh = true) { seen = it.data.first()?.value }.await()
    assertThat(fetches).isEqualTo(1)
  }

  @Test
  fun `a failed fetch before a mutation fails it and skips the block`() = runTest {
    fetchFails = true
    val query = dataQuery()
    var ran = false

    assertThat(query.mutate(requireFresh = true) { ran = true }.await())
      .isInstanceOf<OperationState.Failed>()
    assertThat(ran).isEqualTo(false)
  }

  @Test
  fun `in-memory stores clear and invalidate`() = runTest {
    val single = QueryStore.inMemory<Int>()
    single.write(1, START)
    single.invalidate()
    assertThat(single.data.first()).isEqualTo(Fetched(1, QueryStore.INVALIDATED))
    single.clear()
    assertThat(single.data.first()).isEqualTo(null)

    val byKey = QueryStore.inMemoryByKey<String, Int>()
    byKey("a").write(1, START)
    byKey("b").write(2, START)
    byKey("a").invalidate()
    assertThat(byKey("a").data.first()).isEqualTo(Fetched(1, QueryStore.INVALIDATED))
    assertThat(byKey("b").data.first()).isEqualTo(Fetched(2, START))
    byKey("b").clear()
    assertThat(byKey("b").data.first()).isEqualTo(null)
    byKey.clear()
    assertThat(byKey("a").data.first()).isEqualTo(null)
  }

  @Test
  fun `a combined handle settles once every operation has`() = runTest {
    stored.value = Fetched(1, START)
    val query = dataQuery()

    assertThat(Operation.all(emptyList()).await()).isEqualTo(OperationState.Succeeded)
    // Holds the first forced fetch. After the invalidation, the second cancels it mid-fetch.
    gate = CompletableDeferred()
    val first = query.requestFetch(force = true)
    testScheduler.runCurrent()
    query.invalidate()
    val both = Operation.all(listOf(first, query.requestFetch(force = true)))
    testScheduler.runCurrent()
    gate.complete(Unit)
    assertThat(both.await()).isEqualTo(OperationState.Cancelled)
    fetchFails = true
    assertThat(Operation.all(listOf(query.requestFetch(force = true))).await())
      .isInstanceOf<OperationState.Failed>()
  }

  private companion object {
    val START = Instant.parse("2026-06-10T12:00:00Z")
  }
}
