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
 *                                 unforced fetch() --+
 *                                   ^ cancelled and  |
 *                                   | replaced       v
 * requestFetch(force = true) --> forced fetch() --> store.writeFetched(value, ...) --> data
 *   joins a running forced          |
 *   fetch                           +--> status: Fetching, then Idle or Failed
 * ```
 *
 * Checks, fetches and invalidations run one at a time on the query's own key, so a check never
 * races a fetch. `fetchedAt` is the clock time just before the fetch. A fetch never writes over
 * data with a newer fetch time, like a value another writer stored while it ran.
 *
 * A forced request that arrives while an unforced fetch runs cancels that fetch before it writes
 * and runs a forced fetch in its place. The unforced request's handle follows the forced fetch, so
 * a background caller never sees `Cancelled` because a user pulled to refresh. [status] shows
 * `Fetching(forced = true)` as soon as the forced request takes over a running fetch. When the
 * takeover lands while the check still reads the store, it shows once that read returns.
 */
@SubclassOptInRequired(DelicateDataLayerApi::class)
interface DataQuery<T> {
  /**
   * The stored value and when it was fetched, or null before the first fetch.
   *
   * Collecting this may fetch. Each collector checks freshness on the first value it reads, even
   * one that takes one value and leaves, like `first()`, and fetches when the data is stale. Each
   * collector reads the store once. Collectors share one queued check. When a stored value goes
   * away, one check runs however many collectors see it. Use [stored] to watch the same values
   * without starting work.
   *
   * After a fetch that the query started on its own fails, the next ones it starts on its own hold
   * off for a window. Those are fetches for a collector and the recheck after the stored value goes
   * away. Requests a caller makes with [requestFetch] never widen the window and never hold off.
   * The query's [RetryPolicy] sets the window: after the nth failure in a row it is `delayAfter(n,
   * failure)`, cut to the policy's `maxDelay`. A zero wait holds off nothing. A policy that throws
   * an [Exception] is reported to the failure handler, and the query holds off for the policy's
   * `maxDelay`, or for 1 hour when that has no bound or throws. A policy that throws an [Error],
   * like the `NotImplementedError` of `TODO()`, records no hold-off. The status still shows the
   * fetch's failure, and the handler for failed fetches still hears it. Then the Error fails the
   * fetch's handle and goes to the handler for other failures. The default policy waits 1 second
   * after the first failure, doubles the wait with each further failure, and stops at 1 minute. A
   * success clears the window. Nothing retries when the window ends, so the next collector after it
   * fetches. The count of failures in a row lasts until the policy's `maxDelay` has passed since
   * the window ended, or 1 hour when that has no bound. A failure after that counts as the first
   * again.
   *
   * When the policy declines a failure by returning null, the query never fetches on its own again
   * for it. No collector, recheck or reconnect fetches until a [requestFetch] succeeds, like one
   * from a retry button. [status] keeps showing the failure for such a button. A request that fails
   * leaves the decline in place. When a later fetch fails while the decline holds, the count of
   * failures in a row goes on from the declined one. A keyed set keeps the declined records of at
   * most 1,024 keys. When a new one would pass that cap, the set drops the one declined longest
   * ago, and that key fetches on its own once more.
   *
   * When the network comes back, a query with a collector checks again, and fetches when the data
   * is stale or the last fetch failed, whatever the window says. A declined failure holds off this
   * check too. A failure made offline never holds off once the device is online again, even when
   * the policy declined it, so a collector that arrives after a reconnect fetches at once. Offline,
   * the query still fetches, so a first load fails fast and shows [FetchStatus.Failed].
   */
  val data: Flow<Fetched<T>?>

  /**
   * The values [data] shows, without checking freshness or fetching. For readers that must not
   * start work, like a background job that only reports.
   */
  val stored: Flow<Fetched<T>?>

  /** What this query's fetches are doing. */
  val status: StateFlow<FetchStatus>

  /**
   * Fetches when [force] is true or the stored data is stale. Force it when the user asks for new
   * data, like a pull to refresh. Forced requests made at the same time share one fetch. A forced
   * request never joins an unforced fetch, since that fetch may have read the server before a write
   * the caller just made. It replaces one instead, as [DataQuery] describes. An unforced request
   * joins a fetch in flight, so it never fetches fresh data twice. The handle follows the fetch
   * this request ran or joined, and [status] shows `Fetching` while it runs.
   */
  fun requestFetch(force: Boolean): Operation

  /**
   * Marks the stored data stale without fetching, so the next collector fetches. It runs after any
   * fetch in flight, so a fetch that started before a write cannot leave old data marked fresh. An
   * unforced request made after this returns fetches again. When the data is on screen, use a
   * forced [requestFetch] instead.
   *
   * When its turn comes, it skips the mark if a fetch that started after this call has already
   * written. That fetch read the server after the change, so its data stays fresh. This happens
   * when a forced request replaces an unforced fetch queued ahead of the invalidation. A fetch that
   * failed or was cancelled leaves the mark to land as usual.
   */
  fun invalidate(): Operation
}

/** [DataQuery.requestFetch] with `force = true`: fetches now, as a pull to refresh asks. */
fun <T> DataQuery<T>.refresh(): Operation = requestFetch(force = true)

/** [DataQuery.requestFetch] with `force = false`: fetches only if the stored data is stale. */
fun <T> DataQuery<T>.fetchIfStale(): Operation = requestFetch(force = false)
