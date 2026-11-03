// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import kotlin.jvm.JvmName
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer

/**
 * A [QueryStore] whose value callers change before the server confirms the change. The store keeps
 * the last fetched value as its truth and keeps each change as a durable row beside it. What it
 * shows is the truth with the waiting changes applied on top. A change shows at once, it stays on
 * top of a fetch that lands while it waits, and it drops out by itself when the server rejects it.
 * The query built over this store sees a plain [QueryStore], and nothing downstream knows that an
 * outbox is at work.
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
 * The row is the only write a change makes before the server confirms it. The truth changes only
 * when a fetch lands or a send succeeds, which means it always holds something the server said, and
 * a restart shows the truth plus the rows still waiting. The `apply` function runs on the data
 * scope's dispatcher, again on every change to the truth or the rows, so it must be cheap and pure.
 * It must also be idempotent, as [mutableStore] explains. The store checks this on every commit,
 * and the first change found not to be idempotent is reported once to the factory's
 * `onActionFailure` handler.
 *
 * Built by [mutableStore], or per key by [mutableStores].
 */
@SubclassOptInRequired(DelicateDataLayerApi::class)
interface MutableQueryStore<T, M> : QueryStore<T> {
  /**
   * Shows [mutation] in [data] now, stores it as a row and sends it after the rows before it. The
   * handle follows the row: Succeeded once sent, Failed once rejected for good or out of attempts,
   * Cancelled once discarded. It survives a restart through the row, like [Outbox.enqueue]'s.
   */
  fun mutate(mutation: M): Operation

  /** Changes the server has not confirmed, oldest first: waiting, sending or failed. */
  val pending: Flow<List<PendingMutation<M>>>

  /** Sends a failed change again, with its attempts reset, or ends a backoff early. */
  fun retry(id: EntryId): Operation

  /** Drops a waiting or failed change, so [data] shows the truth without it. */
  fun discard(id: EntryId): Operation

  /**
   * Removes the truth and drops this store's waiting and failed changes, so nothing sends for it
   * later, as on logout. A change already sending finishes, and its result lands in the truth, so
   * cancel the owning scope first when that must not happen.
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
 * One [MutableQueryStore] per key, over the stores [mutableStores] was given, sharing one set of
 * rows. It is the store function a [DataScope.keyed] query takes, so build the queries with it and
 * call [mutate] with the key. Rows for one key send in order, and keys do not wait on each other.
 */
@SubclassOptInRequired(DelicateDataLayerApi::class)
interface MutableQueryStores<K : Any, T, M> : (K) -> MutableQueryStore<T, M> {
  /** [MutableQueryStore.mutate] on [key]'s store. */
  fun mutate(key: K, mutation: M): Operation

  /** [key]'s changes the server has not confirmed, oldest first. */
  fun pending(key: K): Flow<List<PendingMutation<M>>>

  /** How many changes across every key the server has not confirmed, for a sync badge. */
  val pendingCount: Flow<Int>

  /** [MutableQueryStore.retry] for a row from any key. */
  fun retry(id: EntryId): Operation

  /** [MutableQueryStore.discard] for a row from any key. */
  fun discard(id: EntryId): Operation
}

/**
 * One row of a mutable store's outbox: the key it changes and the change. The factory's store set
 * keeps the rows under the store's name with this payload, so a test reads them back as
 * `outboxStore<KeyedMutation<K, M>>(name)`. A single store's key is `Unit`.
 */
@Serializable data class KeyedMutation<K, M>(val key: K, val mutation: M)

/**
 * What the server answered one change with, from the `deliver` function of
 * [DataScope.mutableStores]. The `send` and `exchange` forms of [mutableStore] and [mutableStores]
 * build it, so only a store that mixes the two needs it.
 */
sealed interface Delivery<out T> {
  /** Nothing. The change is applied to the truth with `apply`. */
  data object Acknowledged : Delivery<Nothing>

  /** The value the server holds now. It replaces the truth as fresh data. */
  data class Replaced<T>(val value: T) : Delivery<T>
}

/**
 * A store over [base] whose value callers change before the server confirms it, as
 * [MutableQueryStore] describes, for a server that answers each change with nothing. [base] holds
 * the truth: the last fetched value, plus each change once it has been sent. Every waiting change
 * is kept as a row in the outbox called [name], opened from the factory's store set. Only one
 * outbox per name may run at a time, so build this store once, in the one owner that changes this
 * data, just as with [DataScope.outbox]. Calling `clear()` on the returned store clears the truth
 * in [base] and drops the waiting changes with it.
 *
 * @param apply the change [mutation] makes to the current value. Null in means nothing is stored
 *   yet, and null out means the change removes the value. It runs on the data scope's dispatcher,
 *   again on every change to the truth or the rows, so it must be cheap and pure. It must also be
 *   idempotent: applied to a value that already holds the change, it returns that value unchanged,
 *   as setting a field does, and as an append does once it skips an item already there. A fetch
 *   that overlapped the send, or a resend after a restart, can hand it such a value, and the store
 *   cannot tell. A change that cannot be made idempotent, like a counter, takes the `exchange` form
 *   instead, so the server's value replaces the truth.
 * @param send delivers one change, with the row's id, the same id `pending` shows and a restart
 *   keeps. Pass it to the server as the request's idempotency key, since a row still sending when
 *   the process dies is sent again. Once it returns, the change is applied to the truth with
 *   [apply]. It runs on the data scope's dispatcher, as [DataScope.outbox]'s send does, and throws
 *   [PermanentFailure] for a rejection that retrying cannot fix.
 * @param serializer stores each change as a row. The default serves a `@Serializable` mutation.
 */
inline fun <T, reified M> DataScope.mutableStore(
  name: String,
  base: QueryStore<T>,
  noinline apply: (current: T?, mutation: M) -> T?,
  noinline send: suspend (mutation: M, id: EntryId) -> Unit,
  retry: RetryPolicy = RetryPolicy.exponential(max = 5.minutes),
  serializer: KSerializer<M> = kotlinx.serialization.serializer<M>(),
): MutableQueryStore<T, M> =
  mutableStores(
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
    )
    .invoke(Unit)

/**
 * [mutableStore] for a server that answers each change with the value it holds now. [exchange]
 * delivers one change as `send` does there and returns that value, which replaces the truth in
 * [base] as fresh data in place of applying the change locally. For a change that cannot be made
 * idempotent, like a counter, or a server whose answer carries fields the client cannot compute.
 */
@JvmName("mutableStoreExchanging")
inline fun <T, reified M> DataScope.mutableStore(
  name: String,
  base: QueryStore<T>,
  noinline apply: (current: T?, mutation: M) -> T?,
  noinline exchange: suspend (mutation: M, id: EntryId) -> T,
  retry: RetryPolicy = RetryPolicy.exponential(max = 5.minutes),
  serializer: KSerializer<M> = kotlinx.serialization.serializer<M>(),
): MutableQueryStore<T, M> =
  mutableStores(
      name = name,
      base = { base },
      keySerializer = Unit.serializer(),
      serializer = serializer,
      apply = apply,
      deliver = { _, mutation, id -> Delivery.Replaced(exchange(mutation, id)) },
      retry = retry,
    )
    .invoke(Unit)

/**
 * [mutableStore] per key, for a [DataScope.keyed] query: a store for each key over the store [base]
 * gives for it, all sharing the outbox called [name]. [base] is called once per key, and the store
 * it returns serves that key's views and its commits for as long as this set lives. Keys need a
 * stable `toString`, which orders the rows, and [keySerializer], which stores them. [send] gets the
 * key beside the change and its id.
 */
inline fun <reified K : Any, T, reified M> DataScope.mutableStores(
  name: String,
  noinline base: (key: K) -> QueryStore<T>,
  noinline apply: (current: T?, mutation: M) -> T?,
  noinline send: suspend (key: K, mutation: M, id: EntryId) -> Unit,
  retry: RetryPolicy = RetryPolicy.exponential(max = 5.minutes),
  keySerializer: KSerializer<K> = kotlinx.serialization.serializer<K>(),
  serializer: KSerializer<M> = kotlinx.serialization.serializer<M>(),
): MutableQueryStores<K, T, M> =
  mutableStores(
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
  )

/** [mutableStores] for a server that answers each change with the value it holds now. */
@JvmName("mutableStoresExchanging")
inline fun <reified K : Any, T, reified M> DataScope.mutableStores(
  name: String,
  noinline base: (key: K) -> QueryStore<T>,
  noinline apply: (current: T?, mutation: M) -> T?,
  noinline exchange: suspend (key: K, mutation: M, id: EntryId) -> T,
  retry: RetryPolicy = RetryPolicy.exponential(max = 5.minutes),
  keySerializer: KSerializer<K> = kotlinx.serialization.serializer<K>(),
  serializer: KSerializer<M> = kotlinx.serialization.serializer<M>(),
): MutableQueryStores<K, T, M> =
  mutableStores(
    name = name,
    base = base,
    keySerializer = keySerializer,
    serializer = serializer,
    apply = apply,
    deliver = { key, mutation, id -> Delivery.Replaced(exchange(key, mutation, id)) },
    retry = retry,
  )
