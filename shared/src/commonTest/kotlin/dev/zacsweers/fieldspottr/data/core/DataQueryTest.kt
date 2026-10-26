// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isSameInstanceAs
import dev.zacsweers.fieldspottr.data.core.testing.TestDataScope
import dev.zacsweers.fieldspottr.data.core.testing.TestFetch
import dev.zacsweers.fieldspottr.data.core.testing.assertCancelled
import dev.zacsweers.fieldspottr.data.core.testing.assertSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.awaitSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.testClock
import dev.zacsweers.fieldspottr.data.core.testing.testDataScope
import dev.zacsweers.fieldspottr.data.core.testing.testDataScopeFactory
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

class DataQueryTest {

  private val stored = MutableStateFlow<Fetched<Int>?>(null)
  // No invalidate, so these tests cover the default that rewrites the value with a stale time
  private val store =
    QueryStore.of(stored, clear = { stored.value = null }) { value, fetchedAt ->
      stored.value = Fetched(value, fetchedAt)
    }
  // One more than what is stored, so each fetch stores a value of its own
  private val fetch = TestFetch { (stored.value?.value ?: 0) + 1 }
  private lateinit var data: TestDataScope

  private fun TestScope.dataQuery(
    freshness: Freshness = Freshness.staleAfter(1.hours)
  ): DataQuery<Int> {
    data = testDataScope(clock = testClock(START))
    return data.query(store = store, fetch = fetch, freshness = freshness)
  }

  @Test
  fun `stored fetch time inside the window skips the fetch`() = runTest {
    stored.value = Fetched(1, START - 10.minutes)
    val query = dataQuery()

    assertThat(query.data.values().first()).isEqualTo(1)
    // Joins the check the collector started. advanceUntilIdle() skips backgroundScope work.
    query.requestFetch(force = false).await()
    assertThat(fetch.callCount).isEqualTo(0)
  }

  @Test
  fun `stored fetch time past the window fetches and stores the clock time`() = runTest {
    stored.value = Fetched(1, START - 2.hours)
    val query = dataQuery()

    assertThat(query.data.first { it?.value == 2 }).isEqualTo(Fetched(2, START))
    assertThat(fetch.callCount).isEqualTo(1)
  }

  @Test
  fun `stored fetch time ahead of the clock counts as stale`() = runTest {
    stored.value = Fetched(1, START + 1.hours)
    val query = dataQuery()

    query.data.first { it?.value == 2 }
    assertThat(fetch.callCount).isEqualTo(1)
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
    assertThat(fetch.callCount).isEqualTo(2)
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
    assertThat(fetch.callCount).isEqualTo(0)
    assertThat(checkedAt).isEqualTo(START)

    window = 5.minutes
    query.data.first { it?.value == 2 }
    assertThat(fetch.callCount).isEqualTo(1)
  }

  @Test
  fun `collectors join the fetch in flight`() = runTest {
    fetch.hold()
    val query = dataQuery()

    val collectors = List(2) { async { query.data.first { it?.value == 1 } } }
    testScheduler.runCurrent()
    fetch.release()
    collectors.awaitAll()
    assertThat(fetch.callCount).isEqualTo(1)
  }

  @Test
  fun `a forced request fetches even when the data is fresh`() = runTest {
    stored.value = Fetched(1, START - 10.minutes)
    val query = dataQuery()

    assertThat(query.requestFetch(force = true).await()).isEqualTo(OperationState.Succeeded)
    assertThat(fetch.callCount).isEqualTo(1)
    assertThat(stored.value).isEqualTo(Fetched(2, START))
  }

  @Test
  fun `failed fetch fails the handle and reports the cause`() = runTest {
    fetch.respond = { error("fetch failed") }
    val query = dataQuery()

    assertThat(query.requestFetch(force = true).await()).isInstanceOf<OperationState.Failed>()
    assertThat(query.status.value).isInstanceOf<FetchStatus.Failed>()
    data.fetchFailures.assertSingle<IllegalStateException>()
    assertThat(stored.value).isEqualTo(null)
  }

  @Test
  fun `invalidate marks the data stale without fetching`() = runTest {
    stored.value = Fetched(1, START - 10.minutes)
    val query = dataQuery()

    assertThat(query.invalidate().await()).isEqualTo(OperationState.Succeeded)
    assertThat(fetch.callCount).isEqualTo(0)
    assertThat(stored.value).isEqualTo(Fetched(1, QueryStore.INVALIDATED))
    assertThat(query.data.first { it?.value == 2 }).isEqualTo(Fetched(2, START))
    assertThat(fetch.callCount).isEqualTo(1)
  }

  @Test
  fun `invalidate lands after a fetch already in flight`() = runTest {
    fetch.hold()
    val query = dataQuery()

    val request = query.requestFetch(force = false)
    testScheduler.runCurrent()
    val invalidate = query.invalidate()
    fetch.release()
    request.await()
    invalidate.await()
    // The fetch's write came first, so the value it stored is the one marked stale
    assertThat(stored.value).isEqualTo(Fetched(1, QueryStore.INVALIDATED))
  }

  @Test
  fun `forced requests made together share one fetch`() = runTest {
    fetch.hold()
    val query = dataQuery()

    val first = query.requestFetch(force = true)
    testScheduler.runCurrent()
    // Like a second pull to refresh while the first one still spins
    val second = query.requestFetch(force = true)
    fetch.release()
    assertThat(first.await()).isEqualTo(OperationState.Succeeded)
    assertThat(second.await()).isEqualTo(OperationState.Succeeded)
    assertThat(fetch.callCount).isEqualTo(1)
  }

  @Test
  fun `a forced request after a mutation starts its own fetch`() = runTest {
    fetch.hold()
    val query = dataQuery()

    val before = query.requestFetch(force = true)
    testScheduler.runCurrent()
    query.mutate(requireFresh = false) { it.write(100, START) }
    val after = query.requestFetch(force = true)
    fetch.release()
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
    assertThat(fetch.callCount).isEqualTo(0)
  }

  @Test
  fun `a request after invalidate fetches again`() = runTest {
    fetch.hold()
    val query = dataQuery()

    val first = query.requestFetch(force = false)
    testScheduler.runCurrent()
    query.invalidate()
    val second = query.requestFetch(force = false)
    fetch.release()
    first.await()
    second.await()
    // The second request queued behind the invalidation instead of joining the first fetch
    assertThat(fetch.callCount).isEqualTo(2)
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
      testDataScope(clock = testClock(START))
        .query(
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
    fetch.hold()
    val query = dataQuery()

    val request = query.requestFetch(force = false)
    testScheduler.runCurrent()
    val mutation = query.mutate(requireFresh = false) { it.write(100, START) }
    val later = query.requestFetch(force = false)
    fetch.release()
    request.await()
    mutation.await()
    later.await()
    // The fetch's older result landed first, and the later request found the mutation's write fresh
    assertThat(stored.value).isEqualTo(Fetched(100, START))
    assertThat(fetch.callCount).isEqualTo(1)
  }

  @Test
  fun `a mutation that requires fresh data fetches stale data first`() = runTest {
    stored.value = Fetched(1, START - 2.hours)
    val query = dataQuery()
    var seen: Int? = null

    query.mutate(requireFresh = true) { seen = it.data.first()?.value }.await()
    assertThat(seen).isEqualTo(2)
    assertThat(fetch.callCount).isEqualTo(1)
    // Fresh now, so this one skips the fetch
    query.mutate(requireFresh = true) { seen = it.data.first()?.value }.await()
    assertThat(fetch.callCount).isEqualTo(1)
  }

  @Test
  fun `a failed fetch before a mutation fails it and skips the block`() = runTest {
    fetch.respond = { error("fetch failed") }
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
  fun `a clear between the first check and the first value still fetches once`() = runTest {
    val state = MutableStateFlow<Fetched<Int>?>(Fetched(1, START))
    var reads = 0
    // The first read takes 10 ms and the second 20 ms, like reads on a busy database dispatcher, so
    // the check finishes and the clear lands before the observer's first value
    val slowReads = flow {
      delay(
        when (++reads) {
          1 -> 10.milliseconds
          2 -> 20.milliseconds
          else -> 0.milliseconds
        }
      )
      emitAll(state)
    }
    val store =
      QueryStore.of(slowReads, clear = { state.value = null }) { value, fetchedAt ->
        state.value = Fetched(value, fetchedAt)
      }
    val query =
      testDataScope(clock = testClock(START))
        .query(store, fetch = fetch, freshness = Freshness.staleAfter(1.hours))
    val check = query.requestFetch(force = false)
    backgroundScope.launch { query.data.collect {} }
    testScheduler.runCurrent()

    query.mutate(requireFresh = false) { it.clear() }
    testScheduler.advanceTimeBy(30.milliseconds)
    testScheduler.runCurrent()

    check.assertSucceeded()
    assertThat(fetch.callCount).isEqualTo(1)
  }

  @Test
  fun `an observer behind on older values does not recheck a clear again`() = runTest {
    val values = MutableSharedFlow<Fetched<Int>?>(replay = 1, extraBufferCapacity = 16)
    values.emit(Fetched(1, START))
    val store =
      QueryStore.of(values, clear = { values.emit(null) }) { value, fetchedAt ->
        values.emit(Fetched(value, fetchedAt))
      }
    fetch.respond = { error("offline") }
    val query =
      testDataScope(clock = testClock(START))
        .query(store, fetch = fetch, freshness = Freshness.staleAfter(1.hours))
    val resume = CompletableDeferred<Unit>()
    backgroundScope.launch { query.data.collect {} }
    // Stops on the first value, so the newer value and the clear wait in its buffer
    backgroundScope.launch { query.data.collect { if (it?.value == 1) resume.await() } }
    testScheduler.runCurrent()

    values.emit(Fetched(2, START))
    testScheduler.runCurrent()
    store.clear()
    testScheduler.runCurrent()
    assertThat(fetch.callCount).isEqualTo(1)
    resume.complete(Unit)
    testScheduler.runCurrent()

    assertThat(fetch.callCount).isEqualTo(1)
  }

  @Test
  fun `a forced request joins a forced fetch behind an automatic check`() = runTest {
    stored.value = Fetched(1, START)
    val query = dataQuery()
    backgroundScope.launch { query.data.collect {} }
    testScheduler.runCurrent()
    fetch.hold()
    val first = query.requestFetch(force = true)
    testScheduler.runCurrent()

    // Cleared under the query, so its observer queues a check behind the running fetch
    stored.value = null
    testScheduler.runCurrent()
    val second = query.requestFetch(force = true)
    fetch.release()
    testScheduler.runCurrent()

    assertThat(second).isSameInstanceAs(first)
    first.assertSucceeded()
    // The queued check still ran after the fetch's write, and found it fresh
    assertThat(fetch.callCount).isEqualTo(1)
    assertThat(stored.value?.value).isEqualTo(1)
  }

  @Test
  fun `collectors that see one clear share its recheck`() = runTest {
    stored.value = Fetched(1, START)
    val query = dataQuery()
    val firstSawClear = CompletableDeferred<Unit>()
    val secondSawClear = CompletableDeferred<Unit>()
    backgroundScope.launch { query.data.collect { if (it == null) firstSawClear.complete(Unit) } }
    backgroundScope.launch { query.data.collect { if (it == null) secondSawClear.complete(Unit) } }
    testScheduler.runCurrent()

    fetch.hold()
    store.clear()
    firstSawClear.await()
    secondSawClear.await()
    fetch.respond = { error("offline") }
    fetch.release()
    testScheduler.runCurrent()

    assertThat(fetch.callCount).isEqualTo(1)
  }

  @Test
  fun `a collector that sees a clear late shares the recheck that already ran`() = runTest {
    stored.value = Fetched(1, START)
    val query = dataQuery()
    val resume = CompletableDeferred<Unit>()
    backgroundScope.launch { query.data.collect {} }
    // Stops after its first value, so it sees the clear only once the recheck has settled
    backgroundScope.launch { query.data.collect { if (it != null) resume.await() } }
    testScheduler.runCurrent()

    fetch.respond = { error("offline") }
    store.clear()
    testScheduler.runCurrent()
    assertThat(fetch.callCount).isEqualTo(1)
    resume.complete(Unit)
    testScheduler.runCurrent()

    assertThat(fetch.callCount).isEqualTo(1)
  }

  @Test
  fun `a forced fetch stopped with its owner leaves the status idle`() = runTest {
    val owner =
      CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext.job))
    val query =
      testDataScopeFactory(clock = testClock(START))
        .create(owner)
        .query(store = store, fetch = fetch, freshness = Freshness.staleAfter(1.hours))
    fetch.hold()
    val request = query.requestFetch(force = true)
    testScheduler.runCurrent()
    assertThat(query.status.value).isEqualTo(FetchStatus.Fetching(forced = true))

    owner.cancel()
    testScheduler.runCurrent()

    request.assertCancelled()
    assertThat(query.status.value).isEqualTo(FetchStatus.Idle)
  }

  @Test
  fun `a forced fetch replaced by a newer one leaves the status to it`() = runTest {
    val query = dataQuery()
    fetch.hold()
    val first = query.requestFetch(force = true)
    testScheduler.runCurrent()

    // Queued after the first fetch, so the next forced request starts a fetch of its own
    query.invalidate()
    val second = query.requestFetch(force = true)
    testScheduler.runCurrent()

    first.assertCancelled()
    assertThat(query.status.value).isEqualTo(FetchStatus.Fetching(forced = true))
    fetch.release()
    second.awaitSucceeded()
    assertThat(query.status.value).isEqualTo(FetchStatus.Idle)
  }

  @Test
  fun `one collection of an empty query makes one fetch attempt`() = runTest {
    val failing = TestFetch<Int> { error("offline") }
    val query =
      testDataScope(eager = true, clock = testClock(START))
        .query(QueryStore.inMemory(), fetch = failing, freshness = Freshness.staleAfter(1.hours))

    query.data.first()

    // The check on subscription already covers the store's first empty value
    assertThat(failing.callCount).isEqualTo(1)
  }

  @Test
  fun `clearing the store while a fetch writes checks again after the write`() = runTest {
    val state = MutableStateFlow<Fetched<Int>?>(null)
    val published = CompletableDeferred<Unit>()
    val finishWrite = CompletableDeferred<Unit>()
    val store =
      QueryStore.of(state, clear = { state.value = null }) { value, fetchedAt ->
        // Visible before the write returns, like a database that notifies inside its transaction
        state.value = Fetched(value, fetchedAt)
        published.complete(Unit)
        finishWrite.await()
      }
    var calls = 0
    val counting = TestFetch { ++calls }
    val query =
      testDataScope(clock = testClock(START))
        .query(store, fetch = counting, freshness = Freshness.staleAfter(1.hours))
    backgroundScope.launch { query.data.collect {} }

    published.await()
    store.clear()
    testScheduler.runCurrent()
    finishWrite.complete(Unit)
    testScheduler.runCurrent()

    // The emptied store is checked once the write in flight is done, and fetches again
    assertThat(counting.callCount).isEqualTo(2)
    assertThat(state.value?.value).isEqualTo(2)
  }

  @Test
  fun `a combined handle settles once every operation has`() = runTest {
    stored.value = Fetched(1, START)
    val query = dataQuery()

    assertThat(Operation.all(emptyList()).await()).isEqualTo(OperationState.Succeeded)
    // Holds the first forced fetch. After the invalidation, the second cancels it mid-fetch.
    fetch.hold()
    val first = query.requestFetch(force = true)
    testScheduler.runCurrent()
    query.invalidate()
    val both = Operation.all(listOf(first, query.requestFetch(force = true)))
    testScheduler.runCurrent()
    fetch.release()
    assertThat(both.await()).isEqualTo(OperationState.Cancelled)
    fetch.respond = { error("fetch failed") }
    assertThat(Operation.all(listOf(query.requestFetch(force = true))).await())
      .isInstanceOf<OperationState.Failed>()
  }

  private companion object {
    val START = Instant.parse("2026-06-10T12:00:00Z")
  }
}
