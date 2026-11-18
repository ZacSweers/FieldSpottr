// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import dev.zacsweers.fieldspottr.data.core.FetchStatus.Fetching
import dev.zacsweers.fieldspottr.data.core.FetchStatus.Idle
import dev.zacsweers.fieldspottr.data.core.testing.HeldStoreReadQueryStore
import dev.zacsweers.fieldspottr.data.core.testing.HeldStoreReadQueryStore.HeldRead
import dev.zacsweers.fieldspottr.data.core.testing.TestFetch
import dev.zacsweers.fieldspottr.data.core.testing.TestKeyedFetch
import dev.zacsweers.fieldspottr.data.core.testing.awaitSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.testClock
import dev.zacsweers.fieldspottr.data.core.testing.testDataScope
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** [DataQuery.state] and [DataQuery.storedState] over a store whose reads the test holds. */
class HeldStoreReadTest {
  // Returns how many calls have started, so each fetch returns a value of its own
  private val fetch: TestFetch<Int> = TestFetch { fetch.callCount }

  private fun TestScope.query(store: QueryStore<Int>): DataQuery<Int> =
    testDataScope(clock = testClock(START))
      .query(store, Freshness.staleAfter(1.hours), fetch = fetch)

  @Test
  fun `storedState's first value shows a refresh that settled while the first read waited`() =
    runTest {
      assertFirstValueShowsRefresh { it.storedState }
    }

  @Test
  fun `state's first value shows a refresh that settled while the first read waited`() = runTest {
    assertFirstValueShowsRefresh { it.state }
  }

  /**
   * Holds the first read of [states] after it read the stored 0, completes a refresh that stores 1
   * meanwhile, then releases the read. The first value must be the refresh's 1 with `Idle`.
   */
  private suspend fun TestScope.assertFirstValueShowsRefresh(
    states: (DataQuery<Int>) -> Flow<QueryState<Int>>
  ) {
    val store = HeldStoreReadQueryStore(QueryStore.inMemory(Fetched(0, START)))
    val query = query(store)
    advanceTimeBy(1.minutes)
    store.holdNextRead(HeldRead.AfterFirstValue)
    val first = backgroundScope.async { states(query).first() }
    runCurrent()

    query.refresh().awaitSucceeded()
    store.release()

    assertThat(first.await()).isEqualTo(QueryState(Fetched(1, START + 1.minutes), Idle))
    assertThat(fetch.callCount).isEqualTo(1)
  }

  @Test
  fun `a collected key stays stored and collected while its new read waits`() = runTest {
    val stores = QueryStore.inMemoryByKey<String, String>(maxEntries = 1)
    stores("a").update { Fetched("a0", START) }
    val held = mutableMapOf<String, HeldStoreReadQueryStore<String>>()
    val keyedFetch = TestKeyedFetch<String, String> { key -> "$key!" }
    val values =
      testDataScope(clock = testClock(START))
        .keyed(
          store = { key -> held.getOrPut(key) { HeldStoreReadQueryStore(stores(key)) } },
          freshness = Freshness.staleAfter(1.hours),
          fetch = keyedFetch,
        )
    val seen = mutableListOf<QueryState<String>>()
    backgroundScope.launch { values.storedState("a").collect { seen += it } }
    runCurrent()
    advanceTimeBy(1.minutes)

    // Nothing reads the store back for a collector of storedState, so after the refresh it starts a
    // new read, which waits here before it collects the key
    val a = checkNotNull(held["a"])
    a.holdNextRead(HeldRead.BeforeSubscribe)
    keyedFetch.hold()
    val refresh = values.refresh("a")
    runCurrent()
    keyedFetch.release()
    refresh.awaitSucceeded()
    runCurrent()
    val written = Fetched("a!", START + 1.minutes)
    assertThat(a.reads).isEqualTo(2)
    assertThat(stores.isCollected("a")).isTrue()

    // A second key over the cap drops only keys nobody collects
    values.refresh("b").awaitSucceeded()
    assertThat(stores("a").data.value).isEqualTo(written)
    assertThat(stores.isCollected("a")).isTrue()
    assertThat(seen)
      .containsExactly(
        QueryState(Fetched("a0", START), Idle),
        QueryState(Fetched("a0", START), Fetching(forced = true)),
      )

    a.release()
    runCurrent()
    assertThat(stores("a").data.value).isEqualTo(written)
    assertThat(stores.isCollected("a")).isTrue()
    assertThat(seen)
      .containsExactly(
        QueryState(Fetched("a0", START), Idle),
        QueryState(Fetched("a0", START), Fetching(forced = true)),
        QueryState(written, Idle),
      )
  }

  private companion object {
    val START: Instant = Instant.parse("2026-06-10T12:00:00Z")
  }
}
