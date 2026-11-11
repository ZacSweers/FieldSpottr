// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentHashMapOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.KSerializer

/**
 * Rows in memory, lost with the process. A settled row stays observable for [keepSettledFor], so a
 * handle that re-attaches soon after still sees its outcome, and the next write prunes it. Payloads
 * are kept as objects, so no row is ever unreadable.
 */
internal fun <E> inMemoryOutboxStore(
  keepSettledFor: Duration = 1.minutes,
  time: TimeSource = TimeSource.Monotonic,
): OutboxStore<E> = InMemoryOutboxStore(keepSettledFor, time)

/**
 * An [OutboxStores] that keeps every outbox in memory, with one store per name for as long as this
 * set lives. It is the default store set, and the one tests and previews use. Rows hold each
 * payload as the object itself, so these stores use the serializer only to check the payload type.
 */
@OptIn(ExperimentalAtomicApi::class)
class InMemoryOutboxStores : OutboxStores {
  // Updated by compare-and-set only. Nothing observes it, so it needs no StateFlow.
  private val opened =
    AtomicReference(persistentHashMapOf<String, OpenedStore<InMemoryOutboxStore<*>>>())

  override fun <E> open(name: String, serializer: KSerializer<E>): OutboxStore<E> {
    while (true) {
      val current = opened.load()
      current[name]?.let {
        @Suppress("UNCHECKED_CAST")
        return it.checked(name, serializer) as OutboxStore<E>
      }
      // An unused in-memory store holds nothing, so the copy that loses this race is simply dropped
      val created = InMemoryOutboxStore<E>(1.minutes, TimeSource.Monotonic)
      val entry = OpenedStore<InMemoryOutboxStore<*>>(serializer.descriptor, created)
      if (opened.compareAndSet(current, current.putting(name, entry))) {
        return created
      }
    }
  }

  // Nothing but the stores this set hands out holds rows
  override suspend fun clearAll() {
    for (entry in opened.load().values) {
      entry.store.clear()
    }
  }
}

@OptIn(ExperimentalUuidApi::class)
private class InMemoryOutboxStore<E>(
  private val keepSettledFor: Duration,
  private val time: TimeSource,
) : OutboxStore<E> {
  private val rows = MutableStateFlow(Rows<E>())

  override suspend fun insert(key: String, payload: E): EntryId {
    val id = EntryId(Uuid.random().toString())
    val row = OutboxEntry(id, key, payload, OutboxStatus.Queued)
    rows.update { current ->
      val pruned = current.pruned()
      Rows(
        all = pruned.all.putting(id, Row(row, settledAt = null)),
        byKey = pruned.byKey.putting(key, (pruned.byKey[key] ?: persistentSetOf()).adding(id)),
        settled = pruned.settled,
      )
    }
    return id
  }

  override suspend fun update(entry: OutboxEntry<E>, expected: OutboxStatus): Boolean {
    var written = false
    rows.update { current ->
      val row = current.all[entry.id]
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
        val pruned = current.pruned()
        if (entry.status.isSettled) {
          val settledAt = time.markNow()
          val key = updated.key
          val waiting = pruned.byKey[key]?.removing(entry.id)
          Rows(
            all = pruned.all.putting(entry.id, Row(updated, settledAt)),
            byKey =
              if (waiting.isNullOrEmpty()) {
                pruned.byKey.removing(key)
              } else {
                pruned.byKey.putting(key, waiting)
              },
            settled = pruned.settled.putting(entry.id, settledAt),
          )
        } else {
          val all = pruned.all.putting(entry.id, Row(updated, settledAt = null))
          Rows(all, pruned.byKey, pruned.settled)
        }
      }
    }
    return written
  }

  override fun entries(): Flow<List<OutboxEntry<E>>> =
    rows
      .map { current -> current.all.values.map { it.entry }.filterNot { it.status.isSettled } }
      .distinctUntilChanged()

  override fun entry(id: EntryId): Flow<OutboxEntry<E>?> =
    rows
      .map { current -> current.all[id]?.takeUnless { it.isExpired() }?.entry }
      .distinctUntilChanged()

  // Reads only each key's waiting rows up to its head, so a change costs about one row per key
  override fun heads(): Flow<List<OutboxEntry<E>>> =
    rows
      .map { current -> current.byKey.keys.mapNotNull { current.headOf(it) } }
      .distinctUntilChanged()

  override suspend fun head(key: String): OutboxEntry<E>? = rows.value.headOf(key)

  /** Drops every row, settled ones included, so each handle reads its row as absent. */
  fun clear() {
    rows.value = Rows()
  }

  /** [key]'s oldest row that is Queued or Sending. Failed rows before it are skipped. */
  private fun Rows<E>.headOf(key: String): OutboxEntry<E>? {
    for (id in byKey[key].orEmpty()) {
      val row = all[id]?.entry ?: continue
      if (row.status.isSendable) {
        return row
      }
    }
    return null
  }

  /**
   * These rows without the settled ones that expired. [Rows.settled] is in settle order, so this
   * stops at the first row that has not expired, and costs nothing while none has.
   */
  private fun Rows<E>.pruned(): Rows<E> {
    var all = all
    var settled = settled
    for ((id, settledAt) in this.settled) {
      if (settledAt.elapsedNow() < keepSettledFor) {
        break
      }
      all = all.removing(id)
      settled = settled.removing(id)
    }
    return if (settled === this.settled) {
      this
    } else {
      Rows(all, byKey, settled)
    }
  }

  private fun Row<E>.isExpired(): Boolean =
    settledAt != null && settledAt.elapsedNow() >= keepSettledFor

  private class Row<E>(val entry: OutboxEntry<E>, val settledAt: TimeMark?)

  /**
   * Every row and two indexes over them. The maps keep insertion order, which is send order.
   *
   * @property all every row, settled ones included until they expire.
   * @property byKey each key's rows that are neither Sent nor Discarded, oldest first. A key with
   *   none has no entry.
   * @property settled when each settled row settled, oldest first, for pruning.
   */
  private class Rows<E>(
    val all: PersistentMap<EntryId, Row<E>> = persistentMapOf(),
    val byKey: PersistentMap<String, PersistentSet<EntryId>> = persistentMapOf(),
    val settled: PersistentMap<EntryId, TimeMark> = persistentMapOf(),
  )
}
