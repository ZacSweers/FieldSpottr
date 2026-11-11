// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import kotlin.jvm.JvmInline
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor

/** Identifies one outbox row everywhere: in its store, in a handle and after a restart. */
@JvmInline value class EntryId(val value: String)

/** Where an outbox row stands. [Sent] and [Discarded] are final, and the store prunes such rows. */
sealed interface OutboxStatus {
  /** Waiting for its turn, the network or a backoff. */
  data object Queued : OutboxStatus

  /** In flight. A row found in this state at startup is sent again. */
  data object Sending : OutboxStatus

  /** Rejected or out of attempts. Needs [Outbox.retry] or [Outbox.discard]. */
  data class Failed(val cause: String) : OutboxStatus

  /** Delivered. */
  data object Sent : OutboxStatus

  /** Dropped by [Outbox.discard] without being sent. */
  data object Discarded : OutboxStatus
}

/** Whether the row has reached a final status, [OutboxStatus.Sent] or [OutboxStatus.Discarded]. */
val OutboxStatus.isSettled: Boolean
  get() = this == OutboxStatus.Sent || this == OutboxStatus.Discarded

/** One row of an outbox: the payload to send, its place in the order and where its send stands. */
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
   * Whether the outbox has run, or at least attempted, the revert of this row's optimistic change.
   * A revert runs at most once per row. The outbox sets this flag and attempts the revert as one
   * step that a cancelled scope cannot split, so only process death between the two can leave the
   * flag set without the revert having run.
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
 * store over a shared resource, like a database, must handle that.
 *
 * Calls run on the outbox scope's dispatcher, usually `Dispatchers.Default`, so they must not block
 * it. A store that blocks moves that work to an I/O dispatcher, as [QueryStore] describes.
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

  /**
   * Rows neither Sent nor Discarded, oldest first, re-emitted on every change. A store decodes each
   * row on its own and leaves out a row it cannot decode, as [onUndecodable] describes.
   */
  fun entries(): Flow<List<OutboxEntry<E>>>

  /**
   * One row, still observable after Sent or Discarded until the store prunes it. Null when there is
   * no such row, or when its payload cannot be decoded.
   */
  fun entry(id: EntryId): Flow<OutboxEntry<E>?>

  /**
   * Each key's head: its oldest row that is Queued or Sending, one per key, re-emitted when a head
   * changes. The order of the list is unspecified. Only a head can send or back off, so the outbox
   * watches this in place of [entries]. A row that cannot be decoded is skipped, so the next row on
   * its key is the head.
   *
   * The default reads and decodes every row of [entries] on each change. A store over many rows
   * overrides it so that a change decodes and returns about one row per key.
   */
  fun heads(): Flow<List<OutboxEntry<E>>> =
    entries()
      .map { rows -> rows.filter { it.status.isSendable }.distinctBy { it.key } }
      .distinctUntilChanged()

  /**
   * [key]'s head as [heads] describes it, read once, or null when the key has none. A drain reads
   * this before each send. The default reads [entries].
   */
  suspend fun head(key: String): OutboxEntry<E>? =
    entries().first().firstOrNull { it.key == key && it.status.isSendable }

  /**
   * Hands [report] each row neither Sent nor Discarded that this store left out of a read because
   * its payload does not decode, with the decode error. Such a row never makes a read throw and
   * never blocks its key or another key. It stays stored until [OutboxStores.clearAll].
   *
   * The store hands on each such row once for as long as it lives, on the thread of the read that
   * met it, so [report] must not block. A row met before any listener was set waits for the first
   * one. One listener hears at a time, and a new one replaces the last. Closing the result removes
   * [report]. The default never calls it, which suits a store that keeps payloads as objects.
   *
   * A throw from [report] is dropped. The listener is usually the failure handler itself, so no
   * other handler is left to tell. The read that met the row goes on, and the row counts as handed
   * on.
   */
  fun onUndecodable(report: (id: EntryId, cause: Throwable) -> Unit): AutoCloseable =
    AutoCloseable {}
}

/** A store an [OutboxStores] set opened for a name, with the payload type it was opened for. */
internal class OpenedStore<S>(val descriptor: SerialDescriptor, val store: S) {
  /**
   * [store], if [serializer] describes the payload type it was opened for. Otherwise it throws, as
   * [OutboxStores.open] describes, since the store's codec would fail on the other type.
   */
  fun checked(name: String, serializer: KSerializer<*>): S {
    check(serializer.descriptor == descriptor) {
      "Outbox $name holds payloads of type $descriptor, so it cannot open for " +
        "${serializer.descriptor}"
    }
    return store
  }
}

/** Queued or Sending: the row waits to send, or is in flight. Only such a row can be a head. */
internal val OutboxStatus.isSendable: Boolean
  get() = this == OutboxStatus.Queued || this == OutboxStatus.Sending

/**
 * A set of [OutboxStore]s keyed by outbox name. The app builds one set and hands it to
 * [DataScope.Factory], and that choice decides where every outbox's rows live: in memory, in the
 * database or in files. Each outbox then opens its own store from the set by name. A durable set
 * also takes the payload format, so choosing the set chooses the format too.
 */
interface OutboxStores {
  /**
   * The store for the outbox called [name]. Calls with the same name return the same store, so an
   * outbox built again after its scope ended finds the rows it left. A durable store encodes each
   * payload with [serializer] in the set's format.
   *
   * A name holds one payload type. A later call whose [serializer] has a descriptor equal to the
   * first call's returns the store, also when the serializer is a new object. A call whose
   * descriptor differs throws [IllegalStateException], which names the outbox and both types.
   */
  fun <E> open(name: String, serializer: KSerializer<E>): OutboxStore<E>

  /**
   * Deletes every row of every outbox in this set, including rows of outboxes that this process
   * never opened. This is the call for logout. An outbox that is still running sees its rows
   * disappear, so it sends nothing more, and a handle that was following one of those rows settles
   * as Cancelled because the row is gone. A send that is already in flight runs to the end, and its
   * status write then finds no row to update. Call this after cancelling the scopes of the outboxes
   * that should stop sending, and before clearing the query stores those outboxes change.
   */
  suspend fun clearAll()
}
