// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

class InMemoryQueryStoreTest {

  private val clock = FakeClock(START)
  private var fetches = 0
  private var gate = CompletableDeferred(Unit)

  private fun TestScope.query(store: QueryStore<String>): DataQuery<String> =
    DataQuery.Factory(backgroundScope, onFailure = {}, clock = clock)
      .create(
        store = store,
        fetch = {
          fetches++
          gate.await()
          "fetched"
        },
        freshness = Freshness.staleAfter(1.hours),
      )

  @Test
  fun `a stale seed shows at once and still fetches`() = runTest {
    val store = QueryStore.inMemory(Fetched("cached", QueryStore.INVALIDATED))
    // Readable without collecting
    assertThat(store.data.value?.value).isEqualTo("cached")
    gate = CompletableDeferred()
    val query = query(store)
    val values = mutableListOf<String?>()

    backgroundScope.launch { query.data.values().collect { values += it } }
    testScheduler.runCurrent()
    // The seed is on screen while the fetch waits
    assertThat(values).containsExactly("cached")
    gate.complete(Unit)
    query.data.first { it?.value == "fetched" }
    assertThat(store.data.value).isEqualTo(Fetched("fetched", START))
  }

  @Test
  fun `update edits the value in place for every collector`() = runTest {
    val store = QueryStore.inMemory(Fetched("a", START))
    val query = query(store)

    assertThat(query.data.first()?.value).isEqualTo("a")
    store.update { it?.copy(value = "b") }
    assertThat(store.data.value).isEqualTo(Fetched("b", START))
    assertThat(query.data.first()?.value).isEqualTo("b")
    // Fresh the whole time, so nothing fetched
    assertThat(fetches).isEqualTo(0)
  }

  @Test
  fun `a fetch already in flight replaces a local edit`() = runTest {
    gate = CompletableDeferred()
    val store = QueryStore.inMemory<String>()
    val query = query(store)

    val fetch = query.requestFetch(force = true)
    testScheduler.runCurrent()
    store.update { Fetched("local", START) }
    gate.complete(Unit)
    fetch.await()
    assertThat(store.data.value).isEqualTo(Fetched("fetched", START))
  }

  @Test
  fun `invalidate keeps the value and clear removes it`() = runTest {
    val store = QueryStore.inMemory(Fetched("a", START))

    store.invalidate()
    assertThat(store.data.value).isEqualTo(Fetched("a", QueryStore.INVALIDATED))
    store.clear()
    assertThat(store.data.value).isEqualTo(null)
    // Nothing to mark stale once empty
    store.invalidate()
    assertThat(store.data.value).isEqualTo(null)
  }

  private companion object {
    val START = Instant.parse("2026-06-10T12:00:00Z")
  }
}
