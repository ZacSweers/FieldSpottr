// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.containsExactly
import kotlin.test.Test
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/** A wrapper built with `by` delegation that follows the [QueryStore] KDoc sees every write. */
class QueryStoreDelegationTest {
  /** Counts the writes and clears that reach it, then passes them on to [delegate]. */
  private class Counting<T>(private val delegate: QueryStore<T>) : QueryStore<T> by delegate {
    val calls = mutableListOf<String>()

    override suspend fun write(fetched: Fetched<T>) {
      calls += "write"
      delegate.write(fetched)
    }

    override suspend fun writeFetched(fetched: Fetched<T>, keepsStored: KeepsStored) {
      calls += "writeFetched"
      delegate.writeFetched(fetched, keepsStored)
    }

    override suspend fun clear() {
      calls += "clear"
      delegate.clear()
    }

    override suspend fun clearFetched(keepsStored: KeepsStored) {
      calls += "clearFetched"
      delegate.clearFetched(keepsStored)
    }
  }

  @Test
  fun `every write and clear form reaches a wrapper that overrides the four members`() = runTest {
    val store = Counting(QueryStore.inMemory<String>())

    store.write(Fetched("a", START))
    store.write("b", START)
    store.writeFetched(Fetched("c", START)) { _, _ -> false }
    store.writeFetched("d", START) { _, _ -> false }
    store.clear()
    store.clearFetched { _, _ -> false }

    assertThat(store.calls)
      .containsExactly("write", "write", "writeFetched", "writeFetched", "clear", "clearFetched")
  }

  private companion object {
    val START: Instant = Instant.parse("2026-06-10T12:00:00Z")
  }
}
