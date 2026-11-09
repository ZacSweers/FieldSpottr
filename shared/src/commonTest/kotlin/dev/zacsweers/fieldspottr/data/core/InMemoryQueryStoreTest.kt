// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.isEqualTo
import dev.zacsweers.fieldspottr.data.core.testing.TestFetch
import dev.zacsweers.fieldspottr.data.core.testing.testClock
import dev.zacsweers.fieldspottr.data.core.testing.testDataScope
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

class InMemoryQueryStoreTest {

  private val fetch = TestFetch { "fetched" }

  private fun TestScope.query(store: QueryStore<String>): DataQuery<String> =
    testDataScope(clock = testClock(START))
      .query(store = store, fetch = fetch, freshness = Freshness.staleAfter(1.hours))

  @Test
  fun `a stale seed shows at once and still fetches`() = runTest {
    val store = QueryStore.inMemory(Fetched("cached", QueryStore.INVALIDATED))
    // Readable without collecting
    assertThat(store.data.value?.value).isEqualTo("cached")
    fetch.hold()
    val query = query(store)

    query.data.values().test {
      // The seed is on screen while the fetch waits
      assertThat(awaitItem()).isEqualTo("cached")
      fetch.release()
      assertThat(awaitItem()).isEqualTo("fetched")
    }
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
    assertThat(fetch.callCount).isEqualTo(0)
  }

  @Test
  fun `a fetch already in flight replaces a local edit`() = runTest {
    fetch.hold()
    val store = QueryStore.inMemory<String>()
    val query = query(store)

    val request = query.requestFetch(force = true)
    testScheduler.runCurrent()
    store.update { Fetched("local", START) }
    fetch.release()
    request.await()
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

  @Test
  fun `stores for one key share its value and read it without collecting`() = runTest {
    val stores = QueryStore.inMemoryByKey<String, String>()

    stores("a").write("a", START)
    assertThat(stores("a").data.value).isEqualTo(Fetched("a", START))
    stores("a").update { it?.copy(value = "edited") }
    assertThat(stores("a").data.value?.value).isEqualTo("edited")
    assertThat(stores("b").data.value).isEqualTo(null)
  }

  @Test
  fun `an edit that stores a key's value counts toward the cap`() = runTest {
    val stores = QueryStore.inMemoryByKey<String, String>(maxEntries = 1)
    stores("a").write("a", START)

    stores("b").update { Fetched("b", START + 1.hours) }
    // a was fetched longest ago, so storing b drops it
    assertThat(stores("a").data.value).isEqualTo(null)
    assertThat(stores("b").data.value?.value).isEqualTo("b")
  }

  private companion object {
    val START = Instant.parse("2026-06-10T12:00:00Z")
  }
}
