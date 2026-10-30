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
import dev.zacsweers.fieldspottr.data.core.impl.invoke
import dev.zacsweers.fieldspottr.data.core.impl.keysHeld
import dev.zacsweers.fieldspottr.data.core.testing.TestDataScope
import dev.zacsweers.fieldspottr.data.core.testing.TestKeyedFetch
import dev.zacsweers.fieldspottr.data.core.testing.testClock
import dev.zacsweers.fieldspottr.data.core.testing.testDataScope
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
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
      fetch = fetch,
      freshness = Freshness.staleAfter(1.hours),
      maxFetches = maxFetches,
    )
  }

  @Test
  fun `freshness can differ per key`() = runTest {
    val values =
      testDataScope(clock = testClock(START))
        .keyed(
          store = QueryStore.inMemoryByKey<String, String>(),
          fetch = fetch,
          freshness = { key ->
            Freshness.staleAfter(
              if (key == "today") {
                5.minutes
              } else {
                1.hours
              }
            )
          },
        )
    val keys = listOf("today", "later")

    values.data(keys).first { loaded -> loaded.values.none { it == null } }
    advanceTimeBy(10.minutes)
    values.data(keys).first { loaded -> loaded["today"]?.value == "today#2" }
    // A forced request queues behind later's staleness check, so this counts only its own fetch
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
    // A forced request queues behind that collector's staleness check, so only it fetches here
    values.requestFetch("a", force = true).await()
    assertThat(fetch.calls).containsExactly("a", "a")
  }

  @Test
  fun `a key the store drops fetches again while collected`() = runTest {
    val values = values(store = QueryStore.inMemoryByKey(maxEntries = 1))

    backgroundScope.launch { values.data("a").collect {} }
    values.data("a").first { it != null }
    // Storing b drops a, and a's collector fetches it again
    values.data("b").first { it != null }
    values.data("a").first { it?.value == "a#2" }
    assertThat(fetch.calls).containsExactly("a", "b", "a")
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
  fun `a key's mutation runs after its fetch in flight`() = runTest {
    fetch.hold()
    val values = values()

    val request = values.requestFetch("a", force = false)
    testScheduler.runCurrent()
    val mutation = values.mutate("a", requireFresh = false) { it.write("edited", START) }
    fetch.release()
    request.await()
    mutation.await()
    // The fetch's older result landed first, so the edit is what stays
    assertThat(values.data("a").first()?.value).isEqualTo("edited")
    assertThat(fetch.calls).containsExactly("a")
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
    val mutation = values.mutate("a", requireFresh = false) { it.write("edited", START) }
    testScheduler.runCurrent()
    assertThat(values.keysHeld).isEqualTo(1)
    fetch.release()
    assertThat(request.await()).isEqualTo(OperationState.Succeeded)
    assertThat(invalidate.await()).isEqualTo(OperationState.Succeeded)
    assertThat(mutation.await()).isEqualTo(OperationState.Succeeded)
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
  fun `a key keeps its query while a mutation runs so later requests order behind it`() = runTest {
    val release = CompletableDeferred<Unit>()
    val values = values()

    val mutation =
      values.mutate("a", requireFresh = false) {
        release.await()
        it.write("edited", START)
      }
    testScheduler.runCurrent()
    assertThat(values.keysHeld).isEqualTo(1)
    val request = values.requestFetch("a", force = false)
    testScheduler.runCurrent()
    // The same query, so the request waits for the mutation instead of fetching beside it
    assertThat(fetch.calls).isEmpty()
    release.complete(Unit)
    assertThat(mutation.await()).isEqualTo(OperationState.Succeeded)
    assertThat(request.await()).isEqualTo(OperationState.Succeeded)
    // The mutation's write is fresh, so the request found nothing to fetch
    assertThat(fetch.calls).isEmpty()
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
          DataScope.Factory(onFailure = {}, clock = clock)
            .create(scope)
            .keyed(
              store = QueryStore.inMemoryByKey<Int, Int>(),
              fetch = { key ->
                yield()
                key
              },
              freshness = Freshness.staleAfter(1.hours),
            )
        // Invalidations and mutations let later forced fetches cancel earlier ones, so cancelled
        // commands are in the mix too
        List(500) { i ->
            async {
              val key = i % 3
              when (i % 5) {
                0 -> values.requestFetch(key, force = true).await()
                1 -> values.requestFetch(key, force = false).await()
                2 -> values.invalidate(key).await()
                3 -> values.mutate(key, requireFresh = i % 2 == 0) { it.write(key, START) }.await()
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
