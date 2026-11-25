// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEmpty
import dev.zacsweers.fieldspottr.data.core.FetchStatus.Idle
import dev.zacsweers.fieldspottr.data.core.testing.RowListFailureOutboxStores
import dev.zacsweers.fieldspottr.data.core.testing.TestFailures
import dev.zacsweers.fieldspottr.data.core.testing.TestFetch
import dev.zacsweers.fieldspottr.data.core.testing.assertRunning
import dev.zacsweers.fieldspottr.data.core.testing.assertSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.awaitSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.testClock
import dev.zacsweers.fieldspottr.data.core.testing.testDataScope
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * A query over a store whose data has emitted no value yet, like an optimistic store whose outbox
 * cannot list its rows. An unforced check waits for that value. A forced request does not.
 */
class RowListFailureTest {
  private val stores = RowListFailureOutboxStores()
  private val failures = TestFailures()
  private val base = QueryStore.inMemory<String>()
  // Returns "v" and the number of the call, so each fetch stores a value of its own
  private val fetch: TestFetch<String> = TestFetch { "v${fetch.callCount}" }

  /** A query over an optimistic store whose writable base is [base], over [stores]. */
  private fun TestScope.query(): DataQuery<String> {
    val data =
      DataScope.Factory(
          onFailure = failures,
          online = MutableStateFlow(true),
          stores = stores,
          clock = testClock(START),
        )
        .create(backgroundScope, setOf(Notes.sender { _, _ -> }))
    val notes = data.optimisticStore(Notes, base = base, apply = { _, text -> text })
    return data.query(notes, Freshness.staleAfter(1.hours), fetch = fetch)
  }

  @Test
  fun `a forced request fetches while a check waits for a row list that never comes`() = runTest {
    stores.failRowLists = true
    val query = query()
    val seen = mutableListOf<QueryState<String>>()
    backgroundScope.launch { query.stateAutoFetch.collect { seen += it } }
    runCurrent()

    // A forced fetch writes the base and never reads the store
    query.refresh().awaitSucceeded()
    assertThat(base.data.value).isEqualTo(Fetched("v1", START))
    assertThat(seen).isEmpty()

    val check = query.fetchIfStale()
    runCurrent()
    check.assertRunning()
    val forced = query.refresh()
    runCurrent()

    assertThat(fetch.callCount).isEqualTo(2)
    forced.assertSucceeded()
    // The check's handle follows the forced fetch that took it over
    check.assertSucceeded()
    assertThat(base.data.value).isEqualTo(Fetched("v2", START))
    // The row list still fails, so the collector has nothing to show
    advanceTimeBy(1.minutes)
    assertThat(seen).isEmpty()
    assertThat(failures.take()).isNotEmpty()
  }

  @Test
  fun `a collector shows the value once the row list reads again`() = runTest {
    stores.failRowLists = true
    val query = query()
    val seen = mutableListOf<QueryState<String>>()
    backgroundScope.launch { query.stateAutoFetch.collect { seen += it } }
    runCurrent()
    query.refresh().awaitSucceeded()
    advanceTimeBy(5.seconds)
    assertThat(seen).isEmpty()

    stores.failRowLists = false
    advanceTimeBy(1.minutes)

    assertThat(seen).containsExactly(QueryState(Fetched("v1", START), Idle))
    // The fresh value needs no fetch of its own
    assertThat(fetch.callCount).isEqualTo(1)
    assertThat(failures.take()).isNotEmpty()
  }

  @Test
  fun `a row list that fails after a good list keeps the last value in view`() = runTest {
    base.update { Fetched("v0", START) }
    val query = query()
    val seen = mutableListOf<QueryState<String>>()
    backgroundScope.launch { query.stateAutoFetch.collect { seen += it } }
    runCurrent()
    assertThat(seen).containsExactly(QueryState(Fetched("v0", START), Idle))

    stores.failRowLists = true
    advanceTimeBy(5.minutes)

    assertThat(seen).containsExactly(QueryState(Fetched("v0", START), Idle))
    assertThat(failures.take()).isNotEmpty()
    assertThat(fetch.callCount).isEqualTo(0)
  }

  @Test
  fun `a newer base value reaches the same collector while the row list fails`() = runTest {
    base.update { Fetched("v0", START) }
    val query = query()
    val seen = mutableListOf<QueryState<String>>()
    backgroundScope.launch { query.stateAutoFetch.collect { seen += it } }
    runCurrent()
    stores.failRowLists = true
    advanceTimeBy(5.seconds)
    assertThat(failures.take()).isNotEmpty()

    base.update { Fetched("v1", START + 5.seconds) }
    advanceTimeBy(1.seconds)

    assertThat(seen.last()).isEqualTo(QueryState(Fetched("v1", START + 5.seconds), Idle))
    assertThat(fetch.callCount).isEqualTo(0)
  }

  @Test
  fun `a forced request fetches while a check waits for a store that never emits`() = runTest {
    val stored = MutableStateFlow<Fetched<Int>?>(null)
    val silent =
      QueryStore.of(
        data = flow<Fetched<Int>?> { awaitCancellation() },
        write = { fetched, _ -> stored.value = fetched },
        clear = { stored.value = null },
      )
    val data = testDataScope(clock = testClock(START))
    val counter = TestFetch { 1 }
    val query = data.query(silent, Freshness.staleAfter(1.hours), fetch = counter)

    val check = query.fetchIfStale()
    runCurrent()
    check.assertRunning()
    val forced = query.refresh()
    runCurrent()

    forced.assertSucceeded()
    check.assertSucceeded()
    assertThat(counter.callCount).isEqualTo(1)
    assertThat(stored.value).isEqualTo(Fetched(1, START))
    data.assertNoFailures()
  }

  private companion object {
    val START: Instant = Instant.parse("2026-06-10T12:00:00Z")
    val Notes = OutboxKey<String>("notes")
  }
}
