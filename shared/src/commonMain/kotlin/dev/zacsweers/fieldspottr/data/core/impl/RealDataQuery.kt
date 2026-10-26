// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core.impl

import dev.zacsweers.fieldspottr.data.core.DataQuery
import dev.zacsweers.fieldspottr.data.core.DelicateDataLayerApi
import dev.zacsweers.fieldspottr.data.core.FetchStatus
import dev.zacsweers.fieldspottr.data.core.Fetched
import dev.zacsweers.fieldspottr.data.core.Freshness
import dev.zacsweers.fieldspottr.data.core.OnPrevious
import dev.zacsweers.fieldspottr.data.core.Operation
import dev.zacsweers.fieldspottr.data.core.Ordering
import dev.zacsweers.fieldspottr.data.core.QueryStore
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * [DataQuery] over a runner of its own, which orders its checks, fetches and writes on one key. Its
 * watcher runs in [scope] while anyone collects [data], and [holds] keeps the query's owner, like a
 * keyed query's entry, for as long as the watcher or a request still needs it.
 */
@OptIn(ExperimentalAtomicApi::class, DelicateDataLayerApi::class)
internal class RealDataQuery<T>(
  private val actions: RealDataActions,
  private val store: QueryStore<T>,
  private val fetch: suspend () -> T,
  private val freshness: Freshness,
  private val clock: Clock,
  private val scope: CoroutineScope,
  private val holds: QueryHolds = QueryHolds.None,
) : DataQuery<T> {
  // Private, so only this query's commands chain on it
  private val key = Any()
  // How many observers collect data, and the watcher that runs while any do. Compare-and-set only.
  private val watching = AtomicReference(Watching(observers = 0, watcher = null))

  /**
   * The store's values. The first observer starts the watcher, which checks the store when it first
   * reads it. A later observer checks when it subscribes, since the data can go stale meanwhile.
   */
  override val data: Flow<Fetched<T>?> = flow {
    if (!startWatching()) {
      holds.holdUntilSettled(fetchIfStale())
    }
    try {
      emitAll(store.data)
    } finally {
      stopWatching()
    }
  }

  override val stored: Flow<Fetched<T>?> = store.data

  /** Counts an observer in, and starts the watcher for the first one. Returns whether it did. */
  private fun startWatching(): Boolean {
    while (true) {
      val current = watching.load()
      if (current.observers > 0) {
        if (watching.compareAndSet(current, Watching(current.observers + 1, current.watcher))) {
          return false
        }
        continue
      }
      val watcher = scope.launch(start = CoroutineStart.LAZY) { watch() }
      if (watching.compareAndSet(current, Watching(observers = 1, watcher))) {
        // The watcher holds the query's owner, so the requests it makes are held as well
        if (holds.tryAcquire()) {
          watcher.invokeOnCompletion { holds.release() }
          watcher.start()
        } else {
          watcher.cancel()
        }
        return true
      }
      watcher.cancel()
    }
  }

  /** Counts an observer out, and stops the watcher after the last one. */
  private fun stopWatching() {
    while (true) {
      val current = watching.load()
      val last = current.observers == 1
      val next =
        if (last) {
          Watching(observers = 0, watcher = null)
        } else {
          Watching(current.observers - 1, current.watcher)
        }
      if (watching.compareAndSet(current, next)) {
        if (last) {
          current.watcher?.cancel()
        }
        return
      }
    }
  }

  /**
   * One ordered view of the store for every observer, so each change starts one check however many
   * observers see it, and however late. Its first value checks the stored time, joining a check
   * already queued. A value that later goes away is checked again once the work already queued is
   * done, so a value cleared while a fetch is still writing gets a check after that write.
   */
  private suspend fun watch() {
    var first = true
    var previous: Fetched<T>? = null
    store.data.collect { current ->
      when {
        first -> holds.holdUntilSettled(fetchIfStale())
        current == null && previous != null -> holds.holdUntilSettled(recheck())
      }
      first = false
      previous = current
    }
  }

  override val status: StateFlow<FetchStatus>
    field = MutableStateFlow<FetchStatus>(FetchStatus.Idle)

  override fun requestFetch(force: Boolean): Operation =
    if (force) {
      fetchNow()
    } else {
      fetchIfStale()
    }

  /**
   * Joins a forced fetch that has not settled, even behind checks queued after it, else fetches
   * now. An invalidate() or mutate queued after it stops the search, so a later request never joins
   * a fetch from before the data changed. See [requestFetch].
   */
  private fun fetchNow(): Operation =
    actions.runOrJoin(
      Ordering.Keyed(key, OnPrevious.Cancel),
      Request.Forced,
      joins = { it.tag == Request.Forced },
      passes = { it.tag == Request.Check },
    ) {
      fetchOnce(forced = true)
    }

  /**
   * Single flight. Joins the check or fetch that is the newest command on this query's key, else
   * checks the stored time and fetches if stale.
   */
  private fun fetchIfStale(): Operation =
    actions.runOrJoin(
      Ordering.Keyed(key),
      Request.Check,
      joins = { it.tag is Request },
      block = ::checkStale,
    )

  /**
   * Checks the stored time once the work already queued is done. It joins only a check that has not
   * started, which reads the store later anyway, and never work that may have read it already.
   */
  private fun recheck(): Operation =
    actions.runOrJoin(
      Ordering.Keyed(key),
      Request.Check,
      joins = { it.tag == Request.Check && !it.hasStarted },
      block = ::checkStale,
    )

  // The newest command on the key from here on, so a later request waits for it and checks the
  // store again instead of joining a fetch that started before the data changed
  override fun invalidate(): Operation = actions.run(Ordering.Keyed(key)) { store.invalidate() }

  override fun mutate(
    requireFresh: Boolean,
    block: suspend (store: QueryStore<T>) -> Unit,
  ): Operation =
    actions.run(Ordering.Keyed(key)) {
      if (requireFresh && isStale(store.data.first()?.fetchedAt)) {
        fetchOnce(forced = false)
      }
      block(store)
    }

  // Reads the store on this query's key, so it never races a fetch
  private suspend fun checkStale() {
    if (isStale(store.data.first()?.fetchedAt)) {
      fetchOnce(forced = false)
    }
  }

  private fun isStale(at: Instant?): Boolean {
    if (at == null || at <= QueryStore.INVALIDATED) {
      return true
    }
    val now = clock.now()
    // A time in the future means the clock moved back or the record is bad
    return at > now || freshness.isStale(at, now)
  }

  private suspend fun fetchOnce(forced: Boolean) {
    status.value = FetchStatus.Fetching(forced)
    try {
      val fetchedAt = clock.now()
      val value = fetch()
      // A fetch superseded while it ran never writes, even one whose fetch ignored cancellation
      currentCoroutineContext().ensureActive()
      store.write(value, fetchedAt)
      status.value = FetchStatus.Idle
    } catch (t: Throwable) {
      // Stopped by its owner, or replaced by a newer forced fetch that starts only after this one
      // ends and then sets its own status
      if (!currentCoroutineContext().isActive) {
        status.value = FetchStatus.Idle
        throw t
      }
      status.value = FetchStatus.Failed(t)
      // The handle settles Failed and the failure reporter hears it
      throw t
    }
  }

  /** What a request on this query's key is, so a later request can tell whether to join it. */
  private enum class Request {
    Check,
    Forced,
  }

  private class Watching(val observers: Int, val watcher: Job?)
}

/**
 * What keeps a query's owner while the query still needs it, like a keyed query's entry. Holds are
 * taken only while the owner is already held, so they never revive a dropped one.
 */
internal interface QueryHolds {
  /** Takes a hold, or returns false once the owner was dropped. */
  fun tryAcquire(): Boolean

  fun release()

  /** Holds the owner until [operation]'s command finishes, cancelled or not. */
  fun holdUntilSettled(operation: Operation)

  /** A query without an owner to keep. */
  object None : QueryHolds {
    override fun tryAcquire(): Boolean = true

    override fun release() {}

    override fun holdUntilSettled(operation: Operation) {}
  }
}
