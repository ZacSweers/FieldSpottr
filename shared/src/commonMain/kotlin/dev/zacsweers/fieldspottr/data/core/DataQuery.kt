// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** What a query's fetches are doing, from [DataQuery.status]. */
sealed interface FetchStatus {
  /** No fetch is running, and the last one, if any, succeeded. */
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
     * Never stale once fetched, so the query fetches on the first collect, then only on a forced
     * [DataQuery.requestFetch] or after [DataQuery.invalidate]. For data that changes only when
     * this app changes it.
     */
    val NeverStale: Freshness = FixedFreshness(Duration.INFINITE)

    /**
     * Stale as soon as fetched, so every collect of [DataQuery.data] fetches again unless a fetch
     * is already in flight, and so does every unforced request. For data that must be checked on
     * every visit.
     */
    val AlwaysStale: Freshness = FixedFreshness(Duration.ZERO)

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
 * Checks, fetches and invalidations run one at a time on the query's own key, so a check never
 * races a fetch. `fetchedAt` is the clock time just before the fetch, and a fetch never writes over
 * data with a newer fetch time, like a value another writer put in the store while it ran.
 */
@SubclassOptInRequired(DelicateDataLayerApi::class)
interface DataQuery<T> {
  /**
   * The stored value and when it was fetched, or null before the first fetch. Collecting this may
   * fetch: each collector checks freshness when it subscribes, joining a check already queued, and
   * fetches when the data is stale. That holds for a collector that takes one value and leaves,
   * like `first()`. One watcher checks again whenever a stored value goes away, so each change
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
   * Fetches when [force] is true or the stored data is stale. Force it when the user asks for new
   * data, like a pull to refresh. Forced requests made at the same time share one fetch. An
   * unforced request joins a fetch in flight, else fetches only if the data is stale, so collecting
   * [data] and calling this with `force = false` never fetch fresh data twice. The handle follows
   * the fetch this request ran or joined, and [status] shows `Fetching` while it runs.
   */
  fun requestFetch(force: Boolean): Operation

  /**
   * Marks the stored data stale without fetching, so the next collector fetches. Runs on this
   * query's key after any fetch already in flight, so a fetch that started before a write cannot
   * leave the old data marked fresh. Requests made after this returns run after it, so an unforced
   * one fetches again. Use a forced [requestFetch] instead when the data is on screen.
   */
  fun invalidate(): Operation
}

/** [DataQuery.requestFetch] with `force = true`: fetches now, as a pull to refresh asks. */
fun <T> DataQuery<T>.refresh(): Operation = requestFetch(force = true)

/** [DataQuery.requestFetch] with `force = false`: fetches only if the stored data is stale. */
fun <T> DataQuery<T>.fetchIfStale(): Operation = requestFetch(force = false)
