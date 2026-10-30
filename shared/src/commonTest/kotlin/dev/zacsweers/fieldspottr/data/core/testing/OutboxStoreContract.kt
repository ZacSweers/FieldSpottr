// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core.testing

import dev.zacsweers.fieldspottr.data.core.OutboxStatus
import dev.zacsweers.fieldspottr.data.core.OutboxStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * The rules every [OutboxStore] keeps, so an outbox behaves the same over any of them. A store
 * implementation runs these by subclassing this in a test source set, like a store over its own
 * table or file.
 */
abstract class OutboxStoreContract {
  /** A new, empty store that keeps settled rows readable for [keepSettledFor]. */
  protected abstract fun TestScope.newStore(keepSettledFor: Duration): OutboxStore<String>

  /** Moves the store's notion of time forward by [duration], for settled rows to expire. */
  protected abstract fun advanceStoreTime(duration: Duration)

  @Test
  fun `insert queues rows oldest first`() = runTest {
    val store = newStore(1.minutes)

    val a = store.insert("k", "a")
    val b = store.insert("k", "b")

    assertNotEquals(b, a)
    assertEquals(listOf("a", "b"), store.entries().first().map { it.payload })
    assertEquals(OutboxStatus.Queued, store.entry(a).first()?.status)
  }

  @Test
  fun `update writes only from the expected status`() = runTest {
    val store = newStore(1.minutes)
    val id = store.insert("k", "a")
    val row = checkNotNull(store.entry(id).first())

    assertTrue(store.update(row.copy(status = OutboxStatus.Sending), OutboxStatus.Queued))
    assertFalse(store.update(row.copy(status = OutboxStatus.Sent), OutboxStatus.Queued))
    assertEquals(OutboxStatus.Sending, store.entry(id).first()?.status)

    val failed =
      row.copy(
        status = OutboxStatus.Failed("boom"),
        attempts = 2,
        lastError = "boom",
        reverted = true,
      )
    assertTrue(store.update(failed, OutboxStatus.Sending))
    assertEquals(failed, store.entry(id).first())
  }

  @Test
  fun `update compares a failed row's cause too`() = runTest {
    val store = newStore(1.minutes)
    val id = store.insert("k", "a")
    val row = checkNotNull(store.entry(id).first())
    assertTrue(store.update(row.copy(status = OutboxStatus.Failed("first")), OutboxStatus.Queued))
    assertTrue(
      store.update(row.copy(status = OutboxStatus.Failed("second")), OutboxStatus.Failed("first"))
    )

    // A stale cause no longer matches the row
    val discarded = row.copy(status = OutboxStatus.Discarded)
    assertFalse(store.update(discarded, OutboxStatus.Failed("first")))
    assertEquals(OutboxStatus.Failed("second"), store.entry(id).first()?.status)
  }

  @Test
  fun `concurrent inserts and updates all land`() = runTest {
    val store = newStore(1.minutes)

    // On other threads, the way an outbox drains several keys at once
    val ids =
      withContext(Dispatchers.Default) {
        List(20) { i -> async { store.insert("key$i", "payload$i") } }.awaitAll()
      }
    val rows = ids.map { checkNotNull(store.entry(it).first()) }
    val updated =
      withContext(Dispatchers.Default) {
        rows
          .map { row ->
            async { store.update(row.copy(status = OutboxStatus.Sending), OutboxStatus.Queued) }
          }
          .awaitAll()
      }

    assertTrue(updated.all { it })
    val entries = store.entries().first()
    assertEquals(List(20) { "payload$it" }.toSet(), entries.map { it.payload }.toSet())
    assertTrue(entries.all { it.status == OutboxStatus.Sending })
  }

  @Test
  fun `a settled row stays readable until it expires and a write prunes it`() = runTest {
    val store = newStore(1.minutes)
    val id = store.insert("k", "a")
    val row = checkNotNull(store.entry(id).first())

    store.update(row.copy(status = OutboxStatus.Sent), OutboxStatus.Queued)
    assertEquals(emptyList(), store.entries().first())
    assertEquals(OutboxStatus.Sent, store.entry(id).first()?.status)

    advanceStoreTime(2.minutes)
    store.insert("k", "b")
    assertNull(store.entry(id).first())
  }
}
