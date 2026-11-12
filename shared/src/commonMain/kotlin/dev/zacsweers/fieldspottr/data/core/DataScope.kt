// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import dev.zacsweers.fieldspottr.data.core.impl.RealDataScopeFactory
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/**
 * The entry point for a repository's data work. It builds reads ([query] and [keyed]), a command
 * runner ([newActionRunner]), and writes that must reach the server ([outbox], [optimisticStore]
 * and [optimisticStores]). Everything built here runs in [scope] and stops when that scope is
 * cancelled, so the data scope sets how long the work lives.
 *
 * A repository takes one from the graph, or builds one over its own scope with [Factory.create].
 * The factory supplies the failure handlers, clock, outbox stores and network state.
 *
 * These docs use three words. An action is a repository function like `star(id)`. A command is the
 * block that action submits to [ActionRunner.run]. An [Operation] is the handle the caller gets
 * back.
 */
@SubclassOptInRequired(DelicateDataLayerApi::class)
interface DataScope {
  /**
   * The coroutine scope everything here runs in, for operators that need one, like `shareIn`.
   * Cancelling it stops this data scope's runners, queries and outboxes.
   */
  val scope: CoroutineScope

  /**
   * A new runner in this scope. Build one per owner and keep it, since commands on one runner share
   * its [Ordering.Serial] line and its keys. Failed commands go to [onFailure], so a repository can
   * log them under its own name, or to the factory's `onFailure` handler when it is null.
   */
  fun newActionRunner(onFailure: ((Throwable) -> Unit)? = null): ActionRunner

  /**
   * A query over [store] that fills it with [fetch]. Its work runs on this scope's dispatcher,
   * usually `Dispatchers.Default`, which suits decoding a response. A fetch that blocks a thread
   * moves that part to an I/O dispatcher with `withContext`.
   *
   * @param retry sets how long the fetches the query starts on its own hold off after failures, as
   *   [DataQuery.state] describes. Null uses the factory's `fetchRetry` policy.
   * @param onFailure hears every failure this query reports, failed fetches included. Null uses the
   *   factory's handlers.
   */
  fun <T> query(
    store: QueryStore<T>,
    freshness: Freshness,
    retry: RetryPolicy? = null,
    onFailure: ((Throwable) -> Unit)? = null,
    fetch: suspend () -> T,
  ): DataQuery<T>

  /**
   * One query per key over that key's [store]. Every caller for a key shares its query, so they
   * share its fetches and its status. [freshness] can differ per key, like a shorter window for
   * today than for later dates. [maxFetches] caps the fetches in flight across all keys, but not
   * the store writes. [retry] and [onFailure] work as in [query], for every key.
   *
   * A key's query is dropped once no caller uses it and no work for it is queued or running. Its
   * data stays in its store, so the next query for the key starts from it.
   */
  fun <K : Any, T> keyed(
    store: (key: K) -> QueryStore<T>,
    freshness: (key: K) -> Freshness,
    maxFetches: Int = Int.MAX_VALUE,
    retry: RetryPolicy? = null,
    onFailure: ((Throwable) -> Unit)? = null,
    fetch: suspend (key: K) -> T,
  ): KeyedDataQueries<K, T>

  /** [keyed] with one [freshness] rule for every key. */
  fun <K : Any, T> keyed(
    store: (key: K) -> QueryStore<T>,
    freshness: Freshness,
    maxFetches: Int = Int.MAX_VALUE,
    retry: RetryPolicy? = null,
    onFailure: ((Throwable) -> Unit)? = null,
    fetch: suspend (key: K) -> T,
  ): KeyedDataQueries<K, T> = keyed(store, { freshness }, maxFetches, retry, onFailure, fetch)

  /**
   * An outbox called [name]: writes that must reach the server, kept as rows and sent in order per
   * key, as [Outbox] describes. The factory's store set opens its rows, and it drains in this
   * scope. Cancelling the scope stops the drains and keeps the rows for the next outbox built for
   * [name]. One factory runs only one outbox per name at a time, so build it in the one owner that
   * sends these writes.
   *
   * [serializer] is required even for in-memory stores, so switching the store set changes nothing
   * here.
   *
   * @param send delivers one payload with its row's id. The id stays the same across attempts and
   *   restarts, so pass it to the server as the idempotency key. It runs on this scope's
   *   dispatcher, so move blocking work to an I/O dispatcher with `withContext`.
   * @param revert undoes the optimistic change when a row fails for good or is discarded. It reads
   *   only the payload, so it works after a restart too.
   * @param retry decides whether a failed send retries and when. Null uses the factory's policy.
   * @param onFailure hears every failure this outbox reports. Null uses the factory's handler.
   */
  fun <E> outbox(
    name: String,
    serializer: KSerializer<E>,
    send: suspend (payload: E, id: EntryId) -> Unit,
    revert: suspend (E) -> Unit = {},
    retry: RetryPolicy? = null,
    onFailure: ((Throwable) -> Unit)? = null,
  ): Outbox<E>

  /**
   * An outbox over a [store] of its own, like rows in an app table. [send], [revert], [retry] and
   * [onFailure] work as in the outbox by name. [name] labels this outbox in the failures it
   * reports, like [UnreadableEntryException.outbox]. Nothing claims it or checks for a second
   * outbox here, so build one per store, in the one owner that sends these writes.
   */
  fun <E> outbox(
    name: String,
    store: OutboxStore<E>,
    send: suspend (payload: E, id: EntryId) -> Unit,
    revert: suspend (E) -> Unit = {},
    retry: RetryPolicy? = null,
    onFailure: ((Throwable) -> Unit)? = null,
  ): Outbox<E>

  /**
   * The general form of [optimisticStore] and [optimisticStores]. Use one of those, and use this
   * only for a server that answers some changes with nothing and others with a value.
   *
   * It builds an [OptimisticQueryStore] per key over the store [base] gives for it, with every
   * waiting change kept as a row in the outbox called [name]. Build the set once, in the one owner
   * of this data, as with [outbox]. [base] runs once per key, and its store serves that key for as
   * long as this set lives. [keySerializer] stores the keys, and each key's serialized form orders
   * its rows. Keys that are equal must serialize to the same form, so they share one order.
   *
   * @param apply returns the value with [mutation] applied. It must be cheap, pure and idempotent,
   *   as [optimisticStore] describes.
   * @param deliver sends one change with the row's id as the idempotency key. It returns
   *   [Delivery.Acknowledged] to apply the change to the truth with [apply], or [Delivery.Replaced]
   *   to replace the truth with the server's value. It runs on this scope's dispatcher. Throw
   *   [PermanentFailure] for a rejection that retrying cannot fix.
   * @param retry decides whether a failed send retries and when. Null uses the factory's policy.
   * @param onFailure hears every failure the set and its outbox report. Null uses the factory's
   *   handler.
   */
  fun <K : Any, T, M> optimisticStores(
    name: String,
    base: (key: K) -> QueryStore<T>,
    keySerializer: KSerializer<K>,
    serializer: KSerializer<M>,
    apply: (current: T?, mutation: M) -> T?,
    deliver: suspend (key: K, mutation: M, id: EntryId) -> Delivery<T>,
    retry: RetryPolicy? = null,
    onFailure: ((Throwable) -> Unit)? = null,
  ): OptimisticQueryStores<K, T, M>

  /**
   * Builds data scopes that share their failure handlers, clock, outbox store set, network state,
   * outbox scheduler and default retry policies.
   */
  @SubclassOptInRequired(DelicateDataLayerApi::class)
  interface Factory {
    /** A data scope whose work runs in [scope] and stops when it is cancelled. */
    fun create(scope: CoroutineScope): DataScope

    /**
     * What this factory's running outboxes still have to send. It is [OutboxBacklog.Ready] if any
     * row can send now, including a backoff whose deadline has passed. Otherwise it is the earliest
     * [OutboxBacklog.BackingOff], or [OutboxBacklog.Empty].
     *
     * It emits once every running outbox has read its rows, then on each change. Only running
     * outboxes count, so rows in a store that no outbox has opened are left out. The factory's
     * [OutboxScheduler] hears the same changes.
     */
    val backlog: Flow<OutboxBacklog>

    companion object {
      /**
       * Builds the factory an app or a test hands its repositories.
       *
       * Failed fetches go to [onFetchFailure], which defaults to [onFailure]. Every other failure
       * that has no handler of its own goes to [onFailure]: failed commands, outbox drains, rows
       * that fail for good or cannot be decoded, a store flow that fails while a query watches it,
       * an optimistic store's `apply` that throws, a retry policy or scheduler that throws, and
       * query work that fails without a fetch, like an `invalidate()` whose store throws. A query,
       * runner, outbox or optimistic store built with a handler of its own reports there instead.
       *
       * A handler that throws never breaks the data layer. The data scope keeps running, and the
       * part that reported goes on. The handler's exception goes to the `CoroutineExceptionHandler`
       * in the context of the data scope's coroutine scope when there is one, once per throw.
       * Otherwise it goes to the platform's uncaught exception handler, as any uncaught coroutine
       * failure does. That handler ends the app by default on Android and iOS, and prints the
       * exception by default on the JVM. A throw from [onFailure] for a failure of the factory
       * itself, like a scheduler that throws, always takes that uncaught path, since it belongs to
       * no data scope.
       *
       * Outboxes open their rows on [stores], drain while [online] is true, and tell [scheduler]
       * when rows wait. Create scopes on `Dispatchers.Default`, not Main, as [ActionRunner]
       * explains.
       *
       * [retry] decides for every outbox built without a policy of its own whether a failed send
       * may succeed later, like after a timeout, and how long its row waits first. When the policy
       * declines, or send throws [PermanentFailure], the row fails at once: its undo runs, and the
       * next row on its key sends. The default retries every failure with no attempt limit.
       *
       * [fetchRetry] sets for every query built without a policy of its own how long the fetches it
       * starts on its own hold off after failures, as [DataQuery.state] describes. When it declines
       * a failure, the query fetches on its own again only after a requested fetch succeeds. The
       * default waits 1 second after the first failure in a row, doubles the wait up to 1 minute,
       * and never declines.
       */
      operator fun invoke(
        onFailure: (Throwable) -> Unit,
        onFetchFailure: (Throwable) -> Unit = onFailure,
        online: StateFlow<Boolean> = MutableStateFlow(true),
        stores: OutboxStores = InMemoryOutboxStores(),
        scheduler: OutboxScheduler = OutboxScheduler.None,
        clock: Clock = Clock.System,
        retry: RetryPolicy = RetryPolicy.exponential(),
        fetchRetry: RetryPolicy = RetryPolicy.exponential(initial = 1.seconds, max = 1.minutes),
      ): Factory =
        RealDataScopeFactory(
          onFailure = onFailure,
          onFetchFailure = onFetchFailure,
          online = online,
          stores = stores,
          scheduler = scheduler,
          clock = clock,
          retry = retry,
          fetchRetry = fetchRetry,
        )
    }
  }

  companion object
}

/** [DataScope.outbox] with the serializer for a `@Serializable` payload. */
inline fun <reified E> DataScope.outbox(
  name: String,
  noinline send: suspend (payload: E, id: EntryId) -> Unit,
  noinline revert: suspend (E) -> Unit = {},
  retry: RetryPolicy? = null,
  noinline onFailure: ((Throwable) -> Unit)? = null,
): Outbox<E> = outbox(name, serializer<E>(), send, revert, retry, onFailure)
