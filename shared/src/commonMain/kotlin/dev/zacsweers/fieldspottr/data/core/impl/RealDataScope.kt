// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core.impl

import dev.zacsweers.fieldspottr.data.core.DataActions
import dev.zacsweers.fieldspottr.data.core.DataQuery
import dev.zacsweers.fieldspottr.data.core.DataScope
import dev.zacsweers.fieldspottr.data.core.DelicateDataLayerApi
import dev.zacsweers.fieldspottr.data.core.Delivery
import dev.zacsweers.fieldspottr.data.core.EntryId
import dev.zacsweers.fieldspottr.data.core.Freshness
import dev.zacsweers.fieldspottr.data.core.KeyedDataQueries
import dev.zacsweers.fieldspottr.data.core.KeyedMutation
import dev.zacsweers.fieldspottr.data.core.MutableQueryStores
import dev.zacsweers.fieldspottr.data.core.Outbox
import dev.zacsweers.fieldspottr.data.core.OutboxStore
import dev.zacsweers.fieldspottr.data.core.OutboxStores
import dev.zacsweers.fieldspottr.data.core.QueryStore
import dev.zacsweers.fieldspottr.data.core.RetryPolicy
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update
import kotlin.coroutines.ContinuationInterceptor
import kotlin.time.Clock
import kotlinx.collections.immutable.persistentHashSetOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.KSerializer

/**
 * Builds data scopes. Failed fetches go to [onFailure]. Every other failure goes to
 * [onActionFailure], which defaults to [onFailure]: commands on runners, outbox drains, and query
 * commands that fail without a fetch, like an invalidate() whose store throws. Outboxes open their
 * rows on [stores] and drain while [online] is true. Pass scopes on `Dispatchers.Default`, not on
 * Main, as [DataActions] explains.
 */
operator fun DataScope.Factory.Companion.invoke(
  onFailure: (Throwable) -> Unit,
  onActionFailure: (Throwable) -> Unit = onFailure,
  online: StateFlow<Boolean> = MutableStateFlow(true),
  stores: OutboxStores = InMemoryOutboxStores(),
  clock: Clock = Clock.System,
): DataScope.Factory = RealDataScopeFactory(onFailure, onActionFailure, online, stores, clock)

/**
 * The settings every data scope from one factory shares, and the outbox names they have running.
 * The names live here, so scopes from one factory cannot run two outboxes over the same rows.
 */
@OptIn(ExperimentalAtomicApi::class, DelicateDataLayerApi::class)
private class RealDataScopeFactory(
  val onFailure: (Throwable) -> Unit,
  val onActionFailure: (Throwable) -> Unit,
  val online: StateFlow<Boolean>,
  val stores: OutboxStores,
  val clock: Clock,
) : DataScope.Factory {
  // Updated by compare-and-set only. Nothing observes it, so it needs no StateFlow.
  private val runningOutboxes = AtomicReference(persistentHashSetOf<String>())

  override fun create(scope: CoroutineScope): DataScope = RealDataScope(scope, this)

  /**
   * Records an outbox for [name] until [scope] completes or the returned release runs, whichever
   * comes first. Two outboxes over one name's rows would drain them on separate runners, which
   * breaks per-key send order, so a second one fails here.
   */
  fun claimOutbox(name: String, scope: CoroutineScope): () -> Unit {
    while (true) {
      val current = runningOutboxes.load()
      check(name !in current) {
        "An outbox named $name is already running. Build each outbox in one owner, or let the " +
          "previous owner's scope finish first."
      }
      if (runningOutboxes.compareAndSet(current, current.adding(name))) {
        break
      }
    }
    // Once only, so a late release never frees the name for an outbox that claimed it since
    val released = AtomicBoolean(false)
    val release = {
      if (released.compareAndSet(false, true)) {
        runningOutboxes.update { it.removing(name) }
      }
    }
    // A scope without a job never completes, so its outbox keeps the name for good
    val onScopeEnd = scope.coroutineContext[Job]?.invokeOnCompletion { release() }
    return {
      onScopeEnd?.dispose()
      release()
    }
  }
}

/**
 * [DataScope] over one coroutine scope. Every runner, query and outbox gets a runner of its own in
 * that scope.
 */
@OptIn(DelicateDataLayerApi::class)
private class RealDataScope(
  override val scope: CoroutineScope,
  private val factory: RealDataScopeFactory,
) : DataScope {
  override fun newActions(onFailure: ((Throwable) -> Unit)?): DataActions =
    RealDataActions(scope, onFailure ?: factory.onActionFailure)

  override fun <T> query(
    store: QueryStore<T>,
    fetch: suspend () -> T,
    freshness: Freshness,
  ): DataQuery<T> = newQuery(store, fetch, freshness)

  override fun <K : Any, T> keyed(
    store: (key: K) -> QueryStore<T>,
    fetch: suspend (key: K) -> T,
    freshness: (key: K) -> Freshness,
    maxFetches: Int,
  ): KeyedDataQueries<K, T> {
    require(maxFetches > 0) { "maxFetches must be positive, was $maxFetches" }
    val permits =
      if (maxFetches == Int.MAX_VALUE) {
        null
      } else {
        Semaphore(maxFetches)
      }
    return RealKeyedDataQueries { key, holds ->
      val keyFetch: suspend () -> T =
        if (permits == null) {
          { fetch(key) }
        } else {
          // The fetch time is taken before the wait, so a queued fetch never reads as fresher
          { permits.withPermit { fetch(key) } }
        }
      newQuery(store(key), keyFetch, freshness(key), holds)
    }
  }

  override fun <E> outbox(
    name: String,
    serializer: KSerializer<E>,
    send: suspend (E) -> Unit,
    revert: suspend (E) -> Unit,
    retry: RetryPolicy,
  ): Outbox<E> = claimed(name, serializer) { store -> outbox(store, send, revert, retry) }

  override fun <E> outbox(
    store: OutboxStore<E>,
    send: suspend (E) -> Unit,
    revert: suspend (E) -> Unit,
    retry: RetryPolicy,
  ): Outbox<E> =
    RealOutbox(
      scope = scope,
      actions = RealDataActions(scope, factory.onActionFailure),
      send = send,
      revert = revert,
      store = store,
      policy = retry,
      online = factory.online,
      clock = factory.clock,
      onFailure = factory.onActionFailure,
    )

  override fun <K : Any, T, M> mutableStores(
    name: String,
    base: (key: K) -> QueryStore<T>,
    keySerializer: KSerializer<K>,
    serializer: KSerializer<M>,
    apply: (current: T?, mutation: M) -> T?,
    deliver: suspend (key: K, mutation: M, id: EntryId) -> Delivery<T>,
    retry: RetryPolicy,
  ): MutableQueryStores<K, T, M> =
    claimed(name, KeyedMutation.serializer(keySerializer, serializer)) { rows ->
      RealMutableQueryStores(
        rows = rows,
        // The set drops changes by writing their rows, so it needs the store beside the outbox
        outbox = { send -> outbox(rows, send = send, revert = {}, retry = retry) },
        base = base,
        apply = apply,
        deliver = deliver,
        clock = factory.clock,
        onFailure = factory.onActionFailure,
        // A scope with no dispatcher of its own runs on Default, so the views do too
        context = scope.coroutineContext[ContinuationInterceptor] ?: Dispatchers.Default,
      )
    }

  /**
   * Opens [name]'s rows from the store set, claims the name for this scope and builds over the
   * rows. Opening comes first, so a store that fails to open leaves the name free, and opening
   * again is harmless. A [build] that throws, like one whose rows flow fails to open, frees the
   * name too, so the owner can try again.
   */
  private fun <E, R> claimed(
    name: String,
    serializer: KSerializer<E>,
    build: (OutboxStore<E>) -> R,
  ): R {
    val store = factory.stores.open(name, serializer)
    val release = factory.claimOutbox(name, scope)
    return try {
      build(store)
    } catch (t: Throwable) {
      release()
      throw t
    }
  }

  private fun <T> newQuery(
    store: QueryStore<T>,
    fetch: suspend () -> T,
    freshness: Freshness,
    holds: QueryHolds = QueryHolds.None,
  ) =
    RealDataQuery(
      store,
      fetch,
      freshness,
      factory.clock,
      scope,
      onFetchFailure = factory.onFailure,
      onActionFailure = factory.onActionFailure,
      holds = holds,
    )
}
