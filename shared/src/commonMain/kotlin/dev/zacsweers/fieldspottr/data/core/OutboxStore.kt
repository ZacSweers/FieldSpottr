// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import kotlin.jvm.JvmInline
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer

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
  /**
   * Inserts a Queued row and returns its id, a Uuid, so no other install or device reuses it.
   * Throws only when it stored nothing, since the outbox then reverts the optimistic change. So an
   * insert that starts runs to the end, and a caller cancelled meanwhile still gets the id of the
   * row it stored.
   */
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
 * The app's store choice, made once. Each outbox opens its own store from it by name. A durable
 * store set also takes the payload format, so the app chooses the format with the store.
 */
interface OutboxStores {
  /**
   * The store for the outbox called [name]. Calls with the same name return stores over the same
   * rows, so an outbox built again after its scope ended finds the rows it left. Open a name with
   * one payload type. A durable store encodes each payload with [serializer] in its set's format.
   */
  fun <E> open(name: String, serializer: KSerializer<E>): OutboxStore<E>
}
