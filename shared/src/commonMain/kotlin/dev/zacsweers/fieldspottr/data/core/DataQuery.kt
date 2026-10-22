// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach

sealed interface FetchStatus {
  data object Idle : FetchStatus

  /** A fetch is running. [forced] is true when a forced [DataQuery.requestFetch] started it. */
  data class Fetching(val forced: Boolean) : FetchStatus

  data class Failed(val cause: Throwable) : FetchStatus
}

/**
 * When a successful fetch stops counting as fresh. A fetch time ahead of the clock is always stale.
 */
sealed interface Freshness {
  companion object {
    /** Stale once [duration] has passed since the fetch. */
    fun staleAfter(duration: Duration): Freshness = FixedFreshness(duration)

    /**
     * Decides on every check, for rules that change over time or depend on when the data was
     * fetched. [isStale] must be cheap and must not suspend, since collectors call it.
     */
    fun dynamic(isStale: (fetchedAt: Instant, now: Instant) -> Boolean): Freshness =
      DynamicFreshness(isStale)
  }
}

internal class FixedFreshness(val staleAfter: Duration) : Freshness

internal class DynamicFreshness(val isStale: (fetchedAt: Instant, now: Instant) -> Boolean) :
  Freshness

/**
 * A [QueryStore] plus the fetch that fills it, with single-flight staleness checks. Built by
 * [Factory.create].
 *
 * ```
 * collecting data -------------+
 *                              +--> joins a fetch in flight, or checks the stored
 * requestFetch(force = false) -+    fetch time against freshness and the clock
 *                                   | stale
 *                                   v
 * requestFetch(force = true) --> fetch() --> store.write(value, fetchedAt) --> data
 *   cancels an older forced         |
 *   fetch                           +--> status: Fetching, then Idle or Failed
 * ```
 *
 * Checks, fetches, invalidations and mutations run one at a time on the query's own key, so a check
 * never races a fetch and a fetch never races a write. `fetchedAt` is the clock time just before
 * the fetch.
 */
@OptIn(ExperimentalAtomicApi::class)
class DataQuery<T>
internal constructor(
  private val actions: DataActions,
  private val store: QueryStore<T>,
  private val fetch: suspend () -> T,
  private val freshness: Freshness,
  private val clock: Clock = Clock.System,
) {
  // Private, so only this query's fetches chain on it
  private val key = Any()
  // Compare-and-set only. Nothing observes it.
  private val inFlight = AtomicReference<Operation?>(null)

  /**
   * The stored value and when it was fetched, or null before the first fetch. Collecting it fetches
   * first when the data is stale, and again whenever the store empties.
   */
  val data: Flow<Fetched<T>?> = flow {
    fetchIfStale()
    emitAll(
      store.data.onEach {
        if (it == null) {
          fetchIfStale()
        }
      }
    )
  }

  val status: StateFlow<FetchStatus>
    field = MutableStateFlow<FetchStatus>(FetchStatus.Idle)

  /**
   * Runs this query's fetch when [force] is true or the stored data is stale.
   *
   * Force it when the user asks for new data, like a pull to refresh. A forced request fetches now,
   * cancels an older forced fetch and queues behind an unforced fetch that already started. Its
   * status shows as `Fetching(forced = true)`.
   *
   * An unforced request is single flight. It joins the fetch in flight, else checks the stored time
   * and fetches only if the data is stale.
   */
  fun requestFetch(force: Boolean): Operation =
    if (force) {
      fetchNow()
    } else {
      fetchIfStale()
    }

  /** Fetches now. Cancels an older forced fetch and queues behind an unforced one that started. */
  private fun fetchNow(): Operation {
    val next = actions.prepare(Ordering.Keyed(key, OnPrevious.Cancel)) { fetchOnce(forced = true) }
    // Requests from here on join this fetch
    inFlight.store(next.operation)
    return next.commit()
  }

  /** Single flight. Joins the fetch in flight, else checks the stored time and fetches if stale. */
  private fun fetchIfStale(): Operation {
    while (true) {
      val current = inFlight.load()
      if (current != null && current.state.value !is OperationState.Terminal) {
        return current
      }
      // The check reads the store on this query's key, so it never races a fetch
      val next =
        actions.prepare(Ordering.Keyed(key)) {
          if (isStale(store.data.first()?.fetchedAt)) {
            fetchOnce(forced = false)
          }
        }
      if (inFlight.compareAndSet(current, next.operation)) {
        return next.commit()
      }
      // Lost the race. next was never published, so dropping it costs nothing. Loop and join the
      // winner.
    }
  }

  /**
   * Marks the stored data stale without fetching, so the next collector fetches. Runs on this
   * query's key after any fetch already in flight, so a fetch that started before a write cannot
   * leave the old data marked fresh. Requests made after this returns run after it, so an unforced
   * one fetches again. Use a forced [requestFetch] instead when the data is on screen.
   */
  fun invalidate(): Operation = runBeforeLaterRequests { store.invalidate() }

  /**
   * Runs [block], a write that changes what this query fetches, on this query's key. It runs after
   * any fetch in flight, and fetches requested after this returns run after it, so no fetch that
   * started before the write can land after it. The key stays held until [block] returns, remote
   * call included.
   *
   * This is on the query because its fetches run on a runner of their own, and commands on a
   * repository's [DataActions] cannot order against them. A repository that also orders its own
   * writes by key, like saves to one item that must land in call order, calls this from inside that
   * action and awaits the handle. The action then holds its key until the mutation finishes.
   *
   * With [requireFresh], a stale store fetches first, so [block] sees current data, like a revision
   * to check before writing. A failed fetch fails the handle and [block] never runs.
   *
   * [block] gets the store, to mark it stale before a remote write whose outcome may be unclear and
   * to write the confirmed result after it.
   */
  fun mutate(requireFresh: Boolean, block: suspend (store: QueryStore<T>) -> Unit): Operation =
    runBeforeLaterRequests {
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
    // behind that fetch and finds the data fresh, so the cost is one store read.
    inFlight.store(null)
    return operation
  }

  private fun isStale(at: Instant?): Boolean {
    if (at == null || at <= QueryStore.INVALIDATED) {
      return true
    }
    val now = clock.now()
    // A time in the future means the clock moved back or the record is bad
    return at > now ||
      when (freshness) {
        is FixedFreshness -> now - at >= freshness.staleAfter
        is DynamicFreshness -> freshness.isStale(at, now)
      }
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

  /** Builds queries that share one scope, one failure handler and one clock. */
  interface Factory {
    fun <T> create(store: QueryStore<T>, fetch: suspend () -> T, freshness: Freshness): DataQuery<T>

    companion object {
      /**
       * Fetches run in [scope] and failures go to [onFailure]. Each query gets its own
       * [DataActions].
       */
      operator fun invoke(
        scope: CoroutineScope,
        onFailure: (Throwable) -> Unit,
        clock: Clock = Clock.System,
      ): Factory =
        object : Factory {
          override fun <T> create(
            store: QueryStore<T>,
            fetch: suspend () -> T,
            freshness: Freshness,
          ): DataQuery<T> = DataQuery(DataActions(scope, onFailure), store, fetch, freshness, clock)
        }
    }
  }
}
