// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import kotlin.jvm.JvmName
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer

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
 * row m2 --> rows [m1, m2] --------------+--> send(m1), then send(m2), in order per key
 *                                                |
 *                       sent: the truth takes the value exchange returned, or apply(truth, m)
 *                       rejected: the row fails, so data shows the truth without it
 * ```
 *
 * Before the server confirms a change, its row is its only write. The truth changes only when a
 * fetch lands or a send succeeds, so it always holds something the server said. After a restart,
 * the store shows the truth plus the rows that still wait.
 *
 * `apply` runs on the data scope's dispatcher each time the truth or the rows change. Keep it
 * cheap, pure and idempotent, as [optimisticStore] explains. The first commits check idempotence,
 * and the first failure goes once to the store's `onFailure` handler, or to the factory's when it
 * has none. An `apply` that throws goes there too, and [data] leaves that change out. It is
 * reported once for as long as the store remembers the change, which lasts until the change is
 * sent, fails or is dropped and the stored rows the store reads have caught up. A store that forgot
 * a change, like after a restart, reports it again. A commit that fails in the base store, like on
 * a full disk, is reported there as well. The change still counts as sent, since the server has it.
 *
 * Built by [optimisticStore], or per key by [optimisticStores].
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
   * rows only, so a change shows here once its row is stored, a moment after it shows in [data].
   */
  val pending: Flow<List<PendingMutation<M>>>

  /**
   * Sends a failed change again, with its attempts reset, or ends a backoff early. From the moment
   * this returns, each value [data] computes holds the change while the new attempt runs. If the
   * requeue itself fails, like on a store error, [data] shows the change as it stood without this
   * call.
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
   * stored rows only.
   */
  val pendingCount: Flow<Int>

  /** [OptimisticQueryStore.retry] for a row from any key. */
  fun retry(id: EntryId): Operation

  /** [OptimisticQueryStore.discard] for a row from any key. */
  fun discard(id: EntryId): Operation
}

/**
 * One row of an optimistic store's outbox: the key and the change. A test reads the rows back with
 * `outboxStore<KeyedMutation<K, M>>(name)`. A single store's key is `Unit`.
 *
 * [token] identifies the change from the moment `mutate` makes it, so the store can tell the change
 * apart as a local change, a row and a sent change.
 */
@Serializable data class KeyedMutation<K, M>(val key: K, val mutation: M, val token: String)

/**
 * The server's answer to one change, from the `deliver` function of [DataScope.optimisticStores].
 * The `send` and `exchange` forms build it, so only a store that mixes the two needs it.
 */
sealed interface Delivery<out T> {
  /** Nothing. The change is applied to the truth with `apply`. */
  data object Acknowledged : Delivery<Nothing>

  /** The value the server holds now. It replaces the truth as fresh data. */
  data class Replaced<T>(val value: T) : Delivery<T>
}

/**
 * An [OptimisticQueryStore] over [base], for a server that answers each change with nothing.
 *
 * [base] holds the truth, and each waiting change is a row in the outbox called [name]. Only one
 * outbox per name may run at a time, so build this store once, in the one owner of this data, as
 * with [DataScope.outbox]. `clear()` on the store clears [base] and drops the waiting changes.
 *
 * @param apply returns the value with [mutation] applied. A null input means nothing is stored, and
 *   a null result removes the value. It runs on the data scope's dispatcher each time the truth or
 *   the rows change, so keep it cheap and pure. It must also be idempotent: given a value that
 *   already holds the change, it returns that value unchanged, as setting a field does. A fetch
 *   that overlapped the send, or a resend after a restart, can pass it such a value. A change that
 *   cannot be idempotent, like a counter's increment, takes the `exchange` form instead. The first
 *   16 commits of the store check idempotence with one more call each, and then the check stops.
 *
 *   A throw from it is reported to [onFailure] once for as long as the store remembers the change,
 *   and the store's data keeps flowing without that change. When it throws while a sent change is
 *   committed, the truth stays as it was and is marked stale, so the next fetch brings the server's
 *   value. The handle still succeeds, since the server has the change.
 *
 * @param send delivers one change with the row's id. The id stays the same across attempts and
 *   restarts, so pass it to the server as the idempotency key. Once `send` returns, [apply] applies
 *   the change to the truth. It runs on the data scope's dispatcher. Throw [PermanentFailure] for a
 *   rejection that retrying cannot fix.
 * @param onFailure hears every failure the store and its outbox report. Null uses the factory's
 *   `onFailure` handler.
 * @param serializer stores each change as a row. The default serves a `@Serializable` mutation.
 */
inline fun <T, reified M> DataScope.optimisticStore(
  name: String,
  base: QueryStore<T>,
  noinline apply: (current: T?, mutation: M) -> T?,
  noinline send: suspend (mutation: M, id: EntryId) -> Unit,
  retry: RetryPolicy? = null,
  noinline onFailure: ((Throwable) -> Unit)? = null,
  serializer: KSerializer<M> = kotlinx.serialization.serializer<M>(),
): OptimisticQueryStore<T, M> =
  optimisticStores(
      name = name,
      base = { base },
      keySerializer = Unit.serializer(),
      serializer = serializer,
      apply = apply,
      deliver = { _, mutation, id ->
        send(mutation, id)
        Delivery.Acknowledged
      },
      retry = retry,
      onFailure = onFailure,
    )
    .invoke(Unit)

/**
 * [optimisticStore] for a server that answers each change with the value it now holds. [exchange]
 * returns that value, and it replaces the truth in [base]. Use this form for a change that cannot
 * be idempotent, like a counter, or for an answer with fields the client cannot compute.
 *
 * Until the server answers, the value is an estimate. The store applies the waiting change on top
 * of each fetch, and cannot tell whether the fetch already holds it. So a counter can show a change
 * twice until the answer arrives, after a refresh during the send or after a restart that left the
 * row sending.
 *
 * To keep a count exact, give each change its own id, have the server return the ids its count
 * holds, and make [apply] skip a change whose id is already there. As with `send`, pass the row's
 * id as the idempotency key on every attempt.
 */
@JvmName("optimisticStoreExchanging")
inline fun <T, reified M> DataScope.optimisticStore(
  name: String,
  base: QueryStore<T>,
  noinline apply: (current: T?, mutation: M) -> T?,
  noinline exchange: suspend (mutation: M, id: EntryId) -> T,
  retry: RetryPolicy? = null,
  noinline onFailure: ((Throwable) -> Unit)? = null,
  serializer: KSerializer<M> = kotlinx.serialization.serializer<M>(),
): OptimisticQueryStore<T, M> =
  optimisticStores(
      name = name,
      base = { base },
      keySerializer = Unit.serializer(),
      serializer = serializer,
      apply = apply,
      deliver = { _, mutation, id -> Delivery.Replaced(exchange(mutation, id)) },
      retry = retry,
      onFailure = onFailure,
    )
    .invoke(Unit)

/**
 * [optimisticStore] per key, for a [DataScope.keyed] query. Every key shares the outbox called
 * [name], and [send] gets the key with the change and its id.
 *
 * [base] runs once per key, and its store serves that key for as long as this set lives. So the set
 * keeps one store object per key it has seen. For keys without bound, like thread ids, return small
 * views over one bounded source, like [QueryStore.inMemoryByKey] or a database table.
 *
 * [keySerializer] stores the keys, and each key's serialized form orders its rows. Keys that are
 * equal must serialize to the same form, so they share one order.
 */
inline fun <reified K : Any, T, reified M> DataScope.optimisticStores(
  name: String,
  noinline base: (key: K) -> QueryStore<T>,
  noinline apply: (current: T?, mutation: M) -> T?,
  noinline send: suspend (key: K, mutation: M, id: EntryId) -> Unit,
  retry: RetryPolicy? = null,
  noinline onFailure: ((Throwable) -> Unit)? = null,
  keySerializer: KSerializer<K> = kotlinx.serialization.serializer<K>(),
  serializer: KSerializer<M> = kotlinx.serialization.serializer<M>(),
): OptimisticQueryStores<K, T, M> =
  optimisticStores(
    name = name,
    base = base,
    keySerializer = keySerializer,
    serializer = serializer,
    apply = apply,
    deliver = { key, mutation, id ->
      send(key, mutation, id)
      Delivery.Acknowledged
    },
    retry = retry,
    onFailure = onFailure,
  )

/** [optimisticStores] for a server that answers each change with the value it holds now. */
@JvmName("optimisticStoresExchanging")
inline fun <reified K : Any, T, reified M> DataScope.optimisticStores(
  name: String,
  noinline base: (key: K) -> QueryStore<T>,
  noinline apply: (current: T?, mutation: M) -> T?,
  noinline exchange: suspend (key: K, mutation: M, id: EntryId) -> T,
  retry: RetryPolicy? = null,
  noinline onFailure: ((Throwable) -> Unit)? = null,
  keySerializer: KSerializer<K> = kotlinx.serialization.serializer<K>(),
  serializer: KSerializer<M> = kotlinx.serialization.serializer<M>(),
): OptimisticQueryStores<K, T, M> =
  optimisticStores(
    name = name,
    base = base,
    keySerializer = keySerializer,
    serializer = serializer,
    apply = apply,
    deliver = { key, mutation, id -> Delivery.Replaced(exchange(key, mutation, id)) },
    retry = retry,
    onFailure = onFailure,
  )
