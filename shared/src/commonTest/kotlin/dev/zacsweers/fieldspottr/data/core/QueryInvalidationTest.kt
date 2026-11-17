// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotSameInstanceAs
import assertk.assertions.isSameInstanceAs
import dev.zacsweers.fieldspottr.data.core.FetchStatus.Fetching
import dev.zacsweers.fieldspottr.data.core.FetchStatus.Idle
import dev.zacsweers.fieldspottr.data.core.testing.RecordingQueryStore
import dev.zacsweers.fieldspottr.data.core.testing.RecordingQueryStore.Call.Invalidate
import dev.zacsweers.fieldspottr.data.core.testing.RecordingQueryStore.Call.Write
import dev.zacsweers.fieldspottr.data.core.testing.TestDataScope
import dev.zacsweers.fieldspottr.data.core.testing.TestFetch
import dev.zacsweers.fieldspottr.data.core.testing.TestKeyedFetch
import dev.zacsweers.fieldspottr.data.core.testing.assertCancelled
import dev.zacsweers.fieldspottr.data.core.testing.assertFailed
import dev.zacsweers.fieldspottr.data.core.testing.assertRunning
import dev.zacsweers.fieldspottr.data.core.testing.assertSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.awaitSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.testClock
import dev.zacsweers.fieldspottr.data.core.testing.testDataScope
import dev.zacsweers.fieldspottr.data.core.testing.writeFetched
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * What [DataQuery.invalidate] promises from the moment it returns: a fetch from before the call
 * never stores a fresh value, no request joins such a fetch, and what is stored stays. Also the
 * limits a store sets, and [invalidateAndRefresh].
 */
class QueryInvalidationTest {
  // What the server holds. A fetch reads it when it responds.
  private var server = "old"
  private val fetch = TestFetch { server }
  private lateinit var data: TestDataScope

  private fun TestScope.query(store: QueryStore<String>): DataQuery<String> {
    data = testDataScope(clock = testClock(START))
    return data.query(store, Freshness.staleAfter(1.hours), fetch = fetch)
  }

  @Test
  fun `a fetch from before an invalidation stores its value already marked stale`() = runTest {
    val store = RecordingQueryStore(QueryStore.inMemory(Fetched("cached", START)))
    val query = query(store)
    fetch.hold()
    val refresh = query.refresh()
    runCurrent()

    val invalidation = query.invalidate()
    server = "new"
    // The fetch read the server before the change, whatever it holds once the fetch returns
    fetch.respond = { "old" }
    fetch.release()
    runCurrent()

    refresh.assertSucceeded()
    invalidation.assertSucceeded()
    // One write, marked. The store never held the old value as fresh, so no reader could see it so
    assertThat(store.writes).containsExactly(Fetched("old", START, invalidated = true))
    assertThat(store.calls).containsExactly(Write("old", START, invalidated = true), Invalidate)
    assertThat(query.status.value).isEqualTo(Idle)
    data.assertNoFailures()
  }

  @Test
  fun `a fetch that ignores cancellation stores no fresh value after an invalidation`() = runTest {
    val store = RecordingQueryStore(QueryStore.inMemory(Fetched("cached", START)))
    val query = query(store)
    val respond = CompletableDeferred<Unit>()
    fetch.respond = {
      withContext(NonCancellable) { respond.await() }
      "old"
    }
    val refresh = query.refresh()
    runCurrent()

    query.invalidate()
    respond.complete(Unit)
    runCurrent()

    refresh.assertSucceeded()
    assertThat(store.writes).containsExactly(Fetched("old", START, invalidated = true))
  }

  @Test
  fun `a fetch that ignores cancellation never writes once invalidateAndRefresh replaced it`() =
    runTest {
      val store = RecordingQueryStore(QueryStore.inMemory(Fetched("cached", START)))
      val query = query(store)
      val respond = CompletableDeferred<Unit>()
      fetch.respond = {
        if (fetch.callCount == 1) {
          withContext(NonCancellable) { respond.await() }
          "old"
        } else {
          "new"
        }
      }
      val before = query.refresh()
      runCurrent()

      val after = query.invalidateAndRefresh()
      runCurrent()
      // The old fetch cannot be stopped, so its command still unwinds and the new one waits
      before.assertRunning()
      assertThat(after).isNotSameInstanceAs(before)
      respond.complete(Unit)
      runCurrent()

      before.assertCancelled()
      after.assertSucceeded()
      // The mark came first, over the cached value, and only the new fetch wrote
      assertThat(store.calls).containsExactly(Invalidate, Write("new", START))
      assertThat(store.data.first()).isEqualTo(Fetched("new", START))
      data.assertNoFailures()
    }

  @Test
  fun `an invalidation made while a fetch's write runs marks that value next`() = runTest {
    val store = GatedWriteStore(QueryStore.inMemory(Fetched("cached", START)))
    val query = query(store)
    val refresh = query.refresh()
    runCurrent()
    // The fetch has handed its value to the store, and the store has not stored it yet
    assertThat(store.pending).isEqualTo(Fetched("old", START))

    val invalidation = query.invalidate()
    // Made after the call, so it must not take the unmarked value for fresh
    server = "new"
    val request = query.fetchIfStale()
    store.release()
    runCurrent()

    refresh.assertSucceeded()
    invalidation.assertSucceeded()
    request.assertSucceeded()
    // The limit for a write already with the store: it lands unmarked, and the mark is the next
    // command. The request waited for the mark, found the value stale and fetched again.
    assertThat(store.history)
      .containsExactly(
        Fetched("old", START),
        Fetched("old", START, invalidated = true),
        Fetched("new", START),
      )
    assertThat(fetch.callCount).isEqualTo(2)
  }

  @Test
  fun `no request joins a fetch from before an invalidation and forced ones after it share one`() =
    runTest {
      val store = QueryStore.inMemory(Fetched("cached", START))
      val query = query(store)
      fetch.hold()
      val before = query.refresh()
      runCurrent()

      query.invalidate()
      server = "new"
      val first = query.refresh()
      val second = query.refresh()
      fetch.release()
      runCurrent()

      assertThat(first).isNotSameInstanceAs(before)
      assertThat(second).isSameInstanceAs(first)
      before.assertCancelled()
      first.assertSucceeded()
      assertThat(fetch.callCount).isEqualTo(2)
      assertThat(store.data.value).isEqualTo(Fetched("new", START))
    }

  @Test
  fun `an unforced request after an invalidation waits for the mark and fetches`() = runTest {
    val store = QueryStore.inMemory(Fetched("cached", START))
    val query = query(store)
    fetch.hold()
    val before = query.refresh()
    runCurrent()

    query.invalidate()
    val after = query.fetchIfStale()
    assertThat(after).isNotSameInstanceAs(before)
    fetch.respond = { "old" }
    fetch.release()
    runCurrent()
    // The fetch from before the call wrote its value marked stale, so the request fetched again
    fetch.respond = { "new" }
    runCurrent()

    before.assertSucceeded()
    after.assertSucceeded()
    assertThat(fetch.callCount).isEqualTo(2)
    assertThat(store.data.value?.invalidated).isEqualTo(false)
  }

  @Test
  fun `an outdated fetch keeps a newer value that is already marked stale`() = runTest {
    val store = QueryStore.inMemory(Fetched("cached", START))
    val query = query(store)
    fetch.hold()
    val refresh = query.refresh()
    runCurrent()
    advanceTimeBy(1.minutes)

    // Another writer stored the server's answer to a change, and something marked it stale
    val written = Fetched("written", START + 1.minutes, invalidated = true)
    store.update { written }
    query.invalidate()
    fetch.release()
    runCurrent()

    refresh.assertSucceeded()
    // The fetch is older than that value and stale itself, so it never replaces it
    assertThat(store.data.value).isEqualTo(written)
  }

  @Test
  fun `an outdated fetch keeps a newer value that a writer stored before invalidating`() = runTest {
    val store = QueryStore.inMemory(Fetched("cached", START))
    val query = query(store)
    val seen = mutableListOf<Fetched<String>?>()
    backgroundScope.launch { store.data.collect { seen += it } }
    fetch.hold()
    val refresh = query.refresh()
    runCurrent()
    advanceTimeBy(1.minutes)

    // Another writer stores the server's answer to a change with the current time, unmarked,
    // then invalidates the query
    store.update { Fetched("written", START + 1.minutes) }
    runCurrent()
    query.invalidate()
    fetch.release()
    runCurrent()

    refresh.assertSucceeded()
    // The fetch began before that value was stored, so it never replaces it, and the value
    // stays visible marked stale
    val marked = Fetched("written", START + 1.minutes, invalidated = true)
    assertThat(store.data.value).isEqualTo(marked)
    assertThat(seen.dropWhile { it?.value != "written" }.map { it?.value }.distinct())
      .containsExactly("written")
    data.assertNoFailures()
  }

  @Test
  fun `an outdated fetch still replaces a value stored at the time it began`() = runTest {
    val store = QueryStore.inMemory(Fetched("cached", START))
    val query = query(store)
    fetch.hold()
    val refresh = query.refresh()
    runCurrent()

    // A limit of the clock: the writer stores its answer with the same time the fetch took when
    // it began. An equal time counts as the fetch's own, so the fetch's value wins, marked stale.
    store.update { Fetched("written", START) }
    query.invalidate()
    fetch.release()
    runCurrent()

    refresh.assertSucceeded()
    assertThat(store.data.value).isEqualTo(Fetched("old", START, invalidated = true))
    data.assertNoFailures()
  }

  @Test
  fun `a fetch on a second query replaces a marked value when only the first query invalidates`() =
    runTest {
      val store = QueryStore.inMemory(Fetched("cached", START))
      val first = query(store)
      val secondFetch = TestFetch { "second" }
      val second = data.query(store, Freshness.staleAfter(1.hours), fetch = secondFetch)
      secondFetch.hold()
      val refresh = second.refresh()
      runCurrent()
      advanceTimeBy(1.minutes)

      // A limit of sharing a store: the writer invalidates only the first query. The second
      // query's fetch is not outdated, so once the mark lands it replaces the marked value with
      // its own, unmarked.
      store.update { Fetched("written", START + 1.minutes) }
      first.invalidate()
      runCurrent()
      assertThat(store.data.value)
        .isEqualTo(Fetched("written", START + 1.minutes, invalidated = true))
      secondFetch.release()
      runCurrent()

      refresh.assertSucceeded()
      assertThat(store.data.value).isEqualTo(Fetched("second", START))
      data.assertNoFailures()
    }

  @Test
  fun `a fetch on a second query keeps a newer value when every query invalidates`() = runTest {
    val store = QueryStore.inMemory(Fetched("cached", START))
    val first = query(store)
    val secondFetch = TestFetch { "second" }
    val second = data.query(store, Freshness.staleAfter(1.hours), fetch = secondFetch)
    val seen = mutableListOf<Fetched<String>?>()
    backgroundScope.launch { store.data.collect { seen += it } }
    secondFetch.hold()
    val refresh = second.refresh()
    runCurrent()
    advanceTimeBy(1.minutes)

    // The writer invalidates every query that reads the store, so the second query's fetch is
    // outdated and keeps the newer value
    store.update { Fetched("written", START + 1.minutes) }
    runCurrent()
    first.invalidate()
    second.invalidate()
    secondFetch.release()
    runCurrent()

    refresh.assertSucceeded()
    val marked = Fetched("written", START + 1.minutes, invalidated = true)
    assertThat(store.data.value).isEqualTo(marked)
    assertThat(seen.dropWhile { it?.value != "written" }.map { it?.value }.distinct())
      .containsExactly("written")
    assertThat(fetch.callCount).isEqualTo(0)
    data.assertNoFailures()
  }

  @Test
  fun `a collector shows an outdated fetch's value marked stale and fetches nothing more`() =
    runTest {
      val query = query(QueryStore.inMemory())
      fetch.hold()

      query.state.test {
        assertThat(awaitItem()).isEqualTo(QueryState(null, Fetching(forced = false)))
        // The collector's fetch runs before the call, so it may have read the server before it
        runCurrent()
        assertThat(fetch.running).isEqualTo(1)
        query.invalidate()
        fetch.release()
        // A first load still shows what the fetch returned, with the mark on it
        assertThat(awaitItem())
          .isEqualTo(QueryState(Fetched("old", START, invalidated = true), Idle))
        advanceTimeBy(1.minutes)
        expectNoEvents()
      }
      // Marked data on screen waits for the next collector, or for a request
      assertThat(fetch.callCount).isEqualTo(1)
    }

  @Test
  fun `a value written while the default invalidate runs stays and is marked stale`() = runTest {
    val stored = MutableStateFlow<Fetched<Int>?>(Fetched(1, START))
    val writing = CompletableDeferred<Unit>()
    val proceed = CompletableDeferred<Unit>()
    var gated = true
    // No invalidate of its own, so marking is a read and a conditional write
    val store =
      QueryStore.of(
        stored,
        write = { fetched, keepsStored ->
          if (gated) {
            gated = false
            writing.complete(Unit)
            proceed.await()
          }
          stored.writeFetched(fetched, keepsStored)
        },
        clear = { stored.value = null },
      )
    val numbers = testDataScope(clock = testClock(START))
    val query = numbers.query(store, Freshness.staleAfter(1.hours)) { 0 }

    val invalidation = query.invalidate()
    writing.await()
    // A write on another runner lands between the mark's read and its write
    stored.value = Fetched(2, START + 1.minutes)
    proceed.complete(Unit)
    invalidation.awaitSucceeded()

    assertThat(stored.value).isEqualTo(Fetched(2, START + 1.minutes, invalidated = true))
    numbers.assertNoFailures()
  }

  @Test
  fun `the default invalidate goes by the last answer when the store asks again`() = runTest {
    val stored = MutableStateFlow<Fetched<Int>?>(Fetched(1, START - 1.minutes))
    var retried = false
    val store =
      QueryStore.of(
        stored,
        write = { fetched, keepsStored ->
          if (!retried) {
            retried = true
            // A first try that saw the value the mark read, so it may write, then lost a race to
            // another writer
            assertThat(keepsStored(START - 1.minutes, false)).isFalse()
            stored.value = Fetched(2, START)
          }
          stored.writeFetched(fetched, keepsStored)
        },
        clear = { stored.value = null },
      )
    val numbers = testDataScope(clock = testClock(START))
    val query = numbers.query(store, Freshness.staleAfter(1.hours)) { 0 }

    query.invalidate().awaitSucceeded()

    // The retry kept value 2, so the mark read again and marked it
    assertThat(stored.value).isEqualTo(Fetched(2, START, invalidated = true))
    numbers.assertNoFailures()
  }

  @Test
  fun `a value written while a store's own invalidate waits stays and is marked stale`() = runTest {
    val stored = MutableStateFlow<Fetched<Int>?>(Fetched(1, START - 1.minutes))
    val marking = CompletableDeferred<Unit>()
    val proceed = CompletableDeferred<Unit>()
    // Sets the flag in one update, after a suspension like a database call on its own dispatcher
    val store =
      QueryStore.of(
        stored,
        write = { fetched, keepsStored -> stored.writeFetched(fetched, keepsStored) },
        clear = { stored.value = null },
        invalidate = {
          marking.complete(Unit)
          proceed.await()
          stored.update { it?.copy(invalidated = true) }
        },
      )
    val numbers = testDataScope(clock = testClock(START))
    val query = numbers.query(store, Freshness.staleAfter(1.hours)) { 0 }
    val mutations = numbers.newActionRunner()

    // A write on another runner, queued before the invalidation, that lands while its mark waits
    val mutation = mutations.run {
      marking.await()
      store.write(Fetched(2, START))
    }
    val invalidation = query.invalidate()
    runCurrent()
    mutation.assertSucceeded()
    invalidation.assertRunning()
    assertThat(stored.value).isEqualTo(Fetched(2, START))

    proceed.complete(Unit)
    invalidation.awaitSucceeded()
    val marked = Fetched(2, START, invalidated = true)
    assertThat(stored.value).isEqualTo(marked)
    assertThat(query.storedState.first()).isEqualTo(QueryState(marked, Idle))
    numbers.assertNoFailures()
  }

  @Test
  fun `the default invalidate fails when the store's data never shows what it holds`() = runTest {
    var writes = 0
    // Reads always show the first value, and every write finds another one stored
    val store =
      QueryStore.of<Int>(
        data = flowOf(Fetched(1, START)),
        write = { _, keepsStored ->
          writes++
          check(keepsStored(START + 1.minutes, false)) { "The marked copy replaced a newer value" }
        },
        clear = {},
      )
    val numbers = testDataScope(clock = testClock(START))
    val query = numbers.query(store, Freshness.staleAfter(1.hours)) { 0 }

    val invalidation = query.invalidate()
    runCurrent()

    // It gives up and reports, and never writes the old value over the newer one
    invalidation.assertFailed<IllegalStateException>()
    assertThat(writes).isEqualTo(100)
    assertThat(numbers.actionFailures.take().size).isEqualTo(1)
  }

  @Test
  fun `invalidateAndRefresh shows the cached value until the new one and shares its fetch`() =
    runTest {
      val store = QueryStore.inMemory(Fetched("cached", START))
      val query = query(store)

      query.state.test {
        assertThat(awaitItem()).isEqualTo(QueryState(Fetched("cached", START), Idle))
        advanceTimeBy(1.minutes)
        fetch.hold()
        server = "new"
        val first = query.invalidateAndRefresh()
        // A forced request made after the call shares its fetch
        val second = query.refresh()
        assertThat(second).isSameInstanceAs(first)
        // Nothing is cleared: the cached value keeps showing, marked, while the fetch runs
        val marked = Fetched("cached", START, invalidated = true)
        assertThat(awaitItem().fetched).isEqualTo(marked)
        assertThat(expectMostRecentItem()).isEqualTo(QueryState(marked, Fetching(forced = true)))
        fetch.release()
        assertThat(awaitItem()).isEqualTo(QueryState(Fetched("new", START + 1.minutes), Idle))
        first.awaitSucceeded()
        expectNoEvents()
      }
      assertThat(fetch.callCount).isEqualTo(1)
    }

  @Test
  fun `invalidateAndRefresh on a key fetches that key again`() = runTest {
    val keyedFetch: TestKeyedFetch<String, String> = TestKeyedFetch { key -> "$key $server" }
    val stores = QueryStore.inMemoryByKey<String, String>()
    val values =
      testDataScope(clock = testClock(START))
        .keyed(store = stores, freshness = Freshness.staleAfter(1.hours), fetch = keyedFetch)
    values.refresh("a").awaitSucceeded()
    values.refresh("b").awaitSucceeded()

    server = "new"
    values.invalidateAndRefresh("a").awaitSucceeded()

    assertThat(stores("a").data.value).isEqualTo(Fetched("a new", START))
    assertThat(stores("b").data.value).isEqualTo(Fetched("b old", START))
    assertThat(keyedFetch.calls).containsExactly("a", "b", "a")
  }

  /**
   * A store whose first fetch write waits, after the query handed it the value and before it is
   * stored, until [release]. It records each value it stores, so a test reads the exact order
   * without a collector that could miss one.
   */
  private class GatedWriteStore(private val delegate: InMemoryQueryStore<String>) :
    QueryStore<String> {
    private val gate = CompletableDeferred<Unit>()
    val history = mutableListOf<Fetched<String>?>()

    /** The value of the write that waits, or null when none does. */
    var pending: Fetched<String>? = null
      private set

    override val data: Flow<Fetched<String>?> = delegate.data

    fun release() {
      gate.complete(Unit)
    }

    override suspend fun writeFetched(fetched: Fetched<String>, keepsStored: KeepsStored) {
      if (!gate.isCompleted) {
        pending = fetched
        gate.await()
        pending = null
      }
      delegate.writeFetched(fetched, keepsStored)
      history += delegate.data.value
    }

    override suspend fun clearFetched(keepsStored: KeepsStored) {
      delegate.clearFetched(keepsStored)
      history += delegate.data.value
    }

    override suspend fun invalidate() {
      delegate.invalidate()
      history += delegate.data.value
    }
  }

  private companion object {
    val START: Instant = Instant.parse("2026-06-10T12:00:00Z")
  }
}
