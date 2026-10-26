// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

sealed interface FetchStatus {
  data object Idle : FetchStatus

  /** A fetch is running. [forced] is true when a forced [DataQuery.requestFetch] started it. */
  data class Fetching(val forced: Boolean) : FetchStatus

  /**
   * The last fetch threw [cause]. The core passes it through unchanged, so a fetch that maps
   * transport errors, like an HTTP 404, to domain errors makes [cause] a domain error.
   */
  data class Failed(val cause: Throwable) : FetchStatus
}

/**
 * When a successful fetch stops counting as fresh. A fetch time ahead of the clock is always stale.
 *
 * Freshness decides when to fetch again, never what stays in memory. The [QueryStore] decides what
 * it keeps and for how long, and the scope that owns the query decides how long the query lives.
 */
sealed interface Freshness {
  /**
   * Whether data fetched at [fetchedAt] is stale at [now]. The query treats a [fetchedAt] ahead of
   * [now] as stale before it asks.
   */
  fun isStale(fetchedAt: Instant, now: Instant): Boolean

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

private class FixedFreshness(private val staleAfter: Duration) : Freshness {
  override fun isStale(fetchedAt: Instant, now: Instant): Boolean = now - fetchedAt >= staleAfter
}

private class DynamicFreshness(private val rule: (fetchedAt: Instant, now: Instant) -> Boolean) :
  Freshness {
  override fun isStale(fetchedAt: Instant, now: Instant): Boolean = rule(fetchedAt, now)
}

/**
 * A [QueryStore] plus the fetch that fills it, with single-flight staleness checks. Built by
 * [DataScope.query].
 *
 * ```
 * collecting data -------------+
 *                              +--> joins a fetch in flight, or checks the stored
 * requestFetch(force = false) -+    fetch time against freshness and the clock
 *                                   | stale
 *                                   v
 * requestFetch(force = true) --> fetch() --> store.write(value, fetchedAt) --> data
 *   joins a running forced          |
 *   fetch                           +--> status: Fetching, then Idle or Failed
 * ```
 *
 * Checks, fetches, invalidations and mutations run one at a time on the query's own key, so a check
 * never races a fetch and a fetch never races a write. `fetchedAt` is the clock time just before
 * the fetch.
 */
@SubclassOptInRequired(DelicateDataLayerApi::class)
interface DataQuery<T> {
  /**
   * The stored value and when it was fetched, or null before the first fetch. Collecting this may
   * fetch: it checks freshness first and fetches when the data is stale, and checks again whenever
   * a stored value goes away. One watcher decides these checks for every collector, so each change
   * starts one check however many collectors see it. Use [stored] to watch the same values without
   * starting any work.
   */
  val data: Flow<Fetched<T>?>

  /**
   * The stored value and when it was fetched, as [data] shows it, without checking freshness or
   * fetching. For readers that must not start work, like a background job that only reports.
   */
  val stored: Flow<Fetched<T>?>

  /** What this query's fetches are doing. */
  val status: StateFlow<FetchStatus>

  /**
   * Runs this query's fetch when [force] is true or the stored data is stale.
   *
   * Force it when the user asks for new data, like a pull to refresh. A forced request joins a
   * forced fetch that has not settled, even one with checks queued after it, unless an [invalidate]
   * or [mutate] queued after it. So callers that force at the same time share one fetch. Otherwise
   * it fetches now, cancels a forced fetch from before that call and queues behind an unforced
   * fetch that already started. Its status shows as `Fetching(forced = true)`, and returns to
   * `Idle` if the fetch is cancelled.
   *
   * An unforced request is single flight. It joins the fetch in flight, else checks the stored time
   * and fetches only if the data is stale.
   */
  fun requestFetch(force: Boolean): Operation

  /**
   * Marks the stored data stale without fetching, so the next collector fetches. Runs on this
   * query's key after any fetch already in flight, so a fetch that started before a write cannot
   * leave the old data marked fresh. Requests made after this returns run after it, so an unforced
   * one fetches again. Use a forced [requestFetch] instead when the data is on screen.
   */
  fun invalidate(): Operation

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
  fun mutate(requireFresh: Boolean, block: suspend (store: QueryStore<T>) -> Unit): Operation
}
