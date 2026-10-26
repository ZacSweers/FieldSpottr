// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/**
 * One coroutine scope plus the failure handlers, clock, outbox store set and network state its data
 * work uses. Every runner, query and outbox built here runs in that scope and stops when it is
 * cancelled, so the code that picks a data scope states how long its work lives.
 *
 * A repository takes one from the graph, like an app-scoped binding, or builds one over its own
 * scope with [Factory.create].
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
   * Its failures go to [onFailure], like a message that names the owner. When that is null, they go
   * to the factory's handler for failures other than fetches.
   */
  fun newActions(onFailure: ((Throwable) -> Unit)? = null): DataActions

  /**
   * A query over [store] that fills it with [fetch]. Its checks, fetches and store calls run on
   * this scope's dispatcher, usually `Dispatchers.Default`, which suits decoding a response. A
   * fetch that blocks moves that part with `withContext` onto a dispatcher it was given.
   */
  fun <T> query(store: QueryStore<T>, fetch: suspend () -> T, freshness: Freshness): DataQuery<T>

  /**
   * One query per key, each over the [store] for that key, shared by every caller for the key so
   * concurrent collectors share its fetches and its status. [freshness] can differ per key, like a
   * shorter window for today than for later dates. [maxFetches] caps the fetches in flight across
   * all keys. It does not cap store writes, which still run at the same time for different keys.
   *
   * A key's query is dropped as soon as no caller uses it and none of its fetches, checks,
   * invalidations or mutations is queued or running. Its data stays in its store, so the next query
   * for the key picks up the stored value and fetch time.
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
   * An outbox called [name], over the store that the store set opens for that name, draining in
   * this scope. Data scopes from one factory may run only one outbox per name at a time, so build
   * it in the one owner that sends these writes. Cancelling this scope stops the drains and keeps
   * the rows, and the next outbox built for [name] sends them.
   *
   * [serializer] is required even when the stores are in memory, so changing the store set needs no
   * change where outboxes are built.
   *
   * @param send delivers one payload. It runs on this scope's dispatcher, usually
   *   `Dispatchers.Default`, so a send that blocks moves that part with `withContext` onto a
   *   dispatcher it was given.
   * @param revert undoes the optimistic change when a row fails for good or is discarded. It reads
   *   only the payload, so it works after a restart too.
   */
  fun <E> outbox(
    name: String,
    serializer: KSerializer<E>,
    send: suspend (E) -> Unit,
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
    send: suspend (E) -> Unit,
    revert: suspend (E) -> Unit = {},
    retry: RetryPolicy = RetryPolicy.exponential(max = 5.minutes),
  ): Outbox<E>

  /** Builds data scopes that share their failure handlers, clock, store set and network state. */
  @SubclassOptInRequired(DelicateDataLayerApi::class)
  interface Factory {
    /** A data scope whose work runs in [scope] and stops when it is cancelled. */
    fun create(scope: CoroutineScope): DataScope

    companion object
  }

  companion object
}

/** [DataScope.outbox] with the serializer for a `@Serializable` payload. */
inline fun <reified E> DataScope.outbox(
  name: String,
  noinline send: suspend (E) -> Unit,
  noinline revert: suspend (E) -> Unit = {},
  retry: RetryPolicy = RetryPolicy.exponential(max = 5.minutes),
): Outbox<E> = outbox(name, serializer<E>(), send, revert, retry)
