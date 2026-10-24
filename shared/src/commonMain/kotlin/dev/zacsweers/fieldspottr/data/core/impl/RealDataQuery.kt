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
import dev.zacsweers.fieldspottr.data.core.OperationState
import dev.zacsweers.fieldspottr.data.core.Ordering
import dev.zacsweers.fieldspottr.data.core.QueryStore
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

/** [DataQuery] over a runner of its own, which orders its checks, fetches and writes on one key. */
@OptIn(ExperimentalAtomicApi::class, DelicateDataLayerApi::class)
internal class RealDataQuery<T>(
  private val actions: RealDataActions,
  private val store: QueryStore<T>,
  private val fetch: suspend () -> T,
  private val freshness: Freshness,
  private val clock: Clock,
) : DataQuery<T> {
  // Private, so only this query's fetches chain on it
  private val key = Any()
  // Compare-and-set only. Nothing observes it.
  private val inFlight = AtomicReference<InFlight?>(null)

  override val data: Flow<Fetched<T>?> = data(onRequest = {})

  override val stored: Flow<Fetched<T>?> = store.data

  /**
   * [data], handing [onRequest] each fetch request it makes, the ones after the store empties
   * included. Every command this query runs reaches a caller as an [Operation] this way or as a
   * return value, which [KeyedDataQueries] relies on to keep a key's query until its work is done.
   */
  internal fun data(onRequest: (Operation) -> Unit): Flow<Fetched<T>?> = flow {
    onRequest(fetchIfStale())
    emitAll(
      store.data.onEach {
        if (it == null) {
          onRequest(fetchIfStale())
        }
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

  /** Joins a running forced fetch, else fetches now. See [requestFetch]. */
  private fun fetchNow(): Operation {
    while (true) {
      val current = inFlight.load()
      if (current != null && current.forced && !current.operation.isSettled) {
        return current.operation
      }
      val next =
        actions.prepare(Ordering.Keyed(key, OnPrevious.Cancel)) { fetchOnce(forced = true) }
      // Requests from here on join this fetch
      if (inFlight.compareAndSet(current, InFlight(next.operation, forced = true))) {
        return next.commit()
      }
      // Lost the race. next was never published, so dropping it has no effect.
    }
  }

  /** Single flight. Joins the fetch in flight, else checks the stored time and fetches if stale. */
  private fun fetchIfStale(): Operation {
    while (true) {
      val current = inFlight.load()
      if (current != null && !current.operation.isSettled) {
        return current.operation
      }
      // The check reads the store on this query's key, so it never races a fetch
      val next =
        actions.prepare(Ordering.Keyed(key)) {
          if (isStale(store.data.first()?.fetchedAt)) {
            fetchOnce(forced = false)
          }
        }
      if (inFlight.compareAndSet(current, InFlight(next.operation, forced = false))) {
        return next.commit()
      }
      // Lost the race. next was never published, so dropping it has no effect. Loop and join the
      // winner.
    }
  }

  override fun invalidate(): Operation = runBeforeLaterRequests { store.invalidate() }

  override fun mutate(
    requireFresh: Boolean,
    block: suspend (store: QueryStore<T>) -> Unit,
  ): Operation = runBeforeLaterRequests {
    if (requireFresh && isStale(store.data.first()?.fetchedAt)) {
      fetchOnce(forced = false)
    }
    block(store)
  }

  /**
   * Runs [block] on this query's key, then forgets the fetch that later requests would join. A
   * request made after this returns waits for [block] and checks the store again, instead of
   * reusing a fetch that started before [block] changed the data.
   */
  private fun runBeforeLaterRequests(block: suspend () -> Unit): Operation {
    val operation = actions.run(Ordering.Keyed(key), block)
    // This can also forget a fetch requested just after block. A later request then checks again
    // behind that fetch and finds the data fresh, so this adds one store read.
    inFlight.store(null)
    return operation
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
      // Cancelled by a newer forced fetch, which owns the status
      currentCoroutineContext().ensureActive()
      status.value = FetchStatus.Failed(t)
      // The handle settles Failed and the failure reporter hears it
      throw t
    }
  }

  /** The request later ones may join, and whether it was forced. */
  private class InFlight(val operation: Operation, val forced: Boolean)

  private val Operation.isSettled: Boolean
    get() = state.value is OperationState.Terminal
}
