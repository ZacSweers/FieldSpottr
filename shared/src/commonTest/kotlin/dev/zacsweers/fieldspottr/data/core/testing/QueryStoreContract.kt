// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core.testing

import app.cash.turbine.test
import dev.zacsweers.fieldspottr.data.core.Fetched
import dev.zacsweers.fieldspottr.data.core.QueryStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
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

  private companion object {
    val FETCHED = Instant.parse("2026-06-10T12:00:00Z")
    val LATER = Instant.parse("2026-06-10T13:30:00.250Z")
  }
}
