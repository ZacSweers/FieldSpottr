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

  data class Fetching(val isRefresh: Boolean) : FetchStatus

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
 * collecting data --+
 *                   +--> fetchIfStale() --> joins a fetch in flight, or checks the stored
 * callers ----------+         |             fetch time against freshness and the clock
 *                             | stale
 *                             v
 * refresh() -------------> fetch() --> store.write(value, fetchedAt) --> store.data --> data
 *   cancels an older          |
 *   refresh                   +--> status: Fetching, then Idle or Failed
 * ```
 *
 * Checks and fetches run one at a time on the query's own key, so a check never races a fetch.
 * `fetchedAt` is the clock time just before the fetch.
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

  /** Fetches now. Cancels an older refresh and queues behind a stale fetch that already started. */
  fun refresh(): Operation {
    val next =
      actions.prepare(Ordering.Keyed(key, OnPrevious.Cancel)) { fetchOnce(isRefresh = true) }
    // Subscribers from here on join the refresh
    inFlight.store(next.operation)
    return next.commit()
  }

  /** Single flight. Joins the fetch in flight, else checks the stored time and fetches if stale. */
  fun fetchIfStale(): Operation {
    while (true) {
      val current = inFlight.load()
      if (current != null && current.state.value !is OperationState.Terminal) {
        return current
      }
      // The check reads the store on this query's key, so it never races a fetch
      val next =
        actions.prepare(Ordering.Keyed(key)) {
          if (isStale(store.data.first()?.fetchedAt)) {
            fetchOnce(isRefresh = false)
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
   * leave the old data marked fresh. Use [refresh] instead when the data is on screen.
   */
  fun invalidate(): Operation = actions.run(Ordering.Keyed(key)) { store.invalidate() }

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

  private suspend fun fetchOnce(isRefresh: Boolean) {
    status.value = FetchStatus.Fetching(isRefresh)
    try {
      val fetchedAt = clock.now()
      store.write(fetch(), fetchedAt)
      status.value = FetchStatus.Idle
    } catch (t: Throwable) {
      // Cancelled by a newer refresh, which owns the status
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
