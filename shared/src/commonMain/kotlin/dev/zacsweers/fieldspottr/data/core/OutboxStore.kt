// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import kotlin.jvm.JvmInline
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** Identifies one outbox row everywhere: in its store, in a handle and after a restart. */
@JvmInline value class EntryId(val value: String)

sealed interface OutboxStatus {
  /** Waiting for its turn, the network or a backoff. */
  data object Queued : OutboxStatus

  /** In flight. A row found in this state at startup is sent again. */
  data object Sending : OutboxStatus

  /** Rejected or out of attempts. Needs [Outbox.retry] or [Outbox.discard]. */
  data class Failed(val cause: String) : OutboxStatus

  data object Sent : OutboxStatus

  data object Discarded : OutboxStatus
}

val OutboxStatus.isSettled: Boolean
  get() = this == OutboxStatus.Sent || this == OutboxStatus.Discarded

data class OutboxEntry<E>(
  val id: EntryId,
  /** Rows with equal keys send in order. An empty key is the outbox-wide default. */
  val key: String,
  val payload: E,
  val status: OutboxStatus,
  /** Transient failures so far. */
  val attempts: Int = 0,
  val lastError: String? = null,
  /**
   * The optimistic change behind this row was undone, or an undo was attempted. Revert runs at most
   * once per row.
   */
  val reverted: Boolean = false,
  /** When the current backoff ends. It lives on the row, so a restart waits it out. */
  val retryAt: Instant? = null,
)

/**
 * Durable rows for an [Outbox]. The in-memory store is the default, and [DataStoreOutboxStore]
 * keeps rows across process death.
 *
 * The outbox drains each key on its own, so calls for different keys can run at the same time. A
 * store over a shared resource, like a database, must allow that or take turns itself.
 *
 * Calls come from the outbox's runner, on its scope's dispatcher, usually `Dispatchers.Default`, so
 * they must not block that thread. A store whose calls block moves them with `withContext` onto a
 * dispatcher it was given, as [QueryStore] describes.
 */
interface OutboxStore<E> {
  /** Inserts a Queued row and returns its id, a Uuid, so no other install or device reuses it. */
  suspend fun insert(key: String, payload: E): EntryId

  /**
   * Compare-and-set. Writes status, attempts, lastError, reverted and retryAt only if the row's
   * status is still [expected]. Returns whether it wrote.
   */
  suspend fun update(entry: OutboxEntry<E>, expected: OutboxStatus): Boolean

  /** Rows neither Sent nor Discarded, oldest first, re-emitted on every change. */
  fun entries(): Flow<List<OutboxEntry<E>>>

  /**
   * One row, still observable after Sent or Discarded until the store prunes it. Null when there is
   * no such row.
   */
  fun entry(id: EntryId): Flow<OutboxEntry<E>?>
}

/**
 * Rows in memory, lost with the process. A settled row stays observable for [keepSettledFor], so a
 * handle that re-attaches soon after still sees its outcome, and the next write prunes it.
 */
fun <E> inMemoryOutboxStore(
  keepSettledFor: Duration = 1.minutes,
  time: TimeSource = TimeSource.Monotonic,
): OutboxStore<E> = InMemoryOutboxStore(keepSettledFor, time)

/** The app's store choice, made once. Each outbox opens its own store from it by name. */
interface OutboxStores {
  /** The store for the outbox called [name]. Open each name once per process. */
  fun <E> open(name: String, codec: PayloadCodec<E>): OutboxStore<E>
}

/** Every outbox in memory. The default, and what tests and previews use. */
object InMemoryOutboxStores : OutboxStores {
  override fun <E> open(name: String, codec: PayloadCodec<E>): OutboxStore<E> =
    inMemoryOutboxStore()
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
