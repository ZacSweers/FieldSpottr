// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import dev.zacsweers.fieldspottr.data.core.impl.RealDataScopeFactory
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/**
 * The entry point for a repository's data work. It builds the repository's reads ([query] and
 * [keyed]), its command runner ([newActionRunner]) and its writes that must reach the server
 * ([outbox], [optimisticStore] and [optimisticStores]). Everything built here runs in [scope] and
 * stops when that scope is cancelled, so choosing the data scope chooses how long the work lives.
 *
 * A repository takes one from the graph, like an app-scoped binding, or builds one over its own
 * scope with [Factory.create]. The failure handlers, clock, outbox stores and network state come
 * from the factory. Three words recur in these docs: an action is a repository function like
 * `star(id)`, a command is the block that action submits to [ActionRunner.run], and an [Operation]
 * is the handle the caller gets back.
 */
@SubclassOptInRequired(DelicateDataLayerApi::class)
interface DataScope {
  /**
   * The coroutine scope everything here runs in, for operators that need one, like `shareIn`.
   * Cancelling it stops this data scope's runners, queries and outboxes.
   */
  val scope: CoroutineScope

  /**
   * A new runner in this scope. Each call returns another runner, so build one per owner and keep
   * it: commands on one runner share its [Ordering.Serial] line and its keys.
   *
   * Failed commands are reported to [onFailure], which lets a repository log them under its own
   * name. When [onFailure] is null they go to the `onActionFailure` handler the factory was built
   * with, which covers every failure other than a fetch.
   */
  fun newActionRunner(onFailure: ((Throwable) -> Unit)? = null): ActionRunner

  /**
   * A query over [store] that fills it with [fetch]. Its checks, fetches and store calls run on
   * this scope's dispatcher, usually `Dispatchers.Default`, which suits decoding a response. A
   * fetch that blocks a thread should wrap that part in `withContext` with an I/O dispatcher
   * injected into its owner.
   */
  fun <T> query(store: QueryStore<T>, fetch: suspend () -> T, freshness: Freshness): DataQuery<T>

  /**
   * One query per key, each over the [store] for that key, shared by every caller for the key so
   * concurrent collectors share its fetches and its status. [freshness] can differ per key, like a
   * shorter window for today than for later dates. [maxFetches] caps the fetches in flight across
   * all keys. It does not cap store writes, which still run at the same time for different keys.
   *
   * A key's query is dropped as soon as no caller uses it and none of its fetches, checks or
   * invalidations is queued or running. Its data stays in its store, so the next query for the key
   * picks up the stored value and fetch time.
   */
  fun <K : Any, T> keyed(
    store: (key: K) -> QueryStore<T>,
    fetch: suspend (key: K) -> T,
    freshness: (key: K) -> Freshness,
    maxFetches: Int = Int.MAX_VALUE,
  ): KeyedDataQueries<K, T>

  /** [keyed] with one [freshness] rule for every key. */
  fun <K : Any, T> keyed(
    store: (key: K) -> QueryStore<T>,
    fetch: suspend (key: K) -> T,
    freshness: Freshness,
    maxFetches: Int = Int.MAX_VALUE,
  ): KeyedDataQueries<K, T> = keyed(store, fetch, { freshness }, maxFetches)

  /**
   * An outbox called [name]: writes that must reach the server, kept as rows and sent in order per
   * key, as [Outbox] describes. Its rows live in the store that the factory's store set opens for
   * [name], and its drains run in this scope. Cancelling this scope stops the drains and keeps the
   * rows, and the next outbox built for [name] sends them. The data scopes of one factory may run
   * only one outbox per name at a time, so build it in the one owner that sends these writes.
   *
   * [serializer] is required even when the stores are in memory, so changing the store set needs no
   * change where outboxes are built.
   *
   * @param send delivers one payload, with its row's id, the same id the row keeps across a
   *   restart. Pass it to the server as the request's idempotency key, since a row still sending
   *   when the process dies is sent again. It runs on this scope's dispatcher, usually
   *   `Dispatchers.Default`, so a send that blocks a thread should wrap that part in `withContext`
   *   with an I/O dispatcher injected into its owner.
   * @param revert undoes the optimistic change when a row fails for good or is discarded. It reads
   *   only the payload, so it works after a restart too.
   */
  fun <E> outbox(
    name: String,
    serializer: KSerializer<E>,
    send: suspend (payload: E, id: EntryId) -> Unit,
    revert: suspend (E) -> Unit = {},
    retry: RetryPolicy = RetryPolicy.exponential(max = 5.minutes),
  ): Outbox<E>

  /**
   * An outbox over a [store] of its own, like rows in an app table, draining in this scope. Its
   * [send] and [revert] work as in the outbox by name. This skips the one-outbox-per-name check, so
   * build one outbox per store, in the one owner that sends these writes.
   */
  fun <E> outbox(
    store: OutboxStore<E>,
    send: suspend (payload: E, id: EntryId) -> Unit,
    revert: suspend (E) -> Unit = {},
    retry: RetryPolicy = RetryPolicy.exponential(max = 5.minutes),
  ): Outbox<E>

  /**
   * The store under [optimisticStore] and [optimisticStores], which choose a [deliver] for a server
   * that answers each change with nothing or with its value. Call one of those, and this only for a
   * server that does both. It is a store for each key over the store [base] gives for it, whose
   * values callers change before the server confirms them, as [OptimisticQueryStore] describes,
   * with every waiting change kept as a row in the outbox called [name], opened from the factory's
   * store set. Only one outbox per name may run at a time, so build the set once, in the one owner
   * that changes this data, just as with [outbox]. [base] is called once per key, and the store it
   * returns serves that key's views and its commits for as long as this set lives. Keys need a
   * stable `toString`, which orders the rows, and [keySerializer], which stores them.
   *
   * @param apply the change [mutation] makes to the current value, with the contract
   *   [optimisticStore] describes: cheap, pure and idempotent.
   * @param deliver sends one change, with the row's id as the request's idempotency key, and
   *   returns what the truth takes: [Delivery.Acknowledged] applies the change to it with [apply],
   *   and [Delivery.Replaced] writes the server's value to it as fresh data. It runs on this
   *   scope's dispatcher, as [outbox]'s send does, and throws [PermanentFailure] for a rejection
   *   that retrying cannot fix.
   */
  fun <K : Any, T, M> optimisticStores(
    name: String,
    base: (key: K) -> QueryStore<T>,
    keySerializer: KSerializer<K>,
    serializer: KSerializer<M>,
    apply: (current: T?, mutation: M) -> T?,
    deliver: suspend (key: K, mutation: M, id: EntryId) -> Delivery<T>,
    retry: RetryPolicy = RetryPolicy.exponential(max = 5.minutes),
  ): OptimisticQueryStores<K, T, M>

  /**
   * Builds data scopes that share their failure handlers, clock, outbox store set, network state
   * and outbox scheduler.
   */
  @SubclassOptInRequired(DelicateDataLayerApi::class)
  interface Factory {
    /** A data scope whose work runs in [scope] and stops when it is cancelled. */
    fun create(scope: CoroutineScope): DataScope

    /**
     * What the outboxes running from this factory still have to send, combined:
     * [OutboxBacklog.Ready] if any has a row that can send now, else the earliest
     * [OutboxBacklog.BackingOff], else [OutboxBacklog.Empty]. A backoff whose deadline has passed
     * can send now, so it reads as Ready. It emits once every running outbox has read its rows,
     * then on each change. An outbox counts only while its scope runs, so rows left in a store no
     * outbox has opened are not in it, and with no outbox running it is Empty. The factory's
     * [OutboxScheduler] hears the same changes.
     */
    val backlog: Flow<OutboxBacklog>

    companion object
  }

  companion object
}

/**
 * Builds the factory an app or a test hands its repositories. Failed fetches go to [onFailure].
 * Every other failure goes to [onActionFailure], which defaults to [onFailure]: commands on
 * runners, outbox drains, and query commands that fail without a fetch, like an invalidate() whose
 * store throws. Outboxes open their rows on [stores], drain while [online] is true, and tell
 * [scheduler] when rows wait, as [OutboxScheduler] describes. Pass scopes on `Dispatchers.Default`,
 * not on Main, as [ActionRunner] explains.
 *
 * [canRetrySend] tells whether a send that threw may succeed on a later attempt, like a timeout
 * against a 4xx response. False fails the row at once: it is marked failed, its undo runs, and the
 * next row on its delivery key sends. A [PermanentFailure] and the retry policy's attempt limit
 * stop retries too. A throw from [canRetrySend] is reported, and the send counts as retryable.
 */
operator fun DataScope.Factory.Companion.invoke(
  onFailure: (Throwable) -> Unit,
  onActionFailure: (Throwable) -> Unit = onFailure,
  online: StateFlow<Boolean> = MutableStateFlow(true),
  stores: OutboxStores = InMemoryOutboxStores(),
  scheduler: OutboxScheduler = OutboxScheduler.None,
  clock: Clock = Clock.System,
  canRetrySend: (Throwable) -> Boolean = { true },
): DataScope.Factory =
  RealDataScopeFactory(onFailure, onActionFailure, online, stores, scheduler, clock, canRetrySend)

/** [DataScope.outbox] with the serializer for a `@Serializable` payload. */
inline fun <reified E> DataScope.outbox(
  name: String,
  noinline send: suspend (payload: E, id: EntryId) -> Unit,
  noinline revert: suspend (E) -> Unit = {},
  retry: RetryPolicy = RetryPolicy.exponential(max = 5.minutes),
): Outbox<E> = outbox(name, serializer<E>(), send, revert, retry)
