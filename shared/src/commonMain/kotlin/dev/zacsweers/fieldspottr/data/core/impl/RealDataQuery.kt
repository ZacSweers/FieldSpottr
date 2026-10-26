// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core.impl

import dev.zacsweers.fieldspottr.data.core.DataQuery
import dev.zacsweers.fieldspottr.data.core.DelicateDataLayerApi
import dev.zacsweers.fieldspottr.data.core.FetchStatus
import dev.zacsweers.fieldspottr.data.core.Fetched
import dev.zacsweers.fieldspottr.data.core.Freshness
import dev.zacsweers.fieldspottr.data.core.KeyedDataQueries
import dev.zacsweers.fieldspottr.data.core.OnPrevious
import dev.zacsweers.fieldspottr.data.core.Operation
import dev.zacsweers.fieldspottr.data.core.Ordering
import dev.zacsweers.fieldspottr.data.core.QueryStore
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive

/** [DataQuery] over a runner of its own, which orders its checks, fetches and writes on one key. */
@OptIn(ExperimentalAtomicApi::class, DelicateDataLayerApi::class)
internal class RealDataQuery<T>(
  private val actions: RealDataActions,
  private val store: QueryStore<T>,
  private val fetch: suspend () -> T,
  private val freshness: Freshness,
  private val clock: Clock,
) : DataQuery<T> {
  // Private, so only this query's commands chain on it
  private val key = Any()
  // The recheck for the store's current absence of a value, until a collector sees a value again.
  // Compare-and-set only. Nothing observes it.
  private val absence = AtomicReference<Request.Recheck?>(null)

  override val data: Flow<Fetched<T>?> = data(onRequest = {})

  override val stored: Flow<Fetched<T>?> = store.data

  /**
   * [data], handing [onRequest] each request it makes, the ones after the store empties included.
   * Every command this query runs reaches a caller as an [Operation] this way or as a return value,
   * which [KeyedDataQueries] relies on to keep a key's query until its work is done.
   */
  internal fun data(onRequest: (Operation) -> Unit): Flow<Fetched<T>?> = flow {
    onRequest(fetchIfStale())
    // The check above covers the store's first value, so only a value that later goes away checks
    // again
    var previous: Fetched<T>? = null
    emitAll(
      store.data.onEach { current ->
        if (current != null) {
          // The store has a value again, so the next absence gets a recheck of its own
          absence.load()?.let { absence.compareAndSet(it, null) }
        } else if (previous != null) {
          onRequest(recheck())
        }
        previous = current
      }
    )
  }

  override val status: StateFlow<FetchStatus>
    field = MutableStateFlow<FetchStatus>(FetchStatus.Idle)

  override fun requestFetch(force: Boolean): Operation =
    if (force) {
      fetchNow()
    } else {
      fetchIfStale()
    }

  /** Joins a forced fetch that has not settled, else fetches now. See [requestFetch]. */
  private fun fetchNow(): Operation =
    actions.runOrJoin(
      Ordering.Keyed(key, OnPrevious.Cancel),
      Request.Forced,
      joins = { it == Request.Forced },
    ) {
      fetchOnce(forced = true)
    }

  /**
   * Single flight. Joins the check or fetch that is the newest command on this query's key, else
   * checks the stored time and fetches if stale.
   */
  private fun fetchIfStale(): Operation =
    actions.runOrJoin(Ordering.Keyed(key), Request.Check, joins = { it is Request }, ::checkStale)

  /**
   * Checks the stored time after a value went away, once the commands already queued on this query
   * are done. Every collector that sees the store empty shares one recheck until a collector sees a
   * value again, including a collector that sees the clear after the recheck settled. Work that
   * started before the value went away is never joined, so a value cleared while a fetch is still
   * writing gets a check after that write.
   */
  private fun recheck(): Operation {
    while (true) {
      val current = absence.load()
      val absent = current ?: Request.Recheck()
      if (absent !== current && !absence.compareAndSet(null, absent)) {
        continue
      }
      absent.handle?.let {
        return it
      }
      val operation =
        actions.runOrJoin(Ordering.Keyed(key), absent, joins = { it === absent }, ::checkStale)
      absent.handle = operation
      return operation
    }
  }

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
  private sealed interface Request {
    data object Check : Request

    data object Forced : Request

    /**
     * The check for one absence of a stored value, shared by the collectors that see it. It keeps
     * nothing of the value that went away, so a handle someone keeps cannot keep that value.
     */
    class Recheck : Request {
      // Set by the first collector to link the recheck. Others join it until then.
      @Volatile var handle: Operation? = null
    }
  }
}
