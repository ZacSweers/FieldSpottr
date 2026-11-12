// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * A [QueryStore] that shows a change before the server confirms it.
 *
 * The store keeps the truth, which is the last value the server sent, and a durable row for each
 * change that waits. It shows the truth with the waiting changes applied on top. A change shows at
 * once, stays on top of a fetch that lands while it waits, and drops out when the server rejects
 * it. A query over this store sees a plain [QueryStore].
 *
 * ```
 * mutate(m2)                 data = apply(apply(truth, m1), m2)
 *    |                                   ^
 *    v                                   |
 * row m2 --> rows [m1, m2] --------------+--> the sender sends m1, then m2, in order per key
 *                                                |
 *                       sent: the store commits it, so the truth takes the server's answer, or
 *                             apply(truth, m) when the server answered with nothing
 *                       rejected: the row fails, so data shows the truth without it
 * ```
 *
 * Before the server confirms a change, its row is its only write. The truth changes only when a
 * fetch lands or a sent change is committed, so it always holds something the server said. After a
 * restart, the store shows the truth plus the rows that still wait. A row that the sender sent
 * before this store existed, like in a background drain, keeps showing until the store commits it,
 * which happens once the store is built.
 *
 * `apply` runs on the data scope's dispatcher each time the truth or the rows change. Keep it
 * cheap, pure and idempotent, as [DataScope.optimisticStore] explains. The first commits check
 * idempotence, and the first failure goes once to the store's `onFailure` handler, or to the
 * factory's when it has none. An `apply` that throws goes there too, and [data] leaves that change
 * out. It is reported once for as long as the store remembers the change, which lasts until the
 * change is sent, fails or is dropped and the stored rows the store reads have caught up. A store
 * that forgot a change, like after a restart, reports it again.
 *
 * A commit that fails on a store error, like a base store write on a full disk, is reported there
 * too, once per attempt. The change still counts as sent, since the server has it, so its row stays
 * sent and waits, and the commit runs again after a pause that doubles from 1 second to 1 minute
 * and resets once the key's commits all land. The change keeps showing meanwhile. Within one
 * process, a commit whose truth write landed never applies the change again: when only the write
 * that marks the row committed fails, it is reported to the sender's handler, and a retry repeats
 * that write alone, after the same doubling pause.
 *
 * On the live path, right after the send, the row is still Sending when that write runs. If it
 * fails there, the row stays Sending, and the outbox sends it again with the same id, as [Outbox]
 * describes. The commit that follows does not apply the change again in this process.
 *
 * Across a restart that guarantee cannot hold. A process that dies after a commit wrote the truth
 * and before the row was marked committed leaves the row owed, and the next process commits it
 * again on top of a truth that already holds it. That window, and a fetch that read the server
 * after it took the change but landed before the commit, are why `apply` must be idempotent.
 *
 * Built by [DataScope.optimisticStore], or per key by [DataScope.optimisticStores].
 */
@SubclassOptInRequired(DelicateDataLayerApi::class)
interface OptimisticQueryStore<T, M> : QueryStore<T> {
  /**
   * Shows [mutation] in [data] now, stores it as a row, and sends it after the rows before it.
   *
   * From the moment this returns, each value [data] computes holds the change until it is sent,
   * fails or is dropped, also before its row is stored. An `apply` that throws for it leaves it
   * out. A value computed before this call can still arrive after it. If the row cannot be stored,
   * the change leaves [data] and the handle fails.
   *
   * The handle follows the row, and a restart keeps it. It succeeds once sent, fails once rejected
   * for good or out of attempts, and settles Cancelled once discarded. Its [OutboxOperation.stored]
   * settles once the row is written, for a caller that only needs the change kept.
   */
  fun mutate(mutation: M): OutboxOperation

  /**
   * Changes the server has not confirmed, oldest first: waiting, sending or failed. It lists stored
   * rows only, so a change shows here once its row is stored, a moment after it shows in [data]. A
   * sent change that waits for its commit is left out, since the server has it.
   */
  val pending: Flow<List<PendingMutation<M>>>

  /**
   * Sends a failed change again, with its attempts reset, or ends a backoff early. From the moment
   * this returns, each value [data] computes holds the change while the new attempt runs. If the
   * requeue itself fails, like on a store error, [data] shows the change as it stood without this
   * call. A change the server already has, whose commit waits, sends nothing again: the handle
   * succeeds, and [data] keeps showing the change until its commit lands.
   */
  fun retry(id: EntryId): Operation

  /**
   * Drops a waiting or failed change. From the moment this returns, each value [data] computes
   * lacks it. If the row cannot be discarded, as when it already sends, the change shows again and
   * the handle fails.
   */
  fun discard(id: EntryId): Operation

  /**
   * Removes the truth and drops every change the server has not confirmed, so nothing sends later,
   * as on logout. Once this returns, each value [data] computes lacks the dropped changes. A send
   * already in flight may still reach the server, but its result is ignored and its handle settles
   * Cancelled. A change still being stored is dropped once it is in.
   */
  override suspend fun clear()
}

/**
 * A change the server has not confirmed yet. [status] is [OutboxStatus.Queued],
 * [OutboxStatus.Sending] or [OutboxStatus.Failed]. A failed change no longer shows in the store's
 * data until it is retried.
 */
data class PendingMutation<M>(
  val id: EntryId,
  val mutation: M,
  val status: OutboxStatus,
  /** Transient failures so far. */
  val attempts: Int = 0,
  val lastError: String? = null,
  /** When the current backoff ends, or null when not backing off. */
  val retryAt: Instant? = null,
)

/**
 * One [OptimisticQueryStore] per key, all sharing one set of rows. Pass it as the store of a
 * [DataScope.keyed] query, and call [mutate] with the key. Rows for one key send in order, and keys
 * do not wait on each other.
 */
@SubclassOptInRequired(DelicateDataLayerApi::class)
interface OptimisticQueryStores<K : Any, T, M> : (K) -> OptimisticQueryStore<T, M> {
  /** [OptimisticQueryStore.mutate] on [key]'s store. */
  fun mutate(key: K, mutation: M): OutboxOperation

  /** [key]'s changes the server has not confirmed, oldest first, from stored rows only. */
  fun pending(key: K): Flow<List<PendingMutation<M>>>

  /**
   * How many changes across every key the server has not confirmed, for a sync badge. It counts
   * stored rows only, and no sent change that waits for its commit.
   */
  val pendingCount: Flow<Int>

  /** [OptimisticQueryStore.retry] for a row from any key. */
  fun retry(id: EntryId): Operation

  /** [OptimisticQueryStore.discard] for a row from any key. */
  fun discard(id: EntryId): Operation
}

/**
 * One row of the outbox of an [OptimisticQueryStores] set: the key and the change. The set's
 * [OutboxKey] has this as its payload type, so its sender gets the key with the change. A single
 * [OptimisticQueryStore] has no key, and its rows hold the change itself.
 */
@Serializable data class KeyedMutation<K, M>(val key: K, val mutation: M)

/**
 * The server's answer to one send, from the `deliver` function of [deliveringSender]. [sender] and
 * [exchangingSender] build it, so only a sender that mixes the two needs it.
 */
sealed interface Delivery<out T> {
  /** Nothing. The change is applied to the truth with `apply`. */
  data object Acknowledged : Delivery<Nothing>

  /** The value the server holds now. It replaces the truth as fresh data. */
  data class Replaced<T>(val value: T) : Delivery<T>
}
