// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core.impl

import dev.zacsweers.fieldspottr.data.core.EntryId
import dev.zacsweers.fieldspottr.data.core.OutboxEntry
import dev.zacsweers.fieldspottr.data.core.OutboxStatus
import dev.zacsweers.fieldspottr.data.core.OutboxStore
import dev.zacsweers.fieldspottr.data.core.OutboxStores
import dev.zacsweers.fieldspottr.data.core.isSettled
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.collections.immutable.persistentHashMapOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.KSerializer

/**
 * Rows in memory, lost with the process. A settled row stays observable for [keepSettledFor], so a
 * handle that re-attaches soon after still sees its outcome, and the next write prunes it.
 */
fun <E> inMemoryOutboxStore(
  keepSettledFor: Duration = 1.minutes,
  time: TimeSource = TimeSource.Monotonic,
): OutboxStore<E> = InMemoryOutboxStore(keepSettledFor, time)

/**
 * Every outbox in memory, one store per name for as long as this set lives. The default, and what
 * tests and previews use. Rows keep each payload as the object itself, so these stores never use
 * the serializer.
 */
@OptIn(ExperimentalAtomicApi::class)
class InMemoryOutboxStores : OutboxStores {
  // Compare-and-set only. Nothing observes it.
  private val opened = AtomicReference(persistentHashMapOf<String, OutboxStore<*>>())

  override fun <E> open(name: String, serializer: KSerializer<E>): OutboxStore<E> {
    while (true) {
      val current = opened.load()
      @Suppress("UNCHECKED_CAST") val existing = current[name] as OutboxStore<E>?
      if (existing != null) {
        return existing
      }
      // An unused in-memory store holds nothing, so the copy that loses this race is simply dropped
      val created = inMemoryOutboxStore<E>()
      if (opened.compareAndSet(current, current.putting(name, created))) {
        return created
      }
    }
  }
}

@OptIn(ExperimentalUuidApi::class)
private class InMemoryOutboxStore<E>(
  private val keepSettledFor: Duration,
  private val time: TimeSource,
) : OutboxStore<E> {
  // Insertion order is send order, and the map keeps it
  private val rows = MutableStateFlow(persistentMapOf<EntryId, Row<E>>())

  override suspend fun insert(key: String, payload: E): EntryId {
    val id = EntryId(Uuid.random().toString())
    val row = Row(OutboxEntry(id, key, payload, OutboxStatus.Queued), settledAt = null)
    rows.update { current -> current.pruned().putting(id, row) }
    return id
  }

  override suspend fun update(entry: OutboxEntry<E>, expected: OutboxStatus): Boolean {
    var written = false
    rows.update { current ->
      val row = current[entry.id]
      written = row != null && row.entry.status == expected
      if (row == null || !written) {
        current
      } else {
        val updated =
          row.entry.copy(
            status = entry.status,
            attempts = entry.attempts,
            lastError = entry.lastError,
            reverted = entry.reverted,
            retryAt = entry.retryAt,
          )
        val settledAt =
          if (entry.status.isSettled) {
            time.markNow()
          } else {
            null
          }
        current.pruned().putting(entry.id, Row(updated, settledAt))
      }
    }
    return written
  }

  override fun entries(): Flow<List<OutboxEntry<E>>> =
    rows
      .map { current -> current.values.map { it.entry }.filterNot { it.status.isSettled } }
      .distinctUntilChanged()

  override fun entry(id: EntryId): Flow<OutboxEntry<E>?> =
    rows.map { current -> current[id]?.takeUnless { it.isExpired() }?.entry }.distinctUntilChanged()

  private fun Map<EntryId, Row<E>>.pruned() = filterValues { !it.isExpired() }.toPersistentMap()

  private fun Row<E>.isExpired(): Boolean =
    settledAt != null && settledAt.elapsedNow() >= keepSettledFor

  private class Row<E>(val entry: OutboxEntry<E>, val settledAt: TimeMark?)
}
