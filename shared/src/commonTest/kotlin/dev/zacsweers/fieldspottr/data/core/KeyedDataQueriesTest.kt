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
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

class KeyedDataQueriesTest {

  private val clock = FakeClock(START)
  private val failures = mutableListOf<Throwable>()
  private val fetched = mutableListOf<String>()
  private val failing = mutableSetOf<String>()
  private var gate = CompletableDeferred(Unit)
  private var running = 0
  private var maxRunning = 0

  private fun TestScope.values(
    store: (String) -> QueryStore<String> = QueryStore.inMemoryByKey(),
    maxFetches: Int = Int.MAX_VALUE,
  ) =
    DataQuery.Factory(backgroundScope, onFailure = { failures += it }, clock = clock)
      .keyed(
        store = store,
        fetch = { key ->
          fetched += key
          running++
          maxRunning = maxOf(maxRunning, running)
          try {
            gate.await()
            check(key !in failing) { "$key failed" }
            "$key#${fetched.count { it == key }}"
          } finally {
            running--
          }
        },
        freshness = Freshness.staleAfter(1.hours),
        maxFetches = maxFetches,
      )

  @Test
  fun `freshness can differ per key`() = runTest {
    val values =
      DataQuery.Factory(backgroundScope, onFailure = { failures += it }, clock = clock)
        .keyed(
          store = QueryStore.inMemoryByKey<String, String>(),
          fetch = { key ->
            fetched += key
            "$key#${fetched.count { it == key }}"
          },
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
    clock.now = START + 10.minutes
    values.data(keys).first { loaded -> loaded["today"]?.value == "today#2" }
    // A forced request queues behind later's staleness check, so this counts only its own fetch
    values.requestFetch(listOf("later"), force = true).await()
    assertThat(fetched.count { it == "today" }).isEqualTo(2)
    assertThat(fetched.count { it == "later" }).isEqualTo(2)
  }

  @Test
  fun `collectors of one key share its fetch`() = runTest {
    gate = CompletableDeferred()
    val values = values()

    val collectors = List(3) { async { values.data("a").first { it != null } } }
    testScheduler.runCurrent()
    gate.complete(Unit)
    assertThat(collectors.awaitAll().map { it!!.value }).containsExactly("a#1", "a#1", "a#1")
    assertThat(fetched).containsExactly("a")
  }

  @Test
  fun `data over many keys fetches them together and combines them`() = runTest {
    gate = CompletableDeferred()
    val values = values()

    val combined = async {
      values.data(listOf("a", "b", "a")).first { loaded -> loaded.values.none { it == null } }
    }
    testScheduler.runCurrent()
    assertThat(running).isEqualTo(2)
    gate.complete(Unit)
    assertThat(combined.await().mapValues { it.value!!.value })
      .isEqualTo(mapOf("a" to "a#1", "b" to "b#1"))
    assertThat(fetched).containsExactlyInAnyOrder("a", "b")
  }

  @Test
  fun `no keys emits an empty map and an idle status`() = runTest {
    val values = values()

    assertThat(values.data(emptyList()).first()).isEmpty()
    assertThat(values.status(emptyList()).first()).isEqualTo(FetchStatus.Idle)
  }

  @Test
  fun `maxFetches caps the fetches in flight across keys`() = runTest {
    gate = CompletableDeferred()
    val values = values(maxFetches = 1)

    val combined = async {
      values.data(listOf("a", "b")).first { loaded -> loaded.values.none { it == null } }
    }
    testScheduler.runCurrent()
    assertThat(running).isEqualTo(1)
    gate.complete(Unit)
    combined.await()
    assertThat(maxRunning).isEqualTo(1)
    assertThat(fetched).hasSize(2)
  }

  @Test
  fun `status over many keys reports a failure first`() = runTest {
    failing += "b"
    val values = values()

    backgroundScope.launch { values.data(listOf("a", "b")).collect {} }
    assertThat(values.status(listOf("a", "b")).first { it is FetchStatus.Failed })
      .isInstanceOf<FetchStatus.Failed>()
    assertThat(failures).hasSize(1)
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
    assertThat(fetched).containsExactly("a", "a")
  }

  @Test
  fun `a key the store drops fetches again while collected`() = runTest {
    val values = values(store = QueryStore.inMemoryByKey(maxEntries = 1))

    backgroundScope.launch { values.data("a").collect {} }
    values.data("a").first { it != null }
    // Storing b drops a, and a's collector fetches it again
    values.data("b").first { it != null }
    values.data("a").first { it?.value == "a#2" }
    assertThat(fetched).containsExactly("a", "b", "a")
  }

  @Test
  fun `invalidating an uncollected key makes its next collect fetch`() = runTest {
    val values = values()

    values.data("a").first { it != null }
    testScheduler.runCurrent()
    assertThat(values.invalidate("a").await()).isEqualTo(OperationState.Succeeded)
    assertThat(fetched).containsExactly("a")
    assertThat(values.data("a").first { it?.value == "a#2" }!!.value).isEqualTo("a#2")
    assertThat(fetched).containsExactly("a", "a")
  }

  @Test
  fun `clearing the in-memory stores empties every key`() = runTest {
    val stores = QueryStore.inMemoryByKey<String, String>()
    val values = values(store = stores)

    values.data(listOf("a", "b")).first { loaded -> loaded.values.none { it == null } }
    stores.clear()
    assertThat(values.data("a").first { it != null }!!.value).isEqualTo("a#2")
    assertThat(fetched.count { it == "a" }).isEqualTo(2)
  }

  @Test
  fun `a key's mutation runs after its fetch in flight`() = runTest {
    gate = CompletableDeferred()
    val values = values()

    val fetch = values.requestFetch("a", force = false)
    testScheduler.runCurrent()
    val mutation = values.mutate("a", requireFresh = false) { it.write("edited", START) }
    gate.complete(Unit)
    fetch.await()
    mutation.await()
    // The fetch's older result landed first, so the edit is what stays
    assertThat(values.data("a").first()?.value).isEqualTo("edited")
    assertThat(fetched).containsExactly("a")
  }

  @Test
  fun `stored shows a key's store without fetching`() = runTest {
    val stores = QueryStore.inMemoryByKey<String, String>()
    stores("a").write("cached", START - 2.hours)
    val values = values(store = stores)

    assertThat(values.stored("a").first()?.value).isEqualTo("cached")
    testScheduler.runCurrent()
    // Stale, yet nothing fetched, and the key's query is gone once nothing collects it
    assertThat(fetched).isEmpty()
    assertThat(values.keysHeld).isEqualTo(0)
  }

  @Test
  fun `an unforced request fetches only the stale keys`() = runTest {
    val values = values()

    values.data("a").first { it != null }
    testScheduler.runCurrent()
    assertThat(values.requestFetch(listOf("a", "b"), force = false).await())
      .isEqualTo(OperationState.Succeeded)
    assertThat(fetched).containsExactly("a", "b")

    clock.now = START + 2.hours
    assertThat(values.requestFetch(listOf("a", "b"), force = false).await())
      .isEqualTo(OperationState.Succeeded)
    assertThat(fetched).containsExactlyInAnyOrder("a", "b", "a", "b")
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
    gate = CompletableDeferred()
    val values = values()

    val request = values.requestFetch("a", force = true)
    testScheduler.runCurrent()
    // A dropped query would leave a new one here that reports Idle
    assertThat(values.status("a").first()).isEqualTo(FetchStatus.Fetching(forced = true))
    gate.complete(Unit)
    assertThat(request.await()).isEqualTo(OperationState.Succeeded)
  }

  @Test
  fun `a forced request over many keys settles once all have`() = runTest {
    val values = values()

    values.data(listOf("a", "b")).first { loaded -> loaded.values.none { it == null } }
    assertThat(values.requestFetch(listOf("a", "b"), force = true).await())
      .isEqualTo(OperationState.Succeeded)
    assertThat(fetched).containsExactlyInAnyOrder("a", "b", "a", "b")

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
    gate = CompletableDeferred()
    val stores = QueryStore.inMemoryByKey<String, String>()
    val values = values(store = stores)

    val collector = backgroundScope.launch { values.data("a").collect {} }
    testScheduler.runCurrent()
    collector.cancel()
    testScheduler.runCurrent()
    // The fetch still runs, so the key keeps its query and its ordering
    assertThat(values.keysHeld).isEqualTo(1)
    gate.complete(Unit)
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
    gate = CompletableDeferred()
    // An emptied store makes the collector fetch again, with no keyed call of its own
    stores("a").clear()
    testScheduler.runCurrent()
    assertThat(fetched).containsExactly("a", "a")
    collector.cancel()
    testScheduler.runCurrent()
    assertThat(values.keysHeld).isEqualTo(1)
    gate.complete(Unit)
    testScheduler.runCurrent()
    assertThat(values.keysHeld).isEqualTo(0)
  }

  @Test
  fun `a key is dropped once its explicit operations settle`() = runTest {
    gate = CompletableDeferred()
    val values = values()

    val fetch = values.requestFetch("a", force = true)
    val invalidate = values.invalidate("a")
    val mutation = values.mutate("a", requireFresh = false) { it.write("edited", START) }
    testScheduler.runCurrent()
    assertThat(values.keysHeld).isEqualTo(1)
    gate.complete(Unit)
    assertThat(fetch.await()).isEqualTo(OperationState.Succeeded)
    assertThat(invalidate.await()).isEqualTo(OperationState.Succeeded)
    assertThat(mutation.await()).isEqualTo(OperationState.Succeeded)
    testScheduler.runCurrent()
    assertThat(values.keysHeld).isEqualTo(0)
  }

  @Test
  fun `a key is dropped once cancelled and superseded fetches settle`() = runTest {
    gate = CompletableDeferred()
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
    gate.complete(Unit)
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
    val fetch = values.requestFetch("a", force = false)
    testScheduler.runCurrent()
    // The same query, so the request waits for the mutation instead of fetching beside it
    assertThat(fetched).isEmpty()
    release.complete(Unit)
    assertThat(mutation.await()).isEqualTo(OperationState.Succeeded)
    assertThat(fetch.await()).isEqualTo(OperationState.Succeeded)
    // The mutation's write is fresh, so the request found nothing to fetch
    assertThat(fetched).isEmpty()
    testScheduler.runCurrent()
    assertThat(values.keysHeld).isEqualTo(0)
  }

  @Test
  fun `keys drop once concurrent work on other threads settles`() = runTest {
    withContext(Dispatchers.Default) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val values =
          DataQuery.Factory(scope, onFailure = {}, clock = clock)
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
