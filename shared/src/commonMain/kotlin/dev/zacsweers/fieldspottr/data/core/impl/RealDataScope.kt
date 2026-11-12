// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core.impl

import dev.zacsweers.fieldspottr.data.core.ActionRunner
import dev.zacsweers.fieldspottr.data.core.DataQuery
import dev.zacsweers.fieldspottr.data.core.DataScope
import dev.zacsweers.fieldspottr.data.core.DelicateDataLayerApi
import dev.zacsweers.fieldspottr.data.core.Delivery
import dev.zacsweers.fieldspottr.data.core.EntryId
import dev.zacsweers.fieldspottr.data.core.Freshness
import dev.zacsweers.fieldspottr.data.core.KeyedDataQueries
import dev.zacsweers.fieldspottr.data.core.KeyedMutation
import dev.zacsweers.fieldspottr.data.core.OptimisticQueryStores
import dev.zacsweers.fieldspottr.data.core.Outbox
import dev.zacsweers.fieldspottr.data.core.OutboxBacklog
import dev.zacsweers.fieldspottr.data.core.OutboxScheduler
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
import kotlin.time.Instant
import kotlinx.collections.immutable.persistentHashMapOf
import kotlinx.collections.immutable.persistentHashSetOf
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.KSerializer

/**
 * The factory behind `DataScope.Factory(...)`: the settings every data scope from one factory
 * shares, the outbox names they have running, and what every running outbox still has to send. The
 * names live here, so scopes from one factory cannot run two outboxes over the same rows. The
 * backlogs live here, so one combined state reaches [backlog] and the scheduler.
 */
@OptIn(ExperimentalAtomicApi::class, DelicateDataLayerApi::class)
internal class RealDataScopeFactory(
  /** Hears every failure that has no handler of its own. Each data scope guards it. */
  val onFailure: (Throwable) -> Unit,
  /** Hears failed fetches of queries built without a handler of their own. */
  val onFetchFailure: (Throwable) -> Unit,
  val online: StateFlow<Boolean>,
  val stores: OutboxStores,
  private val scheduler: OutboxScheduler,
  val clock: Clock,
  /** The policy of every outbox built without one of its own. */
  val retry: RetryPolicy,
  /** The hold-off policy of every query built without one of its own. */
  val fetchRetry: RetryPolicy,
) : DataScope.Factory {
  // Updated by compare-and-set only. Nothing observes it, so it needs no StateFlow.
  private val runningOutboxes = AtomicReference(persistentHashSetOf<String>())
  // Each running outbox's last reported backlog, null until its first read. Updated by
  // compare-and-set only.
  private val reported = AtomicReference(persistentHashMapOf<Any, OutboxBacklog?>())
  // The combined backlog, null while a running outbox has not read its rows yet
  private val combined = MutableStateFlow<OutboxBacklog?>(OutboxBacklog.Empty)
  // The last combined result, so the scheduler hears each change once
  private val told = AtomicReference<OutboxBacklog?>(null)
  // One publisher at a time, so the scheduler hears changes in order. A report made while one
  // publishes marks it dirty, and the publisher goes round again.
  private val publishing = AtomicBoolean(false)
  private val dirty = AtomicBoolean(false)

  // A backoff whose deadline the wall clock has passed reads as Ready, since the outbox's timer
  // follows a clock that can stop while the device sleeps, so a waiter waits for the send instead
  override val backlog: Flow<OutboxBacklog> =
    combined.filterNotNull().map { it.asOf(clock.now()) }.distinctUntilChanged()

  override fun create(scope: CoroutineScope): DataScope = RealDataScope(scope, this)

  /**
   * [onFailure] for failures that belong to the factory itself, like a scheduler that throws. It
   * has no scope, so a throw from [onFailure] goes to the platform's uncaught exception handler, as
   * [throwUncaught] describes.
   */
  private val onFactoryFailure: (Throwable) -> Unit = { failure ->
    try {
      onFailure(failure)
    } catch (thrown: Throwable) {
      throwUncaught(thrown)
    }
  }

  /**
   * Counts a new outbox in [scope] as running, with its backlog unknown until it first reads its
   * rows, and stops counting it when the scope completes. Returns the callback the outbox reports
   * its backlog through. A scope without a job never completes, so its outbox stays counted for
   * good, the same way [claimOutbox] keeps such an outbox's name claimed.
   */
  fun track(scope: CoroutineScope): (OutboxBacklog) -> Unit {
    val token = Any()
    report(token, null)
    scope.coroutineContext[Job]?.invokeOnCompletion {
      reported.update { it.removing(token) }
      publish()
    }
    return { state -> report(token, state) }
  }

  private fun report(token: Any, state: OutboxBacklog?) {
    reported.update { it.putting(token, state) }
    publish()
  }

  /**
   * Combines the reports into [combined] and tells the scheduler when the result changed to rows
   * that wait. An empty result is kept but not told, as [OutboxScheduler] explains. A scheduler
   * that throws is reported to [onFactoryFailure], whichever data scope's report published, and
   * neither holds up the drain that reported nor the next report.
   */
  private fun publish() {
    dirty.store(true)
    while (dirty.load() && publishing.compareAndSet(expectedValue = false, newValue = true)) {
      try {
        dirty.store(false)
        val next = combinedOf(reported.load().values)
        combined.value = next
        if (next != null && next != told.load()) {
          told.store(next)
          runCatching {
              when (next) {
                OutboxBacklog.Empty -> Unit
                is OutboxBacklog.Waiting -> scheduler.rowsWaiting(next)
              }
            }
            .onFailure(onFactoryFailure)
        }
      } finally {
        publishing.store(false)
      }
    }
  }

  /**
   * Combines the running outboxes' backlogs: Ready if any outbox is ready, otherwise null if any
   * outbox has not read its rows yet, otherwise BackingOff with the earliest deadline, otherwise
   * Empty.
   */
  private fun combinedOf(states: Collection<OutboxBacklog?>): OutboxBacklog? {
    var unknown = false
    var earliest: Instant? = null
    for (state in states) {
      when (state) {
        OutboxBacklog.Ready -> return OutboxBacklog.Ready
        null -> unknown = true
        is OutboxBacklog.BackingOff ->
          if (earliest == null || state.until < earliest) {
            earliest = state.until
          }
        OutboxBacklog.Empty -> Unit
      }
    }
    return when {
      unknown -> null
      earliest != null -> OutboxBacklog.BackingOff(earliest)
      else -> OutboxBacklog.Empty
    }
  }

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
 * Hands [thrown] to the platform's uncaught exception handler without failing any data scope. It
 * throws in a root coroutine that has no job and no exception handler, so the failure takes the
 * path of any uncaught coroutine failure. On the JVM and Android that is the thread's uncaught
 * exception handler, which by default ends an Android app. On iOS it is the process's unhandled
 * exception path, which by default ends the app too. The coroutine starts undispatched, so this
 * happens before the call returns, on the caller's thread.
 */
@OptIn(DelicateCoroutinesApi::class)
internal fun throwUncaught(thrown: Throwable) {
  GlobalScope.launch(start = CoroutineStart.UNDISPATCHED) { throw thrown }
}

/** This backlog as it stands at [now]: a backoff whose deadline has passed can send now. */
private fun OutboxBacklog.asOf(now: Instant): OutboxBacklog =
  if (this is OutboxBacklog.BackingOff && until <= now) {
    OutboxBacklog.Ready
  } else {
    this
  }

/**
 * [DataScope] over one coroutine scope. Every runner, query and outbox gets a runner of its own in
 * that scope.
 *
 * Every failure handler reaches its parts through [guarded], once: the factory's handlers when the
 * scope is built, and each handler of its own when the part is built. So no part ever calls a
 * handler that can throw, and none has to guard it again.
 */
@OptIn(DelicateDataLayerApi::class)
private class RealDataScope(
  override val scope: CoroutineScope,
  private val factory: RealDataScopeFactory,
) : DataScope {
  /** The factory's handler for every failure without a handler of its own, guarded. */
  private val defaultOnFailure = guarded(factory.onFailure)
  /** The factory's handler for failed fetches, guarded. */
  private val defaultOnFetchFailure = guarded(factory.onFetchFailure)

  /**
   * [handler] made safe to call from paths that must not fail, like a drain or a watcher. A throw
   * from [handler] goes to [handOff] and never reaches the caller.
   */
  private fun guarded(handler: (Throwable) -> Unit): (Throwable) -> Unit = { failure ->
    try {
      handler(failure)
    } catch (thrown: Throwable) {
      handOff(thrown)
    }
  }

  /**
   * Passes [thrown], from a failure handler, to the scope's `CoroutineExceptionHandler` when it has
   * one. Otherwise it goes to the platform's uncaught exception handler, as [throwUncaught]
   * describes, and the scope keeps running. An exception handler that throws itself takes that path
   * too.
   */
  private fun handOff(thrown: Throwable) {
    val handler = scope.coroutineContext[CoroutineExceptionHandler]
    if (handler == null) {
      throwUncaught(thrown)
      return
    }
    try {
      handler.handleException(scope.coroutineContext, thrown)
    } catch (e: Throwable) {
      if (e !== thrown) {
        e.addSuppressed(thrown)
      }
      throwUncaught(e)
    }
  }

  /** The handler of a part built with [own], guarded, or the factory's handler when it is null. */
  private fun handlerOf(own: ((Throwable) -> Unit)?): (Throwable) -> Unit =
    if (own == null) {
      defaultOnFailure
    } else {
      guarded(own)
    }

  override fun newActionRunner(onFailure: ((Throwable) -> Unit)?): ActionRunner =
    RealActionRunner(scope, handlerOf(onFailure))

  override fun <T> query(
    store: QueryStore<T>,
    freshness: Freshness,
    retry: RetryPolicy?,
    onFailure: ((Throwable) -> Unit)?,
    fetch: suspend () -> T,
  ): DataQuery<T> {
    val own = onFailure?.let(::guarded)
    return newQuery(store, fetch, freshness, retry, own)
  }

  override fun <K : Any, T> keyed(
    store: (key: K) -> QueryStore<T>,
    freshness: (key: K) -> Freshness,
    maxFetches: Int,
    retry: RetryPolicy?,
    onFailure: ((Throwable) -> Unit)?,
    fetch: suspend (key: K) -> T,
  ): KeyedDataQueries<K, T> {
    require(maxFetches > 0) { "maxFetches must be positive, was $maxFetches" }
    val permits =
      if (maxFetches == Int.MAX_VALUE) {
        null
      } else {
        Semaphore(maxFetches)
      }
    // Guarded once for the set, so every key's query shares it
    val own = onFailure?.let(::guarded)
    return RealKeyedDataQueries(factory.clock) { key, context ->
      val keyFetch: suspend () -> T =
        if (permits == null) {
          { fetch(key) }
        } else {
          // The fetch time is taken before the wait, so a queued fetch never reads as fresher
          { permits.withPermit { fetch(key) } }
        }
      newQuery(
        store(key),
        keyFetch,
        freshness(key),
        retry,
        own,
        holdOff = context.holdOff,
        staleMark = context.staleMark,
        holds = context.holds,
      )
    }
  }

  override fun <E> outbox(
    name: String,
    serializer: KSerializer<E>,
    send: suspend (E, EntryId) -> Unit,
    revert: suspend (E) -> Unit,
    retry: RetryPolicy?,
    onFailure: ((Throwable) -> Unit)?,
  ): Outbox<E> {
    val handler = handlerOf(onFailure)
    return claimed(name, serializer) { store ->
      newOutbox(name, store, send, revert, retry, handler)
    }
  }

  override fun <E> outbox(
    name: String,
    store: OutboxStore<E>,
    send: suspend (E, EntryId) -> Unit,
    revert: suspend (E) -> Unit,
    retry: RetryPolicy?,
    onFailure: ((Throwable) -> Unit)?,
  ): Outbox<E> = newOutbox(name, store, send, revert, retry, handlerOf(onFailure))

  /**
   * An outbox over [store] that names itself [name] in the failures it reports to [onFailure],
   * which is already guarded.
   */
  private fun <E> newOutbox(
    name: String,
    store: OutboxStore<E>,
    send: suspend (E, EntryId) -> Unit,
    revert: suspend (E) -> Unit,
    retry: RetryPolicy?,
    onFailure: (Throwable) -> Unit,
  ): Outbox<E> =
    RealOutbox(
      name = name,
      scope = scope,
      actions = RealActionRunner(scope, onFailure),
      send = send,
      revert = revert,
      store = store,
      policy = retry ?: factory.retry,
      online = factory.online,
      clock = factory.clock,
      onFailure = onFailure,
      trackBacklog = { factory.track(scope) },
    )

  override fun <K : Any, T, M> optimisticStores(
    name: String,
    base: (key: K) -> QueryStore<T>,
    keySerializer: KSerializer<K>,
    serializer: KSerializer<M>,
    apply: (current: T?, mutation: M) -> T?,
    deliver: suspend (key: K, mutation: M, id: EntryId) -> Delivery<T>,
    retry: RetryPolicy?,
    onFailure: ((Throwable) -> Unit)?,
  ): OptimisticQueryStores<K, T, M> {
    val handler = handlerOf(onFailure)
    return claimed(name, KeyedMutation.serializer(keySerializer, serializer)) { rows ->
      RealOptimisticQueryStores(
        rows = rows,
        // The set drops changes by writing their rows, so it needs the store beside the outbox
        outbox = { send ->
          newOutbox(name, rows, send = send, revert = {}, retry = retry, onFailure = handler)
        },
        keySerializer = keySerializer,
        base = base,
        apply = apply,
        deliver = deliver,
        clock = factory.clock,
        onFailure = handler,
        // A scope with no dispatcher of its own runs on Default, so the views do too
        context = scope.coroutineContext[ContinuationInterceptor] ?: Dispatchers.Default,
      )
    }
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

  /**
   * A query in this scope that hears the factory's network state. It reports to [onFailure], which
   * is already guarded, or to the factory's handlers when that is null, and holds off by [retry],
   * or by the factory's `fetchRetry` when that is null. A keyed set passes the [holdOff] record and
   * the [staleMark] rule it keeps per key, and [holds] on the key's entry.
   */
  private fun <T> newQuery(
    store: QueryStore<T>,
    fetch: suspend () -> T,
    freshness: Freshness,
    retry: RetryPolicy?,
    onFailure: ((Throwable) -> Unit)?,
    holdOff: FetchHoldOff = FetchHoldOff.inMemory(),
    staleMark: StaleMark = StaleMark.None,
    holds: QueryHolds = QueryHolds.None,
  ) =
    RealDataQuery(
      store,
      fetch,
      freshness,
      factory.clock,
      scope,
      onFetchFailure = onFailure ?: defaultOnFetchFailure,
      onFailure = onFailure ?: defaultOnFailure,
      online = factory.online,
      retry = retry ?: factory.fetchRetry,
      holdOff = holdOff,
      staleMark = staleMark,
      holds = holds,
    )
}
