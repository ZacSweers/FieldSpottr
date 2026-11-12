// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core.impl

import dev.zacsweers.fieldspottr.data.core.ActionRunner
import dev.zacsweers.fieldspottr.data.core.DataQuery
import dev.zacsweers.fieldspottr.data.core.DataScope
import dev.zacsweers.fieldspottr.data.core.DefaultPayloadFormat
import dev.zacsweers.fieldspottr.data.core.DelicateDataLayerApi
import dev.zacsweers.fieldspottr.data.core.Delivery
import dev.zacsweers.fieldspottr.data.core.EntryId
import dev.zacsweers.fieldspottr.data.core.Freshness
import dev.zacsweers.fieldspottr.data.core.KeyedDataQueries
import dev.zacsweers.fieldspottr.data.core.KeyedMutation
import dev.zacsweers.fieldspottr.data.core.OptimisticQueryStore
import dev.zacsweers.fieldspottr.data.core.OptimisticQueryStores
import dev.zacsweers.fieldspottr.data.core.Outbox
import dev.zacsweers.fieldspottr.data.core.OutboxBacklog
import dev.zacsweers.fieldspottr.data.core.OutboxEntry
import dev.zacsweers.fieldspottr.data.core.OutboxKey
import dev.zacsweers.fieldspottr.data.core.OutboxScheduler
import dev.zacsweers.fieldspottr.data.core.OutboxSender
import dev.zacsweers.fieldspottr.data.core.OutboxStatus
import dev.zacsweers.fieldspottr.data.core.OutboxStore
import dev.zacsweers.fieldspottr.data.core.OutboxStores
import dev.zacsweers.fieldspottr.data.core.QueryStore
import dev.zacsweers.fieldspottr.data.core.RetryPolicy
import dev.zacsweers.fieldspottr.data.core.asCodec
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * The JSON that encodes a key into its rows' ordering key. Defaults are encoded, so a key's
 * ordering key does not change when a later version changes a property's default.
 */
private val ORDER_KEY_JSON = Json { encodeDefaults = true }

/** The ordering key of a single optimistic store's rows, which all share one order. */
private const val WHOLE_QUEUE = ""

/**
 * The factory behind `DataScope.Factory(...)`: the settings every data scope from one factory
 * shares, the outbox names they have running, and what every running outbox still has to send. The
 * names live here, so scopes from one factory cannot run two senders over the same rows. The
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

  override fun create(scope: CoroutineScope, senders: Set<OutboxSender<*>>): DataScope =
    RealDataScope(scope, this, senders)

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
   * Records a sender for [name] until [scope] completes or the returned release runs, whichever
   * comes first. Two senders over one name's rows would drain them on separate runners, which
   * breaks per-key send order, so a second one fails here.
   */
  fun claimOutbox(name: String, scope: CoroutineScope): () -> Unit {
    while (true) {
      val current = runningOutboxes.load()
      check(name !in current) {
        "A sender for outbox $name already runs in another data scope from this factory. Create " +
          "one data scope with each sender, or let the previous data scope's scope finish first."
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
 *
 * It builds an outbox engine for each of [senders] when it is built, so their rows drain from then
 * on. A handle builder attaches to the engine of its key.
 */
@OptIn(DelicateDataLayerApi::class, ExperimentalAtomicApi::class)
private class RealDataScope(
  override val scope: CoroutineScope,
  private val factory: RealDataScopeFactory,
  senders: Set<OutboxSender<*>>,
) : DataScope {
  /** The factory's handler for every failure without a handler of its own, guarded. */
  private val defaultOnFailure = guarded(factory.onFailure)
  /** The factory's handler for failed fetches, guarded. */
  private val defaultOnFetchFailure = guarded(factory.onFetchFailure)
  /** Each sender's engine, by key name. */
  private val engines: Map<String, RealOutbox<*>> = engines(senders)

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

  /**
   * Builds an engine for each of [senders], keyed by name. Names must be unique. It first opens
   * every sender's rows and claims each name opened from the factory's store set, and only then
   * builds the engines, so a failure in the first part leaves nothing running. Opening comes before
   * the claim, so a store that fails to open leaves its name free, and opening again is harmless. A
   * failure releases every claim made, and an engine that fails to build cancels the engines built
   * before it, so the caller can create the scope again. A sender over a store of its own is
   * checked only within this scope, since nothing else knows its store.
   */
  private fun engines(senders: Set<OutboxSender<*>>): Map<String, RealOutbox<*>> {
    if (senders.isEmpty()) {
      return emptyMap()
    }
    val duplicate = senders.groupBy { it.key.name }.entries.firstOrNull { it.value.size > 1 }
    require(duplicate == null) {
      "The senders for this data scope hold ${duplicate?.value?.size} senders for outbox " +
        "${duplicate?.key}. Contribute one sender per outbox name."
    }
    val releases = ArrayList<() -> Unit>()
    // A child of the scope's job, so the engines stop with the scope, and engines built before a
    // failure stop too
    val engineJob = SupervisorJob(scope.coroutineContext[Job])
    val engineScope = scope + engineJob
    try {
      val opened = senders.map { sender ->
        val store = sender.store ?: factory.stores.open(sender.key.name, sender.key.serializer)
        if (sender.store == null) {
          releases += factory.claimOutbox(sender.key.name, scope)
        }
        sender to store
      }
      return opened.associate { (sender, store) ->
        sender.key.name to newEngine(sender, store, engineScope)
      }
    } catch (t: Throwable) {
      engineJob.cancel()
      releases.forEach { it() }
      throw t
    }
  }

  /** The engine that drains [sender]'s rows in [store], in [engineScope]. */
  @Suppress("UNCHECKED_CAST")
  private fun <E> newEngine(
    sender: OutboxSender<E>,
    store: Any,
    engineScope: CoroutineScope,
  ): RealOutbox<E> {
    val onFailure = handlerOf(sender.onFailure)
    val replySerializer = sender.key.replySerializer as KSerializer<Any?>?
    return RealOutbox(
      name = sender.key.name,
      descriptor = sender.key.serializer.descriptor,
      replyDescriptor = sender.key.replySerializer?.descriptor,
      scope = engineScope,
      deliver = sender.deliver,
      replyCodec =
        replySerializer?.let {
          if (sender.store == null) {
            factory.stores.replyCodec(it)
          } else {
            it.asCodec(DefaultPayloadFormat)
          }
        },
      store = store as OutboxStore<E>,
      policy = sender.retry ?: factory.retry,
      online = factory.online,
      clock = factory.clock,
      onFailure = onFailure,
      trackBacklog = { factory.track(engineScope) },
    )
  }

  /**
   * The engine of [key]'s sender, checked against [key]. It throws when this scope has no sender
   * for [key], or when the sender's key differs from [key] in its payload type or its answer type.
   * Both keys must be plain, or both replying with one answer type, since a store would otherwise
   * take an answer of another type as its truth.
   */
  @Suppress("UNCHECKED_CAST")
  private fun <E> engineFor(key: OutboxKey<E, *>): RealOutbox<E> {
    val engine =
      checkNotNull(engines[key.name]) {
        "No sender for outbox ${key.name} in this data scope. Contribute ${key.name}'s sender to " +
          "the set passed to DataScope.Factory.create."
      }
    val replyDescriptor = key.replySerializer?.descriptor
    val matches =
      engine.descriptor == key.serializer.descriptor && engine.replyDescriptor == replyDescriptor
    check(matches) {
      "The sender for outbox ${key.name} has payloads of type ${engine.descriptor} and answers " +
        "of type ${engine.replyDescriptor ?: "none"}, but the handle's key has payloads of type " +
        "${key.serializer.descriptor} and answers of type ${replyDescriptor ?: "none"}, so the " +
        "handle cannot attach. Use the one OutboxKey val for both the sender and the handle."
    }
    return engine as RealOutbox<E>
  }

  override fun <E> outbox(
    key: OutboxKey<E, *>,
    apply: suspend (payload: E, id: EntryId) -> Unit,
    revert: suspend (payload: E, id: EntryId) -> Unit,
    onFailure: ((Throwable) -> Unit)?,
  ): Outbox<E> {
    val owner =
      object : RowOwner<E> {
        override suspend fun apply(payload: E, id: EntryId) = apply(payload, id)

        override suspend fun revert(payload: E, id: EntryId) = revert(payload, id)

        // A plain outbox keeps no state that a sent row changes
        override suspend fun commit(
          entry: OutboxEntry<E>,
          delivery: Delivery<*>?,
          sentAt: Instant,
          expected: OutboxStatus,
        ) {}
      }
    return engineFor(key).attach(owner, handlerOf(onFailure))
  }

  override fun <T, M> optimisticStore(
    key: OutboxKey<M, T>,
    base: QueryStore<T>,
    apply: (current: T?, mutation: M) -> T?,
    onFailure: ((Throwable) -> Unit)?,
  ): OptimisticQueryStore<T, M> {
    val engine = engineFor(key)
    val handler = handlerOf(onFailure)
    // The rows hold the change itself, so the set sees them through a view with a Unit key
    return newStores(
        rows = engine.store.keyedByUnit(),
        attach = { owner -> engine.attach(owner.forChange(), handler).keyedByUnit() },
        orderKey = { WHOLE_QUEUE },
        base = { base },
        apply = apply,
        onFailure = handler,
      )
      .invoke(Unit)
  }

  override fun <K : Any, T, M> optimisticStores(
    key: OutboxKey<KeyedMutation<K, M>, T>,
    base: (key: K) -> QueryStore<T>,
    keySerializer: KSerializer<K>,
    apply: (current: T?, mutation: M) -> T?,
    onFailure: ((Throwable) -> Unit)?,
  ): OptimisticQueryStores<K, T, M> {
    val engine = engineFor(key)
    val handler = handlerOf(onFailure)
    return newStores(
      rows = engine.store,
      attach = { owner -> engine.attach(owner, handler) },
      orderKey = { k -> ORDER_KEY_JSON.encodeToString(keySerializer, k) },
      base = base,
      apply = apply,
      onFailure = handler,
    )
  }

  /** An optimistic store set over [rows] in this scope, reporting to [onFailure], guarded. */
  private fun <K : Any, T, M> newStores(
    rows: OutboxStore<KeyedMutation<K, M>>,
    attach: (RowOwner<KeyedMutation<K, M>>) -> OwnedRows<KeyedMutation<K, M>>,
    orderKey: (K) -> String,
    base: (key: K) -> QueryStore<T>,
    apply: (current: T?, mutation: M) -> T?,
    onFailure: (Throwable) -> Unit,
  ): RealOptimisticQueryStores<K, T, M> =
    RealOptimisticQueryStores(
      rows = rows,
      attach = attach,
      orderKey = orderKey,
      base = base,
      apply = apply,
      clock = factory.clock,
      onFailure = onFailure,
      // A scope with no dispatcher of its own runs on Default, so the views do too
      context = scope.coroutineContext[ContinuationInterceptor] ?: Dispatchers.Default,
    )

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
