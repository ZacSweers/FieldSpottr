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
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

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
    // A refresh queues behind later's staleness check, so this counts only its own fetch
    values.refresh(listOf("later")).await()
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
    // A refresh queues behind that collector's staleness check, so only the refresh fetches here
    values.refresh("a").await()
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
  fun `invalidating a key nobody collects makes its next collect fetch`() = runTest {
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
  fun `fetchIfStale fetches only the stale keys`() = runTest {
    val values = values()

    values.data("a").first { it != null }
    testScheduler.runCurrent()
    assertThat(values.fetchIfStale(listOf("a", "b")).await()).isEqualTo(OperationState.Succeeded)
    assertThat(fetched).containsExactly("a", "b")

    clock.now = START + 2.hours
    assertThat(values.fetchIfStale(listOf("a", "b")).await()).isEqualTo(OperationState.Succeeded)
    assertThat(fetched).containsExactlyInAnyOrder("a", "b", "a", "b")
    assertThat(values.fetchIfStale(emptyList()).await()).isEqualTo(OperationState.Succeeded)
  }

  @Test
  fun `refresh fetches a fresh key again`() = runTest {
    val values = values()

    values.data("a").first { it != null }
    assertThat(values.refresh("a").await()).isEqualTo(OperationState.Succeeded)
    assertThat(values.data("a").first()!!.value).isEqualTo("a#2")
  }

  @Test
  fun `a refresh keeps its key's query until it settles`() = runTest {
    gate = CompletableDeferred()
    val values = values()

    val refresh = values.refresh("a")
    testScheduler.runCurrent()
    // A dropped query would leave a new one here that reports Idle
    assertThat(values.status("a").first()).isEqualTo(FetchStatus.Fetching(isRefresh = true))
    gate.complete(Unit)
    assertThat(refresh.await()).isEqualTo(OperationState.Succeeded)
  }

  @Test
  fun `refresh over many keys settles once all have`() = runTest {
    val values = values()

    values.data(listOf("a", "b")).first { loaded -> loaded.values.none { it == null } }
    assertThat(values.refresh(listOf("a", "b")).await()).isEqualTo(OperationState.Succeeded)
    assertThat(fetched).containsExactlyInAnyOrder("a", "b", "a", "b")

    failing += "b"
    assertThat(values.refresh(listOf("a", "b")).await()).isInstanceOf<OperationState.Failed>()
    assertThat(values.refresh(emptyList()).await()).isEqualTo(OperationState.Succeeded)
  }

  private companion object {
    val START = Instant.parse("2026-06-10T12:00:00Z")
  }
}
