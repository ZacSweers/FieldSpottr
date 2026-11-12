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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class InMemoryQueryStoreTest {

  private val fetch = TestFetch { "fetched" }

  private fun TestScope.query(store: QueryStore<String>): DataQuery<String> =
    testDataScope(clock = testClock(START))
      .query(store = store, freshness = Freshness.staleAfter(1.hours), fetch = fetch)

  @Test
  fun `a stale seed shows at once and still fetches`() = runTest {
    val store = QueryStore.inMemory(Fetched("cached", fetchedAt = null))
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
    assertThat(store.data.value).isEqualTo(Fetched("a", START, invalidated = true))
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

  @Test
  fun `a keyed store holds every collected key over its cap and trims once one leaves`() = runTest {
    val stores = QueryStore.inMemoryByKey<String, String>(maxEntries = 1)
    val a = backgroundScope.launch { stores("a").data.collect {} }
    val b = backgroundScope.launch { stores("b").data.collect {} }
    runCurrent()

    stores("a").write("a", START)
    stores("b").write("b", START + 1.hours)
    assertThat(stores.size).isEqualTo(2)
    assertThat(stores("a").data.value).isEqualTo(Fetched("a", START))

    // A is the older and nobody collects it now, so it goes
    a.cancel()
    runCurrent()
    assertThat(stores.size).isEqualTo(1)
    assertThat(stores("a").data.value).isEqualTo(null)
    assertThat(stores("b").data.value).isEqualTo(Fetched("b", START + 1.hours))
    b.cancel()
  }

  @Test
  fun `a keyed store drops a key nobody collects as before`() = runTest {
    val stores = QueryStore.inMemoryByKey<String, String>(maxEntries = 1)
    val b = backgroundScope.launch { stores("b").data.collect {} }
    runCurrent()

    stores("a").write("a", START)
    stores("b").write("b", START + 1.hours)
    // B is collected and a is not, so a goes although it is the only key over the cap
    assertThat(stores.size).isEqualTo(1)
    assertThat(stores("a").data.value).isEqualTo(null)

    stores("c").write("c", START + 2.hours)
    // C was just written, and b is collected, so neither goes
    assertThat(stores.size).isEqualTo(2)
    b.cancel()
    runCurrent()
    // B is the older of the two that nobody collects now
    assertThat(stores("b").data.value).isEqualTo(null)
    assertThat(stores("c").data.value).isEqualTo(Fetched("c", START + 2.hours))
  }

  @Test
  fun `a keyed store that nobody collects drops the oldest key on each write`() = runTest {
    val stores = QueryStore.inMemoryByKey<String, String>(maxEntries = 2)

    stores("a").write("a", START)
    stores("b").write("b", START + 1.hours)
    stores("c").write("c", START + 2.hours)

    assertThat(stores.size).isEqualTo(2)
    assertThat(stores("a").data.value).isEqualTo(null)
  }

  @Test
  fun `a reader that takes one value leaves its key unpinned`() = runTest {
    val stores = QueryStore.inMemoryByKey<String, String>(maxEntries = 1)
    stores("a").write("a", START)

    assertThat(stores("a").data.first()).isEqualTo(Fetched("a", START))
    stores("b").write("b", START + 1.hours)

    assertThat(stores.size).isEqualTo(1)
    assertThat(stores("a").data.value).isEqualTo(null)
  }

  private companion object {
    val START = Instant.parse("2026-06-10T12:00:00Z")
  }
}
