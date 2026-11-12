// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core.testing

import dev.zacsweers.fieldspottr.data.core.EntryId
import dev.zacsweers.fieldspottr.data.core.OutboxStatus
import dev.zacsweers.fieldspottr.data.core.OutboxStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
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

  /**
   * Stores a Queued row on [key] in [store] whose payload the store cannot decode, like one written
   * before its payload class changed, and returns its id. Null for a store that keeps payloads as
   * objects, which skips the checks for such rows.
   */
  protected open suspend fun TestScope.insertUnreadable(
    store: OutboxStore<String>,
    key: String,
  ): EntryId? = null

  @Test
  fun `insert queues rows oldest first`() = runTest {
    val store = newStore(1.minutes)

    val a = store.insertNew("k", "a")
    val b = store.insertNew("k", "b")

    assertNotEquals(b, a)
    assertEquals(listOf("a", "b"), store.entries().first().map { it.payload })
    assertEquals(OutboxStatus.Queued, store.entry(a).first()?.status)
  }

  @Test
  fun `update writes only from the expected status`() = runTest {
    val store = newStore(1.minutes)
    val id = store.insertNew("k", "a")
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
    val id = store.insertNew("k", "a")
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
        List(20) { i -> async { store.insertNew("key$i", "payload$i") } }.awaitAll()
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
  fun `heads are each key's oldest queued or sending row`() = runTest {
    val store = newStore(1.minutes)
    val a = store.insertNew("k", "a")
    store.insertNew("k", "b")
    store.insertNew("j", "c")
    val rowA = checkNotNull(store.entry(a).first())

    // A failed row waits for a retry, so the next row on its key is the head
    assertTrue(store.update(rowA.copy(status = OutboxStatus.Failed("boom")), OutboxStatus.Queued))

    assertEquals(setOf("b", "c"), store.heads().first().map { it.payload }.toSet())
    assertEquals("b", store.head("k")?.payload)
    assertEquals("c", store.head("j")?.payload)
    assertNull(store.head("none"))

    // A sending row is still the head, and a sent one leaves its key without one
    val rowC = checkNotNull(store.head("j"))
    assertTrue(store.update(rowC.copy(status = OutboxStatus.Sending), OutboxStatus.Queued))
    assertEquals(OutboxStatus.Sending, store.head("j")?.status)
    assertTrue(store.update(rowC.copy(status = OutboxStatus.Sent), OutboxStatus.Sending))
    assertNull(store.head("j"))
    assertEquals(listOf("b"), store.heads().first().map { it.payload })
  }

  @Test
  fun `a row that cannot be decoded is left out and blocks nothing`() = runTest {
    val store = newStore(1.minutes)
    val unreadable = insertUnreadable(store, "k") ?: return@runTest
    val readable = store.insertNew("k", "a")
    store.insertNew("j", "b")

    assertEquals(listOf("a", "b"), store.entries().first().map { it.payload })
    assertNull(store.entry(unreadable).first())
    // It never sends, so the next row on its key is the head
    assertEquals(readable, store.head("k")?.id)
    assertEquals(setOf("a", "b"), store.heads().first().map { it.payload }.toSet())
  }

  @Test
  fun `a row that cannot be decoded is handed on once`() = runTest {
    val store = newStore(1.minutes)
    val unreadable = insertUnreadable(store, "k") ?: return@runTest
    store.insertNew("k", "a")
    // Reads meet the row before anything listens
    store.entries().first()
    store.head("k")

    val reported = ArrayList<EntryId>()
    val listening = store.onUndecodable { id, _ -> reported += id }
    store.entries().first()
    store.heads().first()
    assertNull(store.entry(unreadable).first())
    assertEquals(listOf(unreadable), reported)

    // Once per store, so a later listener hears nothing more of it
    listening.close()
    val later = ArrayList<EntryId>()
    store.onUndecodable { id, _ -> later += id }.use { store.entries().first() }
    assertEquals(emptyList(), later)
  }

  @Test
  fun `a listener that throws does not fail the read that met the row`() = runTest {
    val store = newStore(1.minutes)
    val unreadable = insertUnreadable(store, "k") ?: return@runTest
    store.insertNew("k", "a")
    val heard = ArrayList<EntryId>()
    store.onUndecodable { id, _ ->
      heard += id
      throw IllegalStateException("The failure handler rethrows")
    }

    assertEquals(listOf("a"), store.entries().first().map { it.payload })
    assertEquals("a", store.head("k")?.payload)
    assertEquals(listOf("a"), store.heads().first().map { it.payload })
    assertNull(store.entry(unreadable).first())
    assertEquals(listOf(unreadable), heard)
  }

  @Test
  fun `a settled row stays readable until it expires and a write prunes it`() = runTest {
    val store = newStore(1.minutes)
    val id = store.insertNew("k", "a")
    val row = checkNotNull(store.entry(id).first())

    store.update(row.copy(status = OutboxStatus.Sent, committed = true), OutboxStatus.Queued)
    assertEquals(emptyList(), store.entries().first())
    assertEquals(OutboxStatus.Sent, store.entry(id).first()?.status)

    advanceStoreTime(2.minutes)
    store.insertNew("k", "b")
    assertNull(store.entry(id).first())
  }

  @Test
  fun `insert keeps the id it is given and a stored id throws and stores nothing`() = runTest {
    val store = newStore(1.minutes)
    val id = EntryId("given")

    store.insert(id, "k", "a")
    assertEquals("a", store.entry(id).first()?.payload)

    assertFails { store.insert(id, "k", "b") }
    assertEquals(listOf("a"), store.entries().first().map { it.payload })
  }

  @Test
  fun `committed and sentAt and reply round trip`() = runTest {
    val store = newStore(1.minutes)
    val id = store.insertNew("k", "a")
    val row = checkNotNull(store.entry(id).first())
    val sent =
      row.copy(
        status = OutboxStatus.Sent,
        sentAt = Instant.fromEpochMilliseconds(1_000),
        reply = "[\"answer\"]",
      )

    assertTrue(store.update(sent, OutboxStatus.Queued))
    assertEquals(sent, store.entry(id).first())

    val committed = sent.copy(committed = true, reply = null)
    assertTrue(store.update(committed, OutboxStatus.Sent))
    assertEquals(committed, store.entry(id).first())
  }

  @Test
  fun `a sent row that is not committed stays listed and owed and is never pruned`() = runTest {
    val store = newStore(1.minutes)
    val id = store.insertNew("k", "a")
    val row = checkNotNull(store.entry(id).first())
    store.update(row.copy(status = OutboxStatus.Sent), OutboxStatus.Queued)

    advanceStoreTime(2.minutes)
    store.insertNew("k", "b")

    assertEquals(listOf(id), store.owed().map { it.id })
    assertEquals(listOf("a", "b"), store.entries().first().map { it.payload })
    assertNull(store.head("k")?.takeIf { it.id == id })
    assertEquals(OutboxStatus.Sent, store.entry(id).first()?.status)
  }

  @Test
  fun `owed rows of one key are read without the other keys`() = runTest {
    val store = newStore(1.minutes)
    val ids =
      listOf("k" to "a", "j" to "b", "k" to "c", "k" to "d").map { (key, payload) ->
        store.insertNew(key, payload)
      }
    for (id in ids.take(3)) {
      val row = checkNotNull(store.entry(id).first())
      store.update(row.copy(status = OutboxStatus.Sent), OutboxStatus.Queued)
    }

    // Oldest first, and the queued d is not owed
    assertEquals(listOf("a", "c"), store.owed("k").map { it.payload })
    assertEquals(listOf("b"), store.owed("j").map { it.payload })
    assertEquals(emptyList(), store.owed("none"))
  }

  @Test
  fun `a failed row whose revert has not run is owed until it is reverted`() = runTest {
    val store = newStore(1.minutes)
    val id = store.insertNew("k", "a")
    store.insertNew("k", "b")
    val row = checkNotNull(store.entry(id).first())
    val failed = row.copy(status = OutboxStatus.Failed("boom"))
    store.update(failed, OutboxStatus.Queued)

    assertEquals(listOf(id), store.owed().map { it.id })

    store.update(failed.copy(reverted = true), failed.status)
    assertEquals(emptyList(), store.owed())
  }

  @Test
  fun `a row gets its settled time only once it is done`() = runTest {
    val store = newStore(1.minutes)
    val id = store.insertNew("k", "a")
    val row = checkNotNull(store.entry(id).first())
    store.update(row.copy(status = OutboxStatus.Sent), OutboxStatus.Queued)
    advanceStoreTime(2.minutes)

    // Committed now, so its settled time starts now and the row stays readable for a while
    store.update(row.copy(status = OutboxStatus.Sent, committed = true), OutboxStatus.Sent)
    store.insertNew("k", "b")
    assertEquals(OutboxStatus.Sent, store.entry(id).first()?.status)
    assertEquals(listOf("b"), store.entries().first().map { it.payload })

    advanceStoreTime(2.minutes)
    store.insertNew("k", "c")
    assertNull(store.entry(id).first())
  }
}
