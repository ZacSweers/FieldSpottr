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
import dev.zacsweers.fieldspottr.data.core.impl.keysHeld
import dev.zacsweers.fieldspottr.data.core.testing.HeldStoreReadQueryStore
import dev.zacsweers.fieldspottr.data.core.testing.HeldStoreReadQueryStore.HeldRead
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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * [DataQuery.storedState] and [KeyedDataQueries.storedState]: the values and statuses of `state`
 * for a collector that starts no work.
 */
class QueryStoredStateTest {
  private val offline = IllegalStateException("offline")
  // Returns how many calls have started, so each fetch returns a value of its own
  private val fetch: TestFetch<Int> = TestFetch { fetch.callCount }
  private lateinit var data: TestDataScope

  private fun TestScope.query(store: QueryStore<Int>): DataQuery<Int> {
    data = testDataScope(clock = testClock(START))
    return data.query(store, Freshness.staleAfter(1.hours), fetch = fetch)
  }

  @Test
  fun `collecting storedState starts no fetch for stale or unfetched or missing data`() = runTest {
    val stale = Fetched(0, START - 2.hours)
    val staleQuery = query(QueryStore.inMemory(stale))
    assertThat(staleQuery.storedState.first()).isEqualTo(QueryState(stale, Idle))

    val unfetched = Fetched(0, fetchedAt = null)
    val unfetchedQuery = query(QueryStore.inMemory(unfetched))
    assertThat(unfetchedQuery.storedState.first()).isEqualTo(QueryState(unfetched, Idle))

    val emptyQuery = query(QueryStore.inMemory())
    emptyQuery.storedState.test {
      assertThat(awaitItem()).isEqualTo(QueryState(null, Idle))
      advanceTimeBy(1.hours)
      expectNoEvents()
    }
    assertThat(fetch.callCount).isEqualTo(0)
  }

  @Test
  fun `storedState shows a refresh as the old value fetching then the new value idle`() = runTest {
    val query = query(QueryStore.inMemory(Fetched(0, START)))
    assertRefreshSequence(query)
  }

  @Test
  fun `storedState never shows idle before the new value from a store that lags`() = runTest {
    val store = LaggingQueryStore<Int>(lag = 1.seconds)
    store.delegate.update { Fetched(0, START) }
    val query = query(store)
    assertRefreshSequence(query)
  }

  @Test
  fun `storedState never shows the new value fetching from a store whose write returns late`() =
    runTest {
      val store = LaggingQueryStore<Int>(writeReturnsAfter = 1.seconds)
      store.delegate.update { Fetched(0, START) }
      val query = query(store)
      assertRefreshSequence(query)
    }

  @Test
  fun `every collector of storedState over a store that lags shows the refresh in order`() =
    runTest {
      val store = LaggingQueryStore<Int>(lag = 1.seconds)
      store.delegate.update { Fetched(0, START) }
      val query = query(store)
      val seen = List(3) { mutableListOf<QueryState<Int>>() }
      for (list in seen) {
        backgroundScope.launch { query.storedState.collect { list += it } }
      }
      runCurrent()
      advanceTimeBy(1.minutes)

      fetch.hold()
      val refresh = query.refresh()
      runCurrent()
      fetch.release()
      refresh.awaitSucceeded()
      advanceTimeBy(5.seconds)

      for (list in seen) {
        assertThat(list)
          .containsExactly(
            QueryState(Fetched(0, START), Idle),
            QueryState(Fetched(0, START), Fetching(forced = true)),
            QueryState(Fetched(1, START + 1.minutes), Idle),
          )
      }
    }

  @Test
  fun `storedState over a store that lags shows nothing new while its new read waits`() = runTest {
    val lagging = LaggingQueryStore<Int>(lag = 1.seconds)
    lagging.delegate.update { Fetched(0, START) }
    val store = HeldStoreReadQueryStore(lagging)
    val query = query(store)
    val seen = mutableListOf<QueryState<Int>>()
    backgroundScope.launch { query.storedState.collect { seen += it } }
    runCurrent()
    advanceTimeBy(1.minutes)

    // The read that starts after the refresh waits, while the old read still delivers the write
    store.holdNextRead(HeldRead.BeforeSubscribe)
    fetch.hold()
    val refresh = query.refresh()
    runCurrent()
    fetch.release()
    refresh.awaitSucceeded()
    advanceTimeBy(5.seconds)

    assertThat(store.reads).isEqualTo(2)
    assertThat(seen)
      .containsExactly(
        QueryState(Fetched(0, START), Idle),
        QueryState(Fetched(0, START), Fetching(forced = true)),
      )

    store.release()
    advanceTimeBy(5.seconds)
    assertThat(seen)
      .containsExactly(
        QueryState(Fetched(0, START), Idle),
        QueryState(Fetched(0, START), Fetching(forced = true)),
        QueryState(Fetched(1, START + 1.minutes), Idle),
      )
  }

  @Test
  fun `storedState shows a failed refresh with the value it keeps`() = runTest {
    val seed = Fetched(0, START)
    val query = query(QueryStore.inMemory(seed))
    fetch.respond = { throw offline }

    query.storedState.test {
      assertThat(awaitItem()).isEqualTo(QueryState(seed, Idle))
      fetch.hold()
      val refresh = query.refresh()
      assertThat(awaitItem()).isEqualTo(QueryState(seed, Fetching(forced = true)))
      fetch.release()
      assertThat(awaitItem()).isEqualTo(QueryState(seed, Failed(offline)))
      refresh.awaitFailed<IllegalStateException>()
      expectNoEvents()
    }
    // A later collector starts from the status as it is
    assertThat(query.storedState.first()).isEqualTo(QueryState(seed, Failed(offline)))
    assertThat(fetch.callCount).isEqualTo(1)
    data.fetchFailures.take()
  }

  @Test
  fun `the first storedState value shows a fetch that already runs`() = runTest {
    val seed = Fetched(0, START)
    val query = query(QueryStore.inMemory(seed))
    fetch.hold()
    val refresh = query.refresh()
    runCurrent()

    query.storedState.test {
      assertThat(awaitItem()).isEqualTo(QueryState(seed, Fetching(forced = true)))
      fetch.release()
      assertThat(awaitItem()).isEqualTo(QueryState(Fetched(1, START), Idle))
    }
    refresh.awaitSucceeded()
  }

  @Test
  fun `storedState shows the fetch that a collector of state starts`() = runTest {
    val query = query(QueryStore.inMemory())
    fetch.hold()

    query.storedState.test {
      assertThat(awaitItem()).isEqualTo(QueryState(null, Idle))
      val screen = backgroundScope.launch { query.state.collect {} }
      assertThat(awaitItem()).isEqualTo(QueryState(null, Fetching(forced = false)))
      fetch.release()
      assertThat(awaitItem()).isEqualTo(QueryState(Fetched(1, START), Idle))
      screen.cancelAndJoin()
      expectNoEvents()
    }
    assertThat(fetch.callCount).isEqualTo(1)
  }

  @Test
  fun `a collector of storedState fetches nothing when the value goes away`() = runTest {
    val store = QueryStore.inMemory(Fetched(0, START))
    val query = query(store)

    query.storedState.test {
      assertThat(awaitItem()).isEqualTo(QueryState(Fetched(0, START), Idle))
      // A collector of state would fetch again here
      store.update { null }
      assertThat(awaitItem()).isEqualTo(QueryState(null, Idle))
      store.update { Fetched(5, fetchedAt = null) }
      assertThat(awaitItem()).isEqualTo(QueryState(Fetched(5, fetchedAt = null), Idle))
      store.invalidate()
      assertThat(awaitItem())
        .isEqualTo(QueryState(Fetched(5, fetchedAt = null, invalidated = true), Idle))
      advanceTimeBy(1.hours)
      expectNoEvents()
    }
    assertThat(fetch.callCount).isEqualTo(0)
  }

  @Test
  fun `a collector of storedState starts no watcher and fetches nothing on a reconnect`() =
    runTest {
      val query = query(QueryStore.inMemory(Fetched(0, START - 2.hours)))
      fetch.respond = { throw offline }
      query.fetchIfStale().awaitFailed<IllegalStateException>()
      data.fetchFailures.take()
      fetch.respond = { fetch.callCount }

      val collector = backgroundScope.launch { query.storedState.collect {} }
      runCurrent()
      // Nothing collects the network state for this collector
      assertThat(data.online.subscriptionCount.value).isEqualTo(0)
      data.online.value = false
      runCurrent()
      data.online.value = true
      runCurrent()
      advanceTimeBy(1.hours)

      // A collector of state would fetch again after the reconnect, since the last fetch failed
      assertThat(fetch.callCount).isEqualTo(1)
      assertThat(query.status.value).isEqualTo(Failed(offline))
      collector.cancelAndJoin()
    }

  @Test
  fun `storedState collectors read the store themselves and the query reads nothing for them`() =
    runTest {
      val store = RecordingQueryStore(QueryStore.inMemory(Fetched(0, START)))
      val query = query(store)
      val collectors = List(3) { backgroundScope.launch { query.storedState.collect {} } }
      runCurrent()
      assertThat(store.dataCollections).isEqualTo(3)

      query.refresh().awaitSucceeded()
      runCurrent()

      // Each collector read the store once more after the fetch wrote. The fetch made no read to
      // share, which it makes only for collectors of state, so no copy of the value was kept.
      assertThat(store.dataCollections).isEqualTo(6)
      collectors.forEach { it.cancelAndJoin() }

      // Nothing was counted, so nothing is left to stop: a later fetch reads nothing
      query.refresh().awaitSucceeded()
      assertThat(store.dataCollections).isEqualTo(6)
    }

  @Test
  fun `a collector of storedState beside a collector of state shows the same order`() = runTest {
    val store = RecordingQueryStore(QueryStore.inMemory(Fetched(0, START)))
    val query = query(store)
    val active = mutableListOf<QueryState<Int>>()
    val passive = mutableListOf<QueryState<Int>>()
    backgroundScope.launch { query.state.collect { active += it } }
    backgroundScope.launch { query.storedState.collect { passive += it } }
    runCurrent()
    advanceTimeBy(1.minutes)

    fetch.hold()
    val refresh = query.refresh()
    runCurrent()
    fetch.release()
    refresh.awaitSucceeded()
    runCurrent()

    // Both line up with the one record the fetch left, so both show the same order
    assertThat(passive)
      .containsExactly(
        QueryState(Fetched(0, START), Idle),
        QueryState(Fetched(0, START), Fetching(forced = true)),
        QueryState(Fetched(1, START + 1.minutes), Idle),
      )
    assertThat(active).isEqualTo(passive)
  }

  @Test
  fun `a collector of storedState that leaves keeps the watcher of a collector of state`() =
    runTest {
      val store = QueryStore.inMemory(Fetched(0, START))
      val query = query(store)
      val screen = backgroundScope.launch { query.state.collect {} }
      runCurrent()
      val passive = backgroundScope.launch { query.storedState.collect {} }
      runCurrent()
      passive.cancelAndJoin()

      // The screen's watcher still runs, so a value that goes away is fetched again
      store.update { null }
      runCurrent()
      assertThat(fetch.callCount).isEqualTo(1)
      assertThat(store.data.value).isEqualTo(Fetched(1, START))
      screen.cancelAndJoin()
    }

  @Test
  fun `a key's storedState shows its value with its status and starts no fetch`() = runTest {
    val keyedFetch = TestKeyedFetch<String, String> { key -> "$key!" }
    val stores = QueryStore.inMemoryByKey<String, String>()
    val values =
      testDataScope(clock = testClock(START))
        .keyed(store = stores, freshness = Freshness.staleAfter(1.hours), fetch = keyedFetch)

    values.storedState("a").test {
      assertThat(awaitItem()).isEqualTo(QueryState(null, Idle))
      runCurrent()
      assertThat(keyedFetch.callCount).isEqualTo(0)

      keyedFetch.hold()
      val refresh = values.refresh("a")
      assertThat(awaitItem()).isEqualTo(QueryState(null, Fetching(forced = true)))
      keyedFetch.release()
      assertThat(awaitItem()).isEqualTo(QueryState(Fetched("a!", START), Idle))
      refresh.awaitSucceeded()
      expectNoEvents()
    }
  }

  @Test
  fun `each key's storedState follows only its own key`() = runTest {
    val keyedFetch = TestKeyedFetch<String, String> { key -> "$key!" }
    val values =
      testDataScope(clock = testClock(START))
        .keyed(
          store = QueryStore.inMemoryByKey<String, String>(),
          freshness = Freshness.staleAfter(1.hours),
          fetch = keyedFetch,
        )
    val a = mutableListOf<QueryState<String>>()
    val b = mutableListOf<QueryState<String>>()
    backgroundScope.launch { values.storedState("a").collect { a += it } }
    backgroundScope.launch { values.storedState("b").collect { b += it } }
    runCurrent()

    keyedFetch.hold()
    val refresh = values.refresh("a")
    runCurrent()
    keyedFetch.release()
    refresh.awaitSucceeded()
    runCurrent()

    val nothing = QueryState<String>(null, Idle)
    assertThat(a)
      .containsExactly(
        nothing,
        QueryState<String>(null, Fetching(forced = true)),
        QueryState(Fetched("a!", START), Idle),
      )
    assertThat(b).containsExactly(nothing)
    assertThat(keyedFetch.calls).containsExactly("a")
  }

  @Test
  fun `collecting a key's storedState keeps its query until the collector leaves`() = runTest {
    val values =
      testDataScope(clock = testClock(START))
        .keyed(
          store = QueryStore.inMemoryByKey<String, String>(),
          freshness = Freshness.staleAfter(1.hours),
          fetch = TestKeyedFetch { key -> "$key!" },
        )

    val collector = backgroundScope.launch { values.storedState("a").collect {} }
    runCurrent()
    assertThat(values.keysHeld).isEqualTo(1)

    collector.cancelAndJoin()
    assertThat(values.keysHeld).isEqualTo(0)
  }

  /**
   * Refreshes [query] a minute after [START], over a stored 0 that is fresh, and checks that its
   * stored state shows exactly the old value idle, then fetching, then the new value idle.
   */
  private suspend fun TestScope.assertRefreshSequence(query: DataQuery<Int>) {
    query.storedState.test {
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

  private companion object {
    val START: Instant = Instant.parse("2026-06-10T12:00:00Z")
  }
}
