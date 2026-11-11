// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isLessThanOrEqualTo
import dev.zacsweers.fieldspottr.data.core.testing.TestSend
import dev.zacsweers.fieldspottr.data.core.testing.awaitSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.testDataScope
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.concurrent.atomics.update
import kotlin.test.Test
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.runTest

/** How much an outbox reads from its store as its backlog grows. */
@OptIn(ExperimentalAtomicApi::class)
class OutboxScaleTest {

  @Test
  fun `draining one key reads a bounded number of rows per row`() = runTest {
    val store = CountingOutboxStore(inMemoryOutboxStore<String>())
    val data = testDataScope(online = false)
    val send = TestSend<String>()
    val outbox = data.outbox("store", store, send = send)
    val handles = List(ROWS) { outbox.enqueue("p$it", key = "k") }
    handles.forEach { it.stored.awaitSucceeded() }

    data.online.value = true
    handles.forEach { it.awaitSucceeded() }

    assertThat(store.entries().first()).isEmpty()
    // Enqueuing and draining each row reads a few heads and one row for its handle
    assertThat(store.delivered.load()).isLessThanOrEqualTo(10 * ROWS)
    // The watcher's heads and a few handles' row reads at a time, however many rows wait
    assertThat(store.maxLive.load()).isLessThanOrEqualTo(6)
    data.assertNoFailures()
  }

  /**
   * Counts what an outbox reads from [rows]: every row its flows and reads deliver, and the most
   * flows collected at once.
   */
  private class CountingOutboxStore<E>(private val rows: OutboxStore<E>) : OutboxStore<E> by rows {
    val delivered = AtomicInt(0)
    val maxLive = AtomicInt(0)
    private val live = AtomicInt(0)

    override fun entries(): Flow<List<OutboxEntry<E>>> = rows.entries().counted { it.size }

    override fun entry(id: EntryId): Flow<OutboxEntry<E>?> = rows.entry(id).counted { 1 }

    override fun heads(): Flow<List<OutboxEntry<E>>> = rows.heads().counted { it.size }

    override suspend fun head(key: String): OutboxEntry<E>? =
      rows.head(key)?.also { delivered.incrementAndFetch() }

    private fun <T> Flow<T>.counted(size: (T) -> Int): Flow<T> = onStart {
      val now = live.incrementAndFetch()
      maxLive.update { maxOf(it, now) }
    }
      .onEach { value -> delivered.update { it + size(value) } }
      .onCompletion { live.decrementAndFetch() }
  }

  private companion object {
    const val ROWS = 400
  }
}
