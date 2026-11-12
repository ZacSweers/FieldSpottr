// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core.testing

import dev.zacsweers.fieldspottr.data.core.EntryId
import dev.zacsweers.fieldspottr.data.core.InMemoryOutboxStores
import dev.zacsweers.fieldspottr.data.core.OutboxEntry
import dev.zacsweers.fieldspottr.data.core.OutboxStatus
import dev.zacsweers.fieldspottr.data.core.OutboxStore
import dev.zacsweers.fieldspottr.data.core.OutboxStores
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * In-memory outbox stores that make the row list and the store's calls race, like a slow database.
 *
 * An insert waits [insertStartsAfter] before it stores the row and [insertReturnsAfter] after, so
 * the list can show a row before its insert returns. A status write waits [updateTakes] first. Each
 * row list arrives [rowsLag] late, so the list can lag the store. With [firstListOnly], the list
 * emits once and then never again, like a list that never catches up. With [decodeEachRead], each
 * list holds new payload objects, as a durable store that decodes on each read does. While
 * [failEntryReads] is set, each read of a single row throws, like a disk error. While
 * [failCommittedWrite] is set, a write that marks a Sent row committed throws and writes nothing,
 * like a process that dies between a commit and its row write.
 */
class LaggingOutboxStores(
  private val insertStartsAfter: Duration = Duration.ZERO,
  private val insertReturnsAfter: Duration = Duration.ZERO,
  private val updateTakes: Duration = Duration.ZERO,
  private val rowsLag: Duration = Duration.ZERO,
  private val firstListOnly: Boolean = false,
  private val decodeEachRead: Boolean = false,
) : OutboxStores {
  /** The stores without the delays, to read what is stored now. */
  val delegate = InMemoryOutboxStores()

  @Volatile var failEntryReads = false

  @Volatile var failCommittedWrite = false

  override fun <E> open(name: String, serializer: KSerializer<E>): OutboxStore<E> {
    val rows = delegate.open(name, serializer)
    return object : OutboxStore<E> by rows {
      override suspend fun insert(id: EntryId, key: String, payload: E) {
        delay(insertStartsAfter)
        rows.insert(id, key, payload)
        delay(insertReturnsAfter)
      }

      override suspend fun update(entry: OutboxEntry<E>, expected: OutboxStatus): Boolean {
        delay(updateTakes)
        check(!failCommittedWrite || entry.status != OutboxStatus.Sent || !entry.committed) {
          "The process died before the row was marked committed"
        }
        return rows.update(entry, expected)
      }

      override fun entry(id: EntryId): Flow<OutboxEntry<E>?> =
        if (failEntryReads) {
          flow { throw IllegalStateException("Disk error") }
        } else {
          rows.entry(id)
        }

      override fun entries(): Flow<List<OutboxEntry<E>>> {
        var lists = rows.entries()
        if (rowsLag != Duration.ZERO) {
          lists = lists.onEach { delay(rowsLag) }
        }
        if (firstListOnly) {
          val first = lists.take(1)
          lists = flow {
            first.collect { emit(it) }
            // Never another list
            awaitCancellation()
          }
        }
        return if (decodeEachRead) {
          lists.map { list ->
            list.map {
              val copy = Json.encodeToString(serializer, it.payload)
              it.copy(payload = Json.decodeFromString(serializer, copy))
            }
          }
        } else {
          lists
        }
      }
    }
  }

  override suspend fun clearAll() = delegate.clearAll()
}
