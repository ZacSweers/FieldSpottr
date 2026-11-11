// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core.testing

import app.cash.turbine.test
import dev.zacsweers.fieldspottr.data.core.Fetched
import dev.zacsweers.fieldspottr.data.core.Freshness
import dev.zacsweers.fieldspottr.data.core.QueryStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * The rules every [QueryStore] keeps, so a query behaves the same over any of them. A store
 * implementation runs these by subclassing this in a test source set, like a store over its own
 * file or table. The fetch times have millisecond precision, so a store that keeps epoch
 * milliseconds passes.
 */
abstract class QueryStoreContract {
  /** A new, empty store. */
  protected abstract fun TestScope.newStore(): QueryStore<String>

  @Test
  fun `a new store holds nothing`() = runTest {
    assertNull(newStore().data.first())
  }

  @Test
  fun `a write stores the value and its fetch time`() = runTest {
    val store = newStore()

    store.write("a", FETCHED)
    assertEquals(Fetched("a", FETCHED), store.data.first())
  }

  @Test
  fun `a write replaces what was stored`() = runTest {
    val store = newStore()

    store.write("a", FETCHED)
    store.write("b", LATER)
    assertEquals(Fetched("b", LATER), store.data.first())
  }

  @Test
  fun `a write replaces a value fetched later`() = runTest {
    val store = newStore()

    store.write("a", LATER)
    store.write("b", FETCHED)
    assertEquals(Fetched("b", FETCHED), store.data.first())
  }

  @Test
  fun `invalidate keeps the value and marks it stale`() = runTest {
    val store = newStore()

    store.write("a", FETCHED)
    store.invalidate()
    assertEquals(Fetched("a", QueryStore.INVALIDATED), store.data.first())
  }

  @Test
  fun `invalidate leaves an empty store empty`() = runTest {
    val store = newStore()

    store.invalidate()
    assertNull(store.data.first())
  }

  @Test
  fun `clear removes the value and the store takes writes again`() = runTest {
    val store = newStore()

    store.write("a", FETCHED)
    store.clear()
    assertNull(store.data.first())
    store.write("b", LATER)
    assertEquals(Fetched("b", LATER), store.data.first())
  }

  @Test
  fun `a collector sees each change`() = runTest {
    val store = newStore()

    store.data.test {
      assertNull(awaitItem())
      store.write("a", FETCHED)
      assertEquals(Fetched("a", FETCHED), awaitItem())
      store.invalidate()
      assertEquals(Fetched("a", QueryStore.INVALIDATED), awaitItem())
      store.clear()
      assertNull(awaitItem())
    }
  }

  @Test
  fun `a fetch write fills an empty store without asking`() = runTest {
    val store = newStore()

    store.writeFetched("a", FETCHED) { error("Nothing is stored to keep") }
    assertEquals(Fetched("a", FETCHED), store.data.first())
  }

  @Test
  fun `a fetch write keeps what is stored when asked to`() = runTest {
    val store = newStore()
    val asked = mutableListOf<Instant>()

    store.write("a", LATER)
    store.writeFetched("b", FETCHED) { storedAt -> true.also { asked += storedAt } }
    assertEquals(Fetched("a", LATER), store.data.first())
    assertEquals(LATER, asked.distinct().single())
  }

  @Test
  fun `a fetch write replaces what is stored when not asked to keep it`() = runTest {
    val store = newStore()

    store.write("a", FETCHED)
    store.writeFetched("b", LATER) { false }
    assertEquals(Fetched("b", LATER), store.data.first())
  }

  @Test
  fun `a fetch clear leaves an empty store empty without asking`() = runTest {
    val store = newStore()

    store.clearFetched { error("Nothing is stored to keep") }
    assertNull(store.data.first())
  }

  @Test
  fun `a fetch clear keeps what is stored when asked to`() = runTest {
    val store = newStore()
    val asked = mutableListOf<Instant>()

    store.write("a", LATER)
    store.clearFetched { storedAt -> true.also { asked += storedAt } }
    assertEquals(Fetched("a", LATER), store.data.first())
    assertEquals(LATER, asked.distinct().single())
  }

  @Test
  fun `a fetch clear removes what is stored when not asked to keep it`() = runTest {
    val store = newStore()

    store.write("a", FETCHED)
    store.clearFetched { false }
    assertNull(store.data.first())

    // The store takes writes again
    store.write("b", LATER)
    assertEquals(Fetched("b", LATER), store.data.first())
  }

  @Test
  fun `an older fetch never replaces what a newer fetch stored meanwhile`() = runTest {
    val data = testDataScope(clock = testClock(FETCHED))
    val store = newStore()
    val olderRead = CompletableDeferred<Unit>()
    val releaseOlder = CompletableDeferred<Unit>()
    val freshness = Freshness.staleAfter(1.hours)
    val older =
      data.query(store, freshness) {
        olderRead.complete(Unit)
        releaseOlder.await()
        "older"
      }
    val newer = data.query(store, freshness) { "newer" }

    // The older fetch reads the server first, and the newer one stores its value meanwhile
    val first = older.requestFetch(force = true)
    olderRead.await()
    data.clock.shift(1.minutes)
    newer.requestFetch(force = true).awaitSucceeded()
    releaseOlder.complete(Unit)
    first.awaitSucceeded()

    assertEquals(Fetched("newer", FETCHED + 1.minutes), store.data.first())
  }

  private companion object {
    val FETCHED = Instant.parse("2026-06-10T12:00:00Z")
    val LATER = Instant.parse("2026-06-10T13:30:00.250Z")
  }
}
