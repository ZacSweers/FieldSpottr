// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import dev.zacsweers.fieldspottr.data.core.impl.MAX_DECLINED_HOLD_OFFS
import dev.zacsweers.fieldspottr.data.core.impl.MAX_REMEMBERED_KEYS
import dev.zacsweers.fieldspottr.data.core.impl.holdOffsHeld
import dev.zacsweers.fieldspottr.data.core.impl.keysHeld
import dev.zacsweers.fieldspottr.data.core.impl.keysRemembered
import dev.zacsweers.fieldspottr.data.core.testing.TestDataScope
import dev.zacsweers.fieldspottr.data.core.testing.TestKeyedFetch
import dev.zacsweers.fieldspottr.data.core.testing.awaitSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.testClock
import dev.zacsweers.fieldspottr.data.core.testing.testDataScope
import kotlin.test.Test
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

class KeyedDataQueriesTest {

  private val failing = mutableSetOf<String>()
  // Numbers each key's fetches, like a#2 for a's second, so a test can tell them apart
  private val fetch: TestKeyedFetch<String, String> = TestKeyedFetch { key ->
    check(key !in failing) { "$key failed" }
    "$key#${fetch.callCount(key)}"
  }
  private lateinit var data: TestDataScope

  private fun TestScope.values(
    store: (String) -> QueryStore<String> = QueryStore.inMemoryByKey(),
    maxFetches: Int = Int.MAX_VALUE,
  ): KeyedDataQueries<String, String> {
    data = testDataScope(clock = testClock(START))
    return data.keyed(
      store = store,
      freshness = Freshness.staleAfter(1.hours),
      maxFetches = maxFetches,
      fetch = fetch,
    )
  }

  @Test
  fun `freshness can differ per key`() = runTest {
    val values =
      testDataScope(clock = testClock(START))
        .keyed(
          store = QueryStore.inMemoryByKey<String, String>(),
          freshness = { key ->
            Freshness.staleAfter(
              if (key == "today") {
                5.minutes
              } else {
                1.hours
              }
            )
          },
          fetch = fetch,
        )
    val keys = listOf("today", "later")

    values.data(keys).first { loaded -> loaded.values.none { it == null } }
    advanceTimeBy(10.minutes)
    values.data(keys).first { loaded -> loaded["today"]?.value == "today#2" }
    // Later is still fresh, so only this forced request fetches it again
    values.requestFetch(listOf("later"), force = true).await()
    assertThat(fetch.callCount("today")).isEqualTo(2)
    assertThat(fetch.callCount("later")).isEqualTo(2)
  }

  @Test
  fun `collectors of one key share its fetch`() = runTest {
    fetch.hold()
    val values = values()

    val collectors = List(3) { async { values.data("a").first { it != null } } }
    testScheduler.runCurrent()
    fetch.release()
    assertThat(collectors.awaitAll().map { it!!.value }).containsExactly("a#1", "a#1", "a#1")
    assertThat(fetch.calls).containsExactly("a")
  }

  @Test
  fun `data over many keys fetches them together and combines them`() = runTest {
    fetch.hold()
    val values = values()

    val combined = async {
      values.data(listOf("a", "b", "a")).first { loaded -> loaded.values.none { it == null } }
    }
    testScheduler.runCurrent()
    assertThat(fetch.running).isEqualTo(2)
    fetch.release()
    assertThat(combined.await().mapValues { it.value!!.value })
      .isEqualTo(mapOf("a" to "a#1", "b" to "b#1"))
    assertThat(fetch.calls).containsExactlyInAnyOrder("a", "b")
  }

  @Test
  fun `no keys emits an empty map and an idle status`() = runTest {
    val values = values()

    assertThat(values.data(emptyList()).first()).isEmpty()
    assertThat(values.status(emptyList()).first()).isEqualTo(FetchStatus.Idle)
  }

  @Test
  fun `maxFetches caps the fetches in flight across keys`() = runTest {
    fetch.hold()
    val values = values(maxFetches = 1)

    val combined = async {
      values.data(listOf("a", "b")).first { loaded -> loaded.values.none { it == null } }
    }
    testScheduler.runCurrent()
    assertThat(fetch.running).isEqualTo(1)
    fetch.release()
    combined.await()
    assertThat(fetch.maxRunning).isEqualTo(1)
    assertThat(fetch.calls).hasSize(2)
  }

  @Test
  fun `status over many keys reports a failure first`() = runTest {
    failing += "b"
    val values = values()

    backgroundScope.launch { values.data(listOf("a", "b")).collect {} }
    assertThat(values.status(listOf("a", "b")).first { it is FetchStatus.Failed })
      .isInstanceOf<FetchStatus.Failed>()
    data.fetchFailures.assertSingle<IllegalStateException>()
  }

  @Test
  fun `a key's stored value outlives its query`() = runTest {
    val values = values()

    assertThat(values.data("a").first { it != null }!!.value).isEqualTo("a#1")
    testScheduler.runCurrent()
    // The unused query was dropped once its fetch settled, so this builds a new one over the store
    assertThat(values.data("a").first()!!.value).isEqualTo("a#1")
    // The stored value is fresh, so only the forced request fetches here
    values.requestFetch("a", force = true).await()
    assertThat(fetch.calls).containsExactly("a", "a")
  }

  @Test
  fun `a key the store drops fetches again while collected`() = runTest {
    val stores = QueryStore.inMemoryByKey<String, String>(maxEntries = 1)
    val values = values(store = stores)

    backgroundScope.launch { values.data("a").collect {} }
    backgroundScope.launch { values.data("b").collect {} }
    values.data("a").first { it != null }
    values.data("b").first { it != null }
    // Another writer removes a's value, so a's collector fetches it again at once
    stores("a").clear()
    values.data("a").first { it?.value == "a#2" }
    testScheduler.runCurrent()
    assertThat(fetch.callCount("a")).isEqualTo(2)
    assertThat(fetch.callCount("b")).isEqualTo(1)
  }

  @Test
  fun `two keys collected over an in-memory cap of one fetch once each and stay stored`() =
    runTest {
      val stores = QueryStore.inMemoryByKey<String, String>(maxEntries = 1)
      val values = values(store = stores)

      backgroundScope.launch { values.data("a").collect {} }
      backgroundScope.launch { values.data("b").collect {} }
      testScheduler.runCurrent()
      advanceTimeBy(1.minutes)

      // Collected keys are never dropped, so neither write evicts the other key
      assertThat(fetch.calls).containsExactlyInAnyOrder("a", "b")
      assertThat(stores("a").data.value?.value).isEqualTo("a#1")
      assertThat(stores("b").data.value?.value).isEqualTo("b#1")
    }

  @Test
  fun `invalidating an uncollected key makes its next collect fetch`() = runTest {
    val values = values()

    values.data("a").first { it != null }
    testScheduler.runCurrent()
    assertThat(values.invalidate("a").await()).isEqualTo(OperationState.Succeeded)
    assertThat(fetch.calls).containsExactly("a")
    assertThat(values.data("a").first { it?.value == "a#2" }!!.value).isEqualTo("a#2")
    assertThat(fetch.calls).containsExactly("a", "a")
  }

  @Test
  fun `clearing the in-memory stores empties every key`() = runTest {
    val stores = QueryStore.inMemoryByKey<String, String>()
    val values = values(store = stores)

    values.data(listOf("a", "b")).first { loaded -> loaded.values.none { it == null } }
    stores.clear()
    assertThat(values.data("a").first { it != null }!!.value).isEqualTo("a#2")
    assertThat(fetch.callCount("a")).isEqualTo(2)
  }

  @Test
  fun `stored shows a key's store without fetching`() = runTest {
    val stores = QueryStore.inMemoryByKey<String, String>()
    stores("a").write("cached", START - 2.hours)
    val values = values(store = stores)

    assertThat(values.stored("a").first()?.value).isEqualTo("cached")
    testScheduler.runCurrent()
    // Stale, yet nothing fetched, and the key's query is gone once nothing collects it
    assertThat(fetch.calls).isEmpty()
    assertThat(values.keysHeld).isEqualTo(0)
  }

  @Test
  fun `an unforced request fetches only the stale keys`() = runTest {
    val values = values()

    values.data("a").first { it != null }
    testScheduler.runCurrent()
    assertThat(values.requestFetch(listOf("a", "b"), force = false).await())
      .isEqualTo(OperationState.Succeeded)
    assertThat(fetch.calls).containsExactly("a", "b")

    advanceTimeBy(2.hours)
    assertThat(values.requestFetch(listOf("a", "b"), force = false).await())
      .isEqualTo(OperationState.Succeeded)
    assertThat(fetch.calls).containsExactlyInAnyOrder("a", "b", "a", "b")
    assertThat(values.requestFetch(emptyList(), force = false).await())
      .isEqualTo(OperationState.Succeeded)
  }

  @Test
  fun `a forced request fetches a fresh key again`() = runTest {
    val values = values()

    values.data("a").first { it != null }
    assertThat(values.requestFetch("a", force = true).await()).isEqualTo(OperationState.Succeeded)
    assertThat(values.data("a").first()!!.value).isEqualTo("a#2")
  }

  @Test
  fun `a forced request keeps its key's query until it settles`() = runTest {
    fetch.hold()
    val values = values()

    val request = values.requestFetch("a", force = true)
    testScheduler.runCurrent()
    // A dropped query would leave a new one here that reports Idle
    assertThat(values.status("a").first()).isEqualTo(FetchStatus.Fetching(forced = true))
    fetch.release()
    assertThat(request.await()).isEqualTo(OperationState.Succeeded)
  }

  @Test
  fun `a forced request over many keys settles once all have`() = runTest {
    val values = values()

    values.data(listOf("a", "b")).first { loaded -> loaded.values.none { it == null } }
    assertThat(values.requestFetch(listOf("a", "b"), force = true).await())
      .isEqualTo(OperationState.Succeeded)
    assertThat(fetch.calls).containsExactlyInAnyOrder("a", "b", "a", "b")

    failing += "b"
    assertThat(values.requestFetch(listOf("a", "b"), force = true).await())
      .isInstanceOf<OperationState.Failed>()
    assertThat(values.requestFetch(emptyList(), force = true).await())
      .isEqualTo(OperationState.Succeeded)
  }

  @Test
  fun `invalidateAll marks keys in use and keys not in use stale`() = runTest {
    val stores = QueryStore.inMemoryByKey<String, String>()
    val values = values(store = stores)
    values.data(listOf("a", "b")).first { loaded -> loaded.values.none { it == null } }
    testScheduler.runCurrent()
    backgroundScope.launch { values.data("a").collect {} }
    testScheduler.runCurrent()

    assertThat(values.invalidateAll().await()).isEqualTo(OperationState.Succeeded)
    // A is in use, so its store is marked. B was not, so only the set's record covers it.
    assertThat(stores("a").data.value).isEqualTo(Fetched("a#1", START, invalidated = true))
    assertThat(stores("b").data.value).isEqualTo(Fetched("b#1", START))
    advanceTimeBy(1.minutes)
    assertThat(values.data("b").first { it?.value == "b#2" }?.fetchedAt)
      .isEqualTo(START + 1.minutes)
    testScheduler.runCurrent()

    // Fetched after the call, so the record leaves it fresh
    values.data("b").first()
    testScheduler.runCurrent()
    assertThat(fetch.callCount("b")).isEqualTo(2)
    // A's collector leaves invalidated data for the next collect, as invalidate does
    assertThat(fetch.callCount("a")).isEqualTo(1)
  }

  @Test
  fun `invalidateAll covers a key whose query was built after the call`() = runTest {
    val stores = QueryStore.inMemoryByKey<String, String>()
    stores("c").write("cached", START - 1.minutes)
    val values = values(store = stores)

    values.invalidateAll().await()
    // Built only now, and fresh by its own freshness
    values.fetchIfStale("c").await()
    assertThat(fetch.callCount("c")).isEqualTo(1)
    assertThat(stores("c").data.value).isEqualTo(Fetched("c#1", START))
  }

  @Test
  fun `invalidateAll marks the result of a fetch in flight stale`() = runTest {
    val values = values()
    fetch.hold()
    val request = values.fetchIfStale("a")
    testScheduler.runCurrent()
    assertThat(fetch.running).isEqualTo(1)

    advanceTimeBy(1.seconds)
    values.invalidateAll()
    fetch.release()
    request.awaitSucceeded()
    // Its fetch time is before the call, so the next unforced request fetches again
    values.fetchIfStale("a").await()
    assertThat(fetch.callCount("a")).isEqualTo(2)
  }

  @Test
  fun `a key that fetched after invalidateAll stays fresh when the clock is set back`() = runTest {
    val values = values()
    values.invalidateAll().await()
    data.clock.shift(-10.minutes)

    values.fetchIfStale("a").await()
    assertThat(fetch.callCount("a")).isEqualTo(1)
    // The time rule alone would read its fetch as stale, so the set remembers it
    assertThat(values.keysRemembered).isEqualTo(1)
    // Fetched a moment ago, inside the 1 hour window
    values.fetchIfStale("a").await()
    assertThat(fetch.callCount("a")).isEqualTo(1)
  }

  @Test
  fun `invalidateAll remembers no keys while the clock moves forward`() = runTest {
    val values = values()
    values.invalidateAll().await()
    advanceTimeBy(1.seconds)

    repeat(100) { values.fetchIfStale("key$it").await() }

    assertThat(fetch.calls).hasSize(100)
    assertThat(values.keysRemembered).isEqualTo(0)
    // Fetched after the call, so the time rule alone leaves them fresh
    repeat(100) { values.fetchIfStale("key$it").await() }
    assertThat(fetch.calls).hasSize(100)
  }

  @Test
  fun `invalidateAll remembers a bounded number of keys and a forgotten one is stale again`() =
    runTest {
      val values = values()
      values.invalidateAll().await()
      data.clock.shift(-30.minutes)

      repeat(MAX_REMEMBERED_KEYS + 1) { values.fetchIfStale("key$it").await() }
      assertThat(values.keysRemembered).isEqualTo(MAX_REMEMBERED_KEYS)

      // The first key was forgotten, so the mark reads its fetch as stale and it fetches again
      values.fetchIfStale("key0").await()
      assertThat(fetch.callCount("key0")).isEqualTo(2)
      // A key still remembered stays fresh
      values.fetchIfStale("key${MAX_REMEMBERED_KEYS}").await()
      assertThat(fetch.callCount("key${MAX_REMEMBERED_KEYS}")).isEqualTo(1)
    }

  @Test
  fun `a newer invalidateAll forgets the keys the last one remembered`() = runTest {
    val values = values()
    values.invalidateAll().await()
    data.clock.shift(-10.minutes)
    values.fetchIfStale("a").await()
    assertThat(values.keysRemembered).isEqualTo(1)

    values.invalidateAll().await()
    assertThat(values.keysRemembered).isEqualTo(0)
    values.fetchIfStale("a").await()
    assertThat(fetch.callCount("a")).isEqualTo(2)
  }

  @Test
  fun `requestFetchAll fetches only the keys in use`() = runTest {
    val values = values()
    values.data(listOf("a", "b")).first { loaded -> loaded.values.none { it == null } }
    testScheduler.runCurrent()
    backgroundScope.launch { values.data("a").collect {} }
    testScheduler.runCurrent()

    assertThat(values.requestFetchAll(force = true).await()).isEqualTo(OperationState.Succeeded)

    assertThat(fetch.calls).containsExactlyInAnyOrder("a", "b", "a")
  }

  @Test
  fun `a key's hold-off outlives its query`() = runTest {
    failing += "a"
    val values = values()

    values.data("a").first()
    testScheduler.runCurrent()
    assertThat(fetch.callCount("a")).isEqualTo(1)
    assertThat(values.keysHeld).isEqualTo(0)
    // A new query for the key, inside the window the failure opened
    values.data("a").first()
    testScheduler.runCurrent()
    assertThat(fetch.callCount("a")).isEqualTo(1)

    advanceTimeBy(1.seconds)
    values.data("a").first()
    testScheduler.runCurrent()
    assertThat(fetch.callCount("a")).isEqualTo(2)
  }

  @Test
  fun `a hold-off record goes once its window ended more than the longest wait ago`() = runTest {
    failing += "a"
    val values = values()
    values.data("a").first()
    testScheduler.runCurrent()
    advanceTimeBy(1.seconds)
    // The second failure in a row opens a 2 second window, which ends 3 seconds in
    values.data("a").first()
    testScheduler.runCurrent()
    assertThat(fetch.callCount("a")).isEqualTo(2)
    assertThat(values.holdOffsHeld).isEqualTo(1)

    // Exactly the default policy's longest wait after the window ended, another key's check keeps
    // the record
    advanceTimeBy(2.seconds + 1.minutes)
    values.data("b").first()
    testScheduler.runCurrent()
    assertThat(values.holdOffsHeld).isEqualTo(1)

    advanceTimeBy(1.milliseconds)
    // A third key's check reads its own record, which drops the spent one
    values.data("c").first()
    testScheduler.runCurrent()
    assertThat(values.holdOffsHeld).isEqualTo(0)

    // The next failure starts again at 1 second
    values.data("a").first()
    testScheduler.runCurrent()
    assertThat(fetch.callCount("a")).isEqualTo(3)
    advanceTimeBy(1.seconds)
    values.data("a").first()
    testScheduler.runCurrent()
    assertThat(fetch.callCount("a")).isEqualTo(4)
  }

  @Test
  fun `a keyed set's own policy sets every key's hold-off`() = runTest {
    failing += "a"
    data = testDataScope(clock = testClock(START), fetchRetry = RetryPolicy { 1.minutes })
    val values =
      data.keyed(
        store = QueryStore.inMemoryByKey(),
        freshness = Freshness.staleAfter(1.hours),
        retry = RetryPolicy { 5.seconds },
        fetch = fetch,
      )

    values.data("a").first()
    testScheduler.runCurrent()
    advanceTimeBy(5.seconds - 1.milliseconds)
    values.data("a").first()
    testScheduler.runCurrent()
    assertThat(fetch.callCount("a")).isEqualTo(1)
    advanceTimeBy(1.milliseconds)
    values.data("a").first()
    testScheduler.runCurrent()
    assertThat(fetch.callCount("a")).isEqualTo(2)
  }

  /** A keyed set over in-memory stores whose policy declines every failure. */
  private fun TestScope.decliningValues(): KeyedDataQueries<String, String> {
    data = testDataScope(clock = testClock(START))
    return data.keyed(
      store = QueryStore.inMemoryByKey(),
      freshness = Freshness.staleAfter(1.hours),
      retry = RetryPolicy(maxAttempts = 1) { 1.seconds },
      fetch = fetch,
    )
  }

  @Test
  fun `a key's declined record outlives its query`() = runTest {
    failing += "a"
    val values = decliningValues()

    values.data("a").first()
    testScheduler.runCurrent()
    assertThat(values.keysHeld).isEqualTo(0)
    advanceTimeBy(30.days)
    // A new query for the key still holds off
    values.data("a").first()
    testScheduler.runCurrent()
    assertThat(fetch.callCount("a")).isEqualTo(1)
    assertThat(values.holdOffsHeld).isEqualTo(1)

    // A requested fetch that succeeds clears the record
    failing -= "a"
    assertThat(values.requestFetch("a", force = true).await()).isEqualTo(OperationState.Succeeded)
    assertThat(values.holdOffsHeld).isEqualTo(0)
  }

  @Test
  fun `a keyed set drops the oldest declined record past its cap`() = runTest {
    val keys = List(MAX_DECLINED_HOLD_OFFS + 1) { "k$it" }
    failing += keys
    val values = decliningValues()

    for (key in keys) {
      values.data(key).first()
      testScheduler.runCurrent()
    }
    assertThat(values.holdOffsHeld).isEqualTo(MAX_DECLINED_HOLD_OFFS)

    // k0 declined longest ago, so its record went, and it fetches on its own once more
    values.data("k0").first()
    testScheduler.runCurrent()
    assertThat(fetch.callCount("k0")).isEqualTo(2)
    // Its failure declines again, which drops k1, the oldest now
    values.data("k0").first()
    testScheduler.runCurrent()
    assertThat(fetch.callCount("k0")).isEqualTo(2)
    assertThat(values.holdOffsHeld).isEqualTo(MAX_DECLINED_HOLD_OFFS)
    values.data(keys.last()).first()
    testScheduler.runCurrent()
    assertThat(fetch.callCount(keys.last())).isEqualTo(1)
    values.data("k1").first()
    testScheduler.runCurrent()
    assertThat(fetch.callCount("k1")).isEqualTo(2)
  }

  // The eviction tests below check keysHeld without calling the queries again, since any keyed
  // access could clean up the entry and hide a missed eviction.

  @Test
  fun `a key is dropped once the fetch its last collector left settles`() = runTest {
    fetch.hold()
    val stores = QueryStore.inMemoryByKey<String, String>()
    val values = values(store = stores)

    val collector = backgroundScope.launch { values.data("a").collect {} }
    testScheduler.runCurrent()
    collector.cancel()
    testScheduler.runCurrent()
    // The fetch still runs, so the key keeps its query and its ordering
    assertThat(values.keysHeld).isEqualTo(1)
    fetch.release()
    testScheduler.runCurrent()
    assertThat(values.keysHeld).isEqualTo(0)
    // Dropping the query leaves the data in the store
    assertThat(stores("a").data.first()?.value).isEqualTo("a#1")
  }

  @Test
  fun `a key is dropped once an automatic refetch its last collector left settles`() = runTest {
    val stores = QueryStore.inMemoryByKey<String, String>()
    val values = values(store = stores)

    val collector = backgroundScope.launch { values.data("a").collect {} }
    stores("a").data.first { it != null }
    testScheduler.runCurrent()
    fetch.hold()
    // An emptied store makes the collector fetch again, with no keyed call of its own
    stores("a").clear()
    testScheduler.runCurrent()
    assertThat(fetch.calls).containsExactly("a", "a")
    collector.cancel()
    testScheduler.runCurrent()
    assertThat(values.keysHeld).isEqualTo(1)
    fetch.release()
    testScheduler.runCurrent()
    assertThat(values.keysHeld).isEqualTo(0)
  }

  @Test
  fun `a key is dropped once its explicit operations settle`() = runTest {
    fetch.hold()
    val values = values()

    val request = values.requestFetch("a", force = true)
    val invalidate = values.invalidate("a")
    testScheduler.runCurrent()
    assertThat(values.keysHeld).isEqualTo(1)
    fetch.release()
    assertThat(request.await()).isEqualTo(OperationState.Succeeded)
    assertThat(invalidate.await()).isEqualTo(OperationState.Succeeded)
    testScheduler.runCurrent()
    assertThat(values.keysHeld).isEqualTo(0)
  }

  @Test
  fun `a key is dropped once cancelled and superseded fetches settle`() = runTest {
    fetch.hold()
    val values = values()

    val running = values.requestFetch("a", force = true)
    testScheduler.runCurrent()
    // Each invalidation stops the next forced request from joining, so queued cancels running,
    // then last cancels queued before it ever starts
    values.invalidate("a")
    val queued = values.requestFetch("a", force = true)
    values.invalidate("a")
    val last = values.requestFetch("a", force = true)
    testScheduler.runCurrent()
    assertThat(running.state.value).isEqualTo(OperationState.Cancelled)
    assertThat(queued.state.value).isEqualTo(OperationState.Cancelled)
    assertThat(values.keysHeld).isEqualTo(1)
    fetch.release()
    assertThat(last.await()).isEqualTo(OperationState.Succeeded)
    testScheduler.runCurrent()
    assertThat(values.keysHeld).isEqualTo(0)
  }

  @Test
  fun `keys drop once concurrent work on other threads settles`() = runTest {
    val clock = testClock(START)
    withContext(Dispatchers.Default) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val values =
          DataScope.Factory(onFailure = {}, clock = clock).create(scope).keyed(
            QueryStore.inMemoryByKey<Int, Int>(),
            Freshness.staleAfter(1.hours),
          ) { key ->
            yield()
            key
          }
        // Invalidations let later forced fetches cancel earlier ones, so cancelled commands are in
        // the mix too
        List(500) { i ->
            async {
              val key = i % 3
              when (i % 5) {
                0 -> values.requestFetch(key, force = true).await()
                1 -> values.requestFetch(key, force = false).await()
                2 -> values.invalidate(key).await()
                3 -> values.stored(key).first()
                else -> values.data(key).first { it != null }
              }
            }
          }
          .awaitAll()
        // A key drops as its last job completes, which can trail the handles a little
        withTimeout(5.seconds) {
          while (values.keysHeld != 0) {
            delay(1)
          }
        }
      } finally {
        scope.cancel()
      }
    }
  }

  private companion object {
    val START = Instant.parse("2026-06-10T12:00:00Z")
  }
}
