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
 * runner ([newActionRunner]), and handles on writes that must reach the server ([outbox],
 * [optimisticStore] and [optimisticStores]). Everything built here runs in [scope] and stops when
 * that scope is cancelled, so the data scope sets how long the work lives.
 *
 * A repository takes one from the graph, or builds one over its own scope with [Factory.create].
 * The factory supplies the failure handlers, clock, outbox stores and network state.
 *
 * Each outbox has two halves that an [OutboxKey] joins. The [OutboxSender]s handed to
 * [Factory.create] send rows, and this scope drains them from its creation, before any repository
 * exists. A repository then builds the handle that writes rows with the same key, and the handle
 * keeps the local state: the optimistic change, the commit of a sent change and the revert of a
 * failed one. A handle needs a sender for its key in this data scope, and each key takes one handle
 * per data scope.
 *
 * These docs use three words. An action is a repository function like `star(id)`. A command is the
 * block that action submits to [ActionRunner.run]. An [Operation] is the handle the caller gets
 * back.
 */
@SubclassOptInRequired(DelicateDataLayerApi::class)
interface DataScope {
  /**
   * The coroutine scope everything here runs in, for operators that need one, like `shareIn`.
   * Cancelling it stops this data scope's runners, queries and outbox drains.
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
   * The handle on the outbox that [key] names: writes that must reach the server, kept as rows and
   * sent in order per key, as [Outbox] describes. The sender for [key] in this data scope sends the
   * rows, and this handle writes them and keeps their local state.
   *
   * ```
   * internal val NotesOutbox = OutboxKey<NoteChange>("notes")
   *
   * private val outbox =
   *   data.outbox(
   *     NotesOutbox,
   *     apply = { change, _ -> notes.update { it + change } },
   *     revert = { change, _ -> notes.update { it - change } },
   *   )
   *
   * fun save(change: NoteChange): OutboxOperation = outbox.enqueue(change)
   * ```
   *
   * It throws when this data scope has no sender for [key], when the sender's key has another
   * payload or answer type, and when [key] already has a handle in this data scope. Build the
   * sender and the handle from the one [OutboxKey] val.
   *
   * A handle lives as long as its data scope and cannot be released. So build it once, in the one
   * owner of this data. An owner that lives shorter than the data scope, like a screen, creates a
   * data scope of its own with [Factory.create] and its senders, and builds the handle there.
   *
   * @param apply makes the optimistic change of each payload [Outbox.enqueue] takes, before its row
   *   is inserted. A throw inserts nothing and fails the handle.
   * @param revert undoes the optimistic change when a row fails for good or is discarded, at most
   *   once per row. It reads only the payload and the row's id, so it works after a restart too. A
   *   row that failed while no handle was attached reverts once a handle attaches.
   * @param onFailure hears this handle's failures: an apply or revert that throws, and enqueue,
   *   retry and discard calls that fail. The sender's handler hears the drain's failures. Null uses
   *   the factory's handler.
   */
  fun <E> outbox(
    key: OutboxKey<E, *>,
    apply: suspend (payload: E, id: EntryId) -> Unit = { _, _ -> },
    revert: suspend (payload: E, id: EntryId) -> Unit = { _, _ -> },
    onFailure: ((Throwable) -> Unit)? = null,
  ): Outbox<E>

  /**
   * An [OptimisticQueryStore] over [base], for the outbox that [key] names. [base] holds the truth,
   * and each change that waits is a row of the outbox. The sender for [key] in this data scope
   * sends the rows, and this store commits each sent change into [base]. `clear()` on the store
   * clears [base] and drops the changes that wait.
   *
   * ```
   * internal val FavoritesOutbox = OutboxKey<FavoriteChange>("favorites")
   *
   * private val store =
   *   data.optimisticStore(
   *     FavoritesOutbox,
   *     base = base,
   *     apply = { current, change ->
   *       if (change.on) {
   *         current.orEmpty() + change.group
   *       } else {
   *         current.orEmpty() - change.group
   *       }
   *     },
   *   )
   * ```
   *
   * The sender decides how the server answers. With [sender], a sent change is applied to the truth
   * with [apply]. With [exchangingSender], the server's value replaces the truth. With
   * [deliveringSender], each send decides. A key built with [OutboxKey.replying] needs a store
   * whose value type is the key's answer type. A plain key fits any store.
   *
   * It throws as [outbox] does: without a sender for [key], for a sender with another payload type,
   * and for a second handle on [key] in this data scope. A handle lives as long as its data scope,
   * as [outbox] describes.
   *
   * @param apply returns the value with [mutation] applied. A null input means nothing is stored,
   *   and a null result removes the value. It runs on the data scope's dispatcher each time the
   *   truth or the rows change, so keep it cheap and pure. Its other rule depends on how the server
   *   answers a send.
   *
   *   With [Delivery.Acknowledged], the store commits a sent change by applying it to the truth. A
   *   fetch that overlapped the send, or a commit that runs again after a restart, can pass it a
   *   value that already holds the change. So it must be idempotent: applying the same change twice
   *   gives the same value as applying it once, as setting a field does. The first 16 of these
   *   commits check idempotence with one more call each, and then the check stops.
   *
   *   With [Delivery.Replaced], the server's value becomes the truth, and [apply] only shows the
   *   changes that still wait. So a change that is not idempotent, like a counter's increment, is
   *   valid with [exchangingSender], and nothing checks it. When a fetch already holds an increment
   *   that still waits, the store shows that increment twice, so the count is an estimate until the
   *   server's answer replaces the truth.
   *
   *   A throw from it is reported to [onFailure] once for as long as the store remembers the
   *   change, and the store's data keeps flowing without that change. When it throws while a sent
   *   change is committed, the truth stays as it was and is marked stale, so the next fetch brings
   *   the server's value. The handle still succeeds, since the server has the change.
   *
   * @param onFailure hears the store's failures: an [apply] that throws or is not idempotent, a
   *   commit that fails, a stored answer that cannot be decoded, and the store's failed calls. The
   *   sender's handler hears the drain's failures. Null uses the factory's handler.
   */
  fun <T, M> optimisticStore(
    key: OutboxKey<M, T>,
    base: QueryStore<T>,
    apply: (current: T?, mutation: M) -> T?,
    onFailure: ((Throwable) -> Unit)? = null,
  ): OptimisticQueryStore<T, M>

  /**
   * [optimisticStore] per key, for a [keyed] query. Every key shares the outbox that [key] names,
   * whose rows are [KeyedMutation]s, so the sender gets the key with the change.
   *
   * ```
   * internal val PermitsOutbox = OutboxKey<KeyedMutation<String, PermitChange>>("permits")
   *
   * // The sender, contributed into the set the data scope is created with
   * PermitsOutbox.sender { change, id -> api.set(change.key, change.mutation, id.value) }
   *
   * // The handle, in the repository
   * private val stores = data.optimisticStores(PermitsOutbox, base = { area -> ... }, apply = ...)
   * ```
   *
   * [base] runs once per key, and its store serves that key for as long as this set lives. So the
   * set keeps one store object per key it has seen. For keys without bound, like thread ids, return
   * small views over one bounded source, like [QueryStore.inMemoryByKey] or a database table.
   *
   * [keySerializer] encodes each key, and each key's encoded form orders its rows. Keys that are
   * equal must encode to the same form, so they share one order. [apply] and [onFailure] work as in
   * [optimisticStore].
   */
  fun <K : Any, T, M> optimisticStores(
    key: OutboxKey<KeyedMutation<K, M>, T>,
    base: (key: K) -> QueryStore<T>,
    keySerializer: KSerializer<K>,
    apply: (current: T?, mutation: M) -> T?,
    onFailure: ((Throwable) -> Unit)? = null,
  ): OptimisticQueryStores<K, T, M>

  /**
   * Builds data scopes that share their failure handlers, clock, outbox store set, network state,
   * outbox scheduler and default retry policies.
   */
  @SubclassOptInRequired(DelicateDataLayerApi::class)
  interface Factory {
    /**
     * A data scope whose work runs in [scope] and stops when it is cancelled. It drains the outbox
     * of each of [senders] from now on, so rows stored before a restart send before any repository
     * builds a handle. A handle built later in the data scope joins its key's sender.
     *
     * Each sender's [OutboxKey.name] names its rows. Two senders with one name throw here. So does
     * a name whose sender over the factory's [OutboxStores] already runs in another data scope from
     * this factory, until that scope ends. A sender with a store of its own is checked only within
     * this call, since nothing else knows its store. Rows under a name that has no sender wait in
     * their store until a data scope is created with a sender for that name.
     */
    fun create(scope: CoroutineScope, senders: Set<OutboxSender<*>> = emptySet()): DataScope

    /**
     * What this factory's running outboxes still have to send. An outbox runs from the creation of
     * a data scope with its sender until that scope ends. It is [OutboxBacklog.Ready] if any row
     * can send now, including a backoff whose deadline has passed. Otherwise it is the earliest
     * [OutboxBacklog.BackingOff], or [OutboxBacklog.Empty].
     *
     * It emits once every running outbox has read its rows, then on each change. Only running
     * outboxes count, so rows under a name with no sender in a created data scope are left out.
     * Rows that wait for a handle to commit or revert them count as sent. The factory's
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
       * runner, sender, outbox or optimistic store built with a handler of its own reports there
       * instead.
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
       * [retry] decides for every sender built without a policy of its own whether a failed send
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

/** [DataScope.optimisticStores] with the serializer for a `@Serializable` key. */
inline fun <reified K : Any, T, M> DataScope.optimisticStores(
  key: OutboxKey<KeyedMutation<K, M>, T>,
  noinline base: (key: K) -> QueryStore<T>,
  noinline apply: (current: T?, mutation: M) -> T?,
  noinline onFailure: ((Throwable) -> Unit)? = null,
  keySerializer: KSerializer<K> = serializer<K>(),
): OptimisticQueryStores<K, T, M> = optimisticStores(key, base, keySerializer, apply, onFailure)
