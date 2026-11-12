// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import dev.zacsweers.fieldspottr.data.core.FetchStatus.Failed
import dev.zacsweers.fieldspottr.data.core.FetchStatus.Fetching
import dev.zacsweers.fieldspottr.data.core.FetchStatus.Idle
import dev.zacsweers.fieldspottr.data.core.testing.LaggingQueryStore
import dev.zacsweers.fieldspottr.data.core.testing.RecordingQueryStore
import dev.zacsweers.fieldspottr.data.core.testing.TestDataScope
import dev.zacsweers.fieldspottr.data.core.testing.TestFetch
import dev.zacsweers.fieldspottr.data.core.testing.TestKeyedFetch
import dev.zacsweers.fieldspottr.data.core.testing.awaitFailed
import dev.zacsweers.fieldspottr.data.core.testing.awaitSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.testClock
import dev.zacsweers.fieldspottr.data.core.testing.testDataScope
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** [DataQuery.state], and the one distinctness rule that it shares with `data` and `stored`. */
class QueryStateTest {
  private val offline = IllegalStateException("offline")
  // Returns how many calls have started, so each fetch returns a value of its own
  private val fetch: TestFetch<Int> = TestFetch { fetch.callCount }
  private lateinit var data: TestDataScope

  private fun TestScope.query(store: QueryStore<Int>): DataQuery<Int> {
    data = testDataScope(clock = testClock(START))
    return data.query(store, Freshness.staleAfter(1.hours), fetch = fetch)
  }

  @Test
  fun `collecting state fetches stale data and an uncollected query fetches nothing`() = runTest {
    val query = query(QueryStore.inMemory(Fetched(0, START - 2.hours)))
    runCurrent()
    assertThat(fetch.callCount).isEqualTo(0)

    assertThat(query.state.first { it.value == 1 }).isEqualTo(QueryState(Fetched(1, START), Idle))
    assertThat(fetch.callCount).isEqualTo(1)
  }

  @Test
  fun `the first value shows Fetching at once when this collector queued a check`() = runTest {
    fetch.hold()
    val empty = query(QueryStore.inMemory())
    assertThat(empty.state.first()).isEqualTo(QueryState(null, Fetching(forced = false)))

    val stale = query(QueryStore.inMemory(Fetched(0, START - 2.hours)))
    assertThat(stale.state.first())
      .isEqualTo(QueryState(Fetched(0, START - 2.hours), Fetching(forced = false)))

    val unfetched = query(QueryStore.inMemory(Fetched(0, fetchedAt = null)))
    assertThat(unfetched.state.first())
      .isEqualTo(QueryState(Fetched(0, fetchedAt = null), Fetching(forced = false)))
    fetch.release()
  }

  @Test
  fun `a fresh first value shows the query's status`() = runTest {
    val seed = Fetched(0, START)
    val query = query(QueryStore.inMemory(seed))
    fetch.respond = { throw offline }
    query.refresh().awaitFailed<IllegalStateException>()

    assertThat(query.state.first()).isEqualTo(QueryState(seed, Failed(offline)))
    assertThat(fetch.callCount).isEqualTo(1)
  }

  @Test
  fun `a check that holds off shows the query's status again`() = runTest {
    fetch.respond = { throw offline }
    val query = query(QueryStore.inMemory())
    query.state.first { it.status is Failed }
    assertThat(fetch.callCount).isEqualTo(1)

    // Inside the hold-off window, so this collector's check decides not to fetch
    query.state.test {
      assertThat(awaitItem()).isEqualTo(QueryState(null, Fetching(forced = false)))
      assertThat(awaitItem()).isEqualTo(QueryState(null, Failed(offline)))
      expectNoEvents()
    }
    assertThat(fetch.callCount).isEqualTo(1)
    data.fetchFailures.take()
  }

  @Test
  fun `a refresh shows the old value fetching then the new value idle`() = runTest {
    val query = query(QueryStore.inMemory(Fetched(0, START)))
    assertRefreshSequence(query)
  }

  @Test
  fun `a refresh never shows idle before the new value from a store that lags`() = runTest {
    val store = LaggingQueryStore<Int>(lag = 1.seconds)
    store.delegate.update { Fetched(0, START) }
    val query = query(store)
    assertRefreshSequence(query)
  }

  @Test
  fun `a refresh never shows the new value fetching from a store whose write returns late`() =
    runTest {
      val store = LaggingQueryStore<Int>(writeReturnsAfter = 1.seconds)
      store.delegate.update { Fetched(0, START) }
      val query = query(store)
      assertRefreshSequence(query)
    }

  @Test
  fun `a collector that subscribes during a fetch's write shows its value idle`() = runTest {
    val store = LaggingQueryStore<Int>(writeReturnsAfter = 10.seconds)
    store.delegate.update { Fetched(0, START) }
    val query = query(store)
    val early = mutableListOf<QueryState<Int>>()
    val late = mutableListOf<QueryState<Int>>()
    backgroundScope.launch { query.state.collect { early += it } }
    runCurrent()
    advanceTimeBy(1.minutes)

    val refresh = query.refresh()
    advanceTimeBy(1.seconds)
    // The fetch has written and its write call is still running
    val written = Fetched(1, START + 1.minutes)
    assertThat(store.delegate.data.value).isEqualTo(written)
    backgroundScope.launch { query.state.collect { late += it } }
    runCurrent()
    refresh.awaitSucceeded()
    advanceTimeBy(5.seconds)

    assertThat(early)
      .containsExactly(
        QueryState(Fetched(0, START), Idle),
        QueryState(Fetched(0, START), Fetching(forced = true)),
        QueryState(written, Idle),
      )
    assertThat(late).containsExactly(QueryState(written, Idle))
  }

  @Test
  fun `every collector of a store that lags shows the refresh in order`() = runTest {
    val store = LaggingQueryStore<Int>(lag = 1.seconds)
    store.delegate.update { Fetched(0, START) }
    val query = query(store)
    val seen = List(3) { mutableListOf<QueryState<Int>>() }
    for (list in seen) {
      backgroundScope.launch { query.state.collect { list += it } }
    }
    runCurrent()
    advanceTimeBy(1.minutes)

    fetch.hold()
    val refresh = query.refresh()
    runCurrent()
    fetch.release()
    refresh.awaitSucceeded()
    advanceTimeBy(5.seconds)

    val fetched = Fetched(1, START + 1.minutes)
    for (list in seen) {
      assertThat(list)
        .containsExactly(
          QueryState(Fetched(0, START), Idle),
          QueryState(Fetched(0, START), Fetching(forced = true)),
          QueryState(fetched, Idle),
        )
    }
  }

  /**
   * Refreshes [query] a minute after [START], over a stored 0 that is fresh, and checks that its
   * state shows exactly the old value idle, then fetching, then the new value idle.
   */
  private suspend fun TestScope.assertRefreshSequence(query: DataQuery<Int>) {
    query.state.test {
      assertThat(awaitItem()).isEqualTo(QueryState(Fetched(0, START), Idle))
      advanceTimeBy(1.minutes)
      fetch.hold()
      val refresh = query.refresh()
      assertThat(awaitItem()).isEqualTo(QueryState(Fetched(0, START), Fetching(forced = true)))
      fetch.release()
      assertThat(awaitItem()).isEqualTo(QueryState(Fetched(1, START + 1.minutes), Idle))
      refresh.awaitSucceeded()
      advanceTimeBy(5.seconds)
      expectNoEvents()
    }
  }

  @Test
  fun `a failed refresh keeps the value and shows the failure`() = runTest {
    val seed = Fetched(0, START)
    val query = query(QueryStore.inMemory(seed))
    query.state.test {
      assertThat(awaitItem()).isEqualTo(QueryState(seed, Idle))
      fetch.hold()
      fetch.respond = { throw offline }
      query.refresh()
      assertThat(awaitItem()).isEqualTo(QueryState(seed, Fetching(forced = true)))
      fetch.release()
      assertThat(awaitItem()).isEqualTo(QueryState(seed, Failed(offline)))
      expectNoEvents()
    }
    data.fetchFailures.take()
  }

  @Test
  fun `a check that decides not to fetch emits nothing`() = runTest {
    val query = query(QueryStore.inMemory(Fetched(0, START)))
    query.state.test {
      assertThat(awaitItem()).isEqualTo(QueryState(Fetched(0, START), Idle))
      query.fetchIfStale().awaitSucceeded()
      runCurrent()
      expectNoEvents()
    }
    assertThat(fetch.callCount).isEqualTo(0)
  }

  @Test
  fun `a forced request that takes over an unforced fetch shows both`() = runTest {
    val stale = Fetched(0, START - 2.hours)
    val query = query(QueryStore.inMemory(stale))
    fetch.hold()
    query.state.test {
      assertThat(awaitItem()).isEqualTo(QueryState(stale, Fetching(forced = false)))
      // The collector's check starts its unforced fetch
      runCurrent()
      assertThat(fetch.callCount).isEqualTo(1)
      query.refresh()
      assertThat(awaitItem()).isEqualTo(QueryState(stale, Fetching(forced = true)))
      fetch.release()
      assertThat(awaitItem()).isEqualTo(QueryState(Fetched(2, START), Idle))
      expectNoEvents()
    }
  }

  @Test
  fun `equal values that the store emits again show once`() = runTest {
    val emitted = MutableSharedFlow<Fetched<List<String>>?>(replay = 1)
    emitted.tryEmit(Fetched(listOf("a"), START))
    val store = sharedStore(emitted)
    val query =
      testDataScope(clock = testClock(START)).query(store, Freshness.staleAfter(1.hours)) {
        listOf("fetched")
      }

    query.state.test {
      assertThat(awaitItem()).isEqualTo(QueryState(Fetched(listOf("a"), START), Idle))
      // A new list with the same contents and stamp, like a database reading the row again
      emitted.emit(Fetched(listOf("a"), START))
      runCurrent()
      expectNoEvents()
    }
    query.data.test {
      assertThat(awaitItem()).isEqualTo(Fetched(listOf("a"), START))
      emitted.emit(Fetched(listOf("a"), START))
      runCurrent()
      expectNoEvents()
    }
    query.stored.test {
      assertThat(awaitItem()).isEqualTo(Fetched(listOf("a"), START))
      emitted.emit(Fetched(listOf("a"), START))
      runCurrent()
      expectNoEvents()
    }
  }

  @Test
  fun `a change over the same fetch time and a new flag both show`() = runTest {
    val emitted = MutableSharedFlow<Fetched<List<String>>?>(replay = 1)
    emitted.tryEmit(Fetched(listOf("a"), START))
    val query =
      testDataScope(clock = testClock(START)).query(
        sharedStore(emitted),
        Freshness.staleAfter(1.hours),
      ) {
        listOf("a")
      }

    query.data.test {
      assertThat(awaitItem()).isEqualTo(Fetched(listOf("a"), START))
      // Like an optimistic change over a truth that did not change
      emitted.emit(Fetched(listOf("a", "b"), START))
      assertThat(awaitItem()).isEqualTo(Fetched(listOf("a", "b"), START))
      emitted.emit(Fetched(listOf("a", "b"), START, invalidated = true))
      assertThat(awaitItem()).isEqualTo(Fetched(listOf("a", "b"), START, invalidated = true))
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test
  fun `collecting status starts no work and reads no store`() = runTest {
    val store = RecordingQueryStore<Int>()
    val query = query(store)

    assertThat(query.status.first()).isEqualTo(Idle)
    runCurrent()
    assertThat(store.dataCollections).isEqualTo(0)
    assertThat(fetch.callCount).isEqualTo(0)
  }

  @Test
  fun `a keyed state shows a key's value with its status`() = runTest {
    val keyedFetch = TestKeyedFetch<String, String> { key -> "$key!" }
    val values =
      testDataScope(clock = testClock(START))
        .keyed(
          store = QueryStore.inMemoryByKey<String, String>(),
          freshness = Freshness.staleAfter(1.hours),
          fetch = keyedFetch,
        )
    keyedFetch.hold()

    values.state("a").test {
      assertThat(awaitItem()).isEqualTo(QueryState(null, Fetching(forced = false)))
      keyedFetch.release()
      assertThat(awaitItem()).isEqualTo(QueryState(Fetched("a!", START), Idle))
    }
    assertThat(values.data("a").first()).isEqualTo(Fetched("a!", START))
  }

  @Test
  fun `map changes the value and keeps the stamp and status`() {
    val state = QueryState(Fetched(2, START, invalidated = true), Fetching(forced = true))

    assertThat(state.map { "$it" })
      .isEqualTo(QueryState(Fetched("2", START, invalidated = true), Fetching(forced = true)))
    assertThat(state.value).isEqualTo(2)
    assertThat(state.fetchedAt).isEqualTo(START)
    assertThat(QueryState<Int>(null, Idle).map { it + 1 }).isEqualTo(QueryState(null, Idle))
  }

  /** A store over [emitted], which a test can make emit an equal value again. */
  private fun <T> sharedStore(emitted: MutableSharedFlow<Fetched<T>?>): QueryStore<T> =
    QueryStore.of(
      emitted,
      clear = { keepsStored ->
        val current = emitted.replayCache.lastOrNull()
        if (current == null || !keepsStored(current.fetchedAt, current.invalidated)) {
          emitted.emit(null)
        }
      },
      write = { fetched, keepsStored ->
        val current = emitted.replayCache.lastOrNull()
        if (current == null || !keepsStored(current.fetchedAt, current.invalidated)) {
          emitted.emit(fetched)
        }
      },
    )

  private companion object {
    val START: Instant = Instant.parse("2026-06-10T12:00:00Z")
  }
}
