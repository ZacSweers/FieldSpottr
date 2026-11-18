// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isTrue
import dev.zacsweers.fieldspottr.data.core.testing.TestFailures
import dev.zacsweers.fieldspottr.data.core.testing.TestSend
import dev.zacsweers.fieldspottr.data.core.testing.assertCancelled
import dev.zacsweers.fieldspottr.data.core.testing.assertPending
import dev.zacsweers.fieldspottr.data.core.testing.awaitSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.discardDirectly
import dev.zacsweers.fieldspottr.data.core.testing.insertNew
import dev.zacsweers.fieldspottr.data.core.testing.testDataScope
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.concurrent.atomics.update
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
    val send = TestSend<String>()
    val rows = OutboxKey<String>("store")
    val data =
      testDataScope(online = false, senders = setOf(rows.sender(store = store, send = send)))
    val outbox = data.outbox(rows)
    val handles = List(ROWS) { outbox.enqueue("p$it", key = "k") }
    handles.forEach { it.stored.awaitSucceeded() }

    data.online.value = true
    handles.forEach { it.awaitSucceeded() }

    assertThat(store.entries().first()).isEmpty()
    // Enqueuing and draining each row reads a few heads and one row for its handle
    assertThat(store.delivered.load()).isLessThanOrEqualTo(10 * ROWS)
    // The watchers' heads and changes, and a few handles' row reads at a time, however many rows
    // wait
    assertThat(store.maxLive.load()).isLessThanOrEqualTo(6)
    data.assertNoFailures()
  }

  @Test
  fun `a row another writer discards deep in a backlog is read alone`() = runTest {
    val store = CountingOutboxStore(inMemoryOutboxStore<String>())
    val rows = OutboxKey<String>("store")
    val data =
      testDataScope(online = false, senders = setOf(rows.sender(store = store, send = TestSend())))
    val outbox = data.outbox(rows)
    val handles = List(ROWS) { outbox.enqueue("p$it", key = "k") }
    handles.forEach { it.stored.awaitSucceeded() }
    testScheduler.runCurrent()
    val last = handles.last()
    val before = store.delivered.load()

    store.discardDirectly(last.id)
    testScheduler.runCurrent()

    last.assertCancelled()
    handles.first().assertPending()
    // The discard's own read, the change that names the row, and one read of that row to settle it,
    // twice at most: no row ahead of it in the backlog
    assertThat(store.delivered.load() - before).isLessThanOrEqualTo(6)
    data.assertNoFailures()
  }

  @Test
  fun `draining one key with no handle reads its owed rows once`() = runTest {
    val store = CountingOutboxStore(inMemoryOutboxStore<String>())
    val ids = List(ROWS) { store.insertNew("k", "p$it") }
    val failures = TestFailures()
    // The clock stands still, so every send reads the same time
    val frozen =
      object : Clock {
        override fun now(): Instant = START
      }
    // No handle is attached, so every row the drain sends is owed
    DataScope.Factory(onFailure = failures, online = MutableStateFlow(true), clock = frozen)
      .create(
        backgroundScope,
        setOf(OutboxKey<String>("store").sender(store = store, send = TestSend())),
      )

    store.entries().first { list -> list.all { it.status == OutboxStatus.Sent } }

    // One read before the pass's first send, however many rows the pass sends
    assertThat(store.owedKeyReads.load()).isLessThanOrEqualTo(2)
    assertThat(store.owedRowsRead.load()).isLessThanOrEqualTo(ROWS)
    val sentAt = ids.map { id -> checkNotNull(store.entry(id).first()?.sentAt) }
    assertThat(sentAt.first()).isEqualTo(START)
    assertThat(sentAt.zipWithNext().all { (earlier, later) -> earlier < later }).isTrue()
    failures.assertNone()
  }

  /**
   * Counts what an outbox reads from [rows]: every row its flows and reads deliver, and the most
   * flows collected at once, and the reads of one key's owed rows with the rows they return.
   */
  private class CountingOutboxStore<E>(private val rows: OutboxStore<E>) : OutboxStore<E> by rows {
    val delivered = AtomicInt(0)
    val maxLive = AtomicInt(0)
    val owedKeyReads = AtomicInt(0)
    val owedRowsRead = AtomicInt(0)
    private val live = AtomicInt(0)

    override fun entries(): Flow<List<OutboxEntry<E>>> = rows.entries().counted { it.size }

    override fun entry(id: EntryId): Flow<OutboxEntry<E>?> = rows.entry(id).counted { 1 }

    override fun heads(): Flow<List<OutboxEntry<E>>> = rows.heads().counted { it.size }

    override fun changes(): Flow<OutboxChange> =
      rows.changes().counted { change ->
        when (change) {
          is OutboxChange.Rows -> change.ids.size
          OutboxChange.Unknown -> 0
        }
      }

    override suspend fun head(key: String): OutboxEntry<E>? =
      rows.head(key)?.also { delivered.update { it + 1 } }

    override suspend fun owed(key: String): List<OutboxEntry<E>> {
      val owed = rows.owed(key)
      owedKeyReads.update { it + 1 }
      owedRowsRead.update { it + owed.size }
      return owed
    }

    private fun <T> Flow<T>.counted(size: (T) -> Int): Flow<T> = onStart {
      val now = live.incrementAndFetch()
      maxLive.update { maxOf(it, now) }
    }
      .onEach { value -> delivered.update { it + size(value) } }
      .onCompletion { live.update { it - 1 } }
  }

  private companion object {
    const val ROWS = 400
    val START: Instant = Instant.parse("2026-06-10T12:00:00Z")
  }
}
