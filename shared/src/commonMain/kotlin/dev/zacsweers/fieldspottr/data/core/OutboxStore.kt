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

/**
 * Where an outbox row stands. [Sent] and [Discarded] are final. The store prunes a Discarded row,
 * and a Sent row once its handle has committed it, as [OutboxEntry.committed] describes.
 */
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
  /**
   * Whether the outbox's handle has taken this Sent row into its local state, like an optimistic
   * store that writes the change into its truth. A row sent while its data scope had no handle for
   * the outbox stays uncommitted until a handle attaches, so the store keeps it until then.
   */
  val committed: Boolean = false,
  /** When the send finished. Set on Sent rows only. A commit reads it to tell older fetches. */
  val sentAt: Instant? = null,
  /**
   * The server's answer to a Sent row that is not committed yet, encoded by the outbox's reply
   * codec. Null when the server answered with nothing, as [Delivery.Acknowledged] does. The write
   * that marks the row committed also clears it, so a committed row keeps no answer, and a row
   * whose commit write failed keeps it for the next commit.
   */
  val reply: String? = null,
)

/**
 * Whether the row has no work left: it is Sent and committed, or Discarded. Only such a row gets a
 * settled time, and the store prunes it once that time is old enough.
 */
val OutboxEntry<*>.isDone: Boolean
  get() = (status == OutboxStatus.Sent && committed) || status == OutboxStatus.Discarded

/**
 * Whether the row waits for its outbox's handle: it is Sent and not committed, or Failed and its
 * revert has not run. A handle settles such rows when it attaches, oldest first per key.
 */
val OutboxEntry<*>.isOwed: Boolean
  get() =
    (status == OutboxStatus.Sent && !committed) || (status is OutboxStatus.Failed && !reverted)

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
   * Inserts a Queued row with the id [id]. The outbox makes the id, a Uuid, before the insert, so
   * no other install or device reuses it and the handle knows it before the row exists. An id
   * already stored throws and stores nothing.
   *
   * Throws only when it stored nothing, since the outbox then reverts the optimistic change. So an
   * insert that starts runs to the end, also when its caller is cancelled meanwhile.
   */
  suspend fun insert(id: EntryId, key: String, payload: E)

  /**
   * Compare-and-set. Writes status, attempts, lastError, reverted, retryAt, committed, sentAt and
   * reply only if the row's status is still [expected]. Returns whether it wrote.
   */
  suspend fun update(entry: OutboxEntry<E>, expected: OutboxStatus): Boolean

  /**
   * Rows that are not [isDone], oldest first, re-emitted on every change. That includes Sent rows
   * that are not committed. A store decodes each row on its own and leaves out a row it cannot
   * decode, as [onUndecodable] describes.
   */
  fun entries(): Flow<List<OutboxEntry<E>>>

  /**
   * The rows that are [isOwed], oldest first, read once. A handle reads these when it attaches. The
   * default reads [entries].
   */
  suspend fun owed(): List<OutboxEntry<E>> = entries().first().filter { it.isOwed }

  /**
   * [key]'s rows that are [isOwed], oldest first, read once. A handle settles one key at a time
   * with this. The default filters [owed]. A store over many rows overrides it to read only [key].
   */
  suspend fun owed(key: String): List<OutboxEntry<E>> = owed().filter { it.key == key }

  /**
   * One row, still observable once it is [isDone] until the store prunes it. Null when there is no
   * such row, or when its payload cannot be decoded.
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
   * Hands [report] each row that is not [isDone] that this store left out of a read because its
   * payload does not decode, with the decode error. Such a row never makes a read throw and never
   * blocks its key or another key. It stays stored until [OutboxStores.clearAll].
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
 * database or in files. Each [OutboxSender] then opens its store from the set by its key's name. A
 * durable set also takes the payload format, so choosing the set chooses the format too.
 */
interface OutboxStores {
  /**
   * The store for the outbox called [name]. Calls with the same name return the same store, so a
   * sender in a later data scope finds the rows an earlier one left. A durable store encodes each
   * payload with [serializer] in the set's format.
   *
   * A name holds one payload type. A later call whose [serializer] has a descriptor equal to the
   * first call's returns the store, also when the serializer is a new object. A call whose
   * descriptor differs throws [IllegalStateException], which names the outbox and both types.
   */
  fun <E> open(name: String, serializer: KSerializer<E>): OutboxStore<E>

  /**
   * Encodes the server's answers for [OutboxEntry.reply], for a row sent while no handle could
   * commit it. A durable set encodes answers in its payload format. The default uses JSON.
   */
  fun <R> replyCodec(serializer: KSerializer<R>): PayloadCodec<R> =
    serializer.asCodec(DefaultPayloadFormat)

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
