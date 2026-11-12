// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core.impl

import dev.zacsweers.fieldspottr.data.core.RetryPolicy
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * How many fetches the query started on its own failed in a row, the [window] the last one opened,
 * when the next fetch may start, and whether the last failure happened while offline. [keep] is the
 * policy's longest wait, as [HoldOffWindow.keep] describes. A [declined] record is one whose policy
 * declined the last failure. It holds off until a success clears it, so its [window], [keep] and
 * [until] mean nothing.
 */
internal class HoldOff(
  val failures: Int,
  val window: Duration,
  val keep: Duration,
  val until: Instant,
  val offline: Boolean,
  val declined: Boolean = false,
) {
  /**
   * Whether this record no longer matters at [now]. That is so once its window ended more than
   * [keep] ago, so a failure after that starts the count again at 1. It is also so when [until]
   * lies further ahead of [now] than the window itself, which only a clock set back can cause, so
   * the record never holds longer than its window. A keyed set so keeps records only for keys that
   * failed within about one window plus [keep]. A [declined] record is never spent.
   */
  fun isSpent(now: Instant): Boolean = !declined && (now - until > keep || until - now > window)
}

/**
 * The hold-off after one failure: its [length], and how long after it ends the record still counts
 * the failures in a row. [keep] is the policy's longest wait, so a failure inside it moves on to
 * the policy's next wait, even after a zero wait.
 */
internal class HoldOffWindow(val length: Duration, val keep: Duration)

/**
 * When a query's fetches that it starts on its own may run again after they failed. Each failure in
 * a row opens the window [holdOffWindow] gives for it, and a success clears it. Nothing retries
 * when the window ends. The next collector or reconnect does. When the policy declines a failure,
 * the record holds off until a success clears it. A spent record, as [HoldOff.isSpent] describes,
 * counts as cleared, and subclasses drop such records when they read or write.
 *
 * A query reads and writes it only from commands on its own key, so one call at a time. Subclasses
 * decide where the record lives, like a keyed set's map that outlives each key's query.
 */
internal abstract class FetchHoldOff {
  /** The record that is not spent at [now], or null. */
  protected abstract fun load(now: Instant): HoldOff?

  /** Replaces the record with [next], or clears it when null. */
  protected abstract fun store(next: HoldOff?, now: Instant)

  /**
   * Whether a fetch the query starts on its own at [now] must wait. A failure made offline never
   * holds once the device is [online] again, so a screen that returns after a reconnect it missed
   * fetches at once. That includes a failure the policy declined.
   */
  fun holdsOff(now: Instant, online: Boolean): Boolean {
    val current = load(now) ?: return false
    if (current.offline && online) {
      return false
    }
    return current.declined || now < current.until
  }

  /**
   * Whether the policy declined the last failure, so even the check after a reconnect must wait.
   * The offline rule of [holdsOff] applies here too.
   */
  fun declines(now: Instant, online: Boolean): Boolean {
    val current = load(now) ?: return false
    if (current.offline && online) {
      return false
    }
    return current.declined
  }

  /**
   * Records a failure at [now], made while [offline] or not. [window] gives the hold-off for the
   * count of failures in a row, this one included, or null when the policy declines it. The count
   * goes on from a declined record, which only a failure made offline or a dropped keyed record
   * lets a fetch the query starts on its own reach. When [window] throws, nothing is recorded.
   */
  fun failed(now: Instant, offline: Boolean, window: (failures: Int) -> HoldOffWindow?) {
    val failures = (load(now)?.failures ?: 0) + 1
    val next = window(failures)
    val record =
      if (next == null) {
        HoldOff(failures, Duration.ZERO, Duration.ZERO, now, offline, declined = true)
      } else {
        HoldOff(failures, next.length, next.keep, now + next.length, offline)
      }
    store(record, now)
  }

  /** Records a fetch that succeeded at [now], which clears the window or the decline. */
  fun succeeded(now: Instant) {
    if (load(now) != null) {
      store(null, now)
    }
  }

  companion object {
    /** A record of its own, for a query that is not part of a keyed set. */
    fun inMemory(): FetchHoldOff = InMemoryHoldOff()
  }
}

/**
 * The hold-off after the [failures]th failure in a row, which threw [failure], or null when the
 * policy declines it. Its length is the policy's wait, cut to its [RetryPolicy.maxDelay]. A
 * negative wait counts as zero, which holds off nothing. The record keeps counting for the bound,
 * or for [UNBOUNDED_HOLD_OFF] when the policy sets none.
 *
 * An [Exception] from the policy is reported to [onFailure]. The fetch then holds off for a window
 * that ends, so a bug in a policy never stops a query's own fetches for good. When
 * [RetryPolicy.delayAfter] throws, the window is the bound, or [UNBOUNDED_HOLD_OFF] when the policy
 * sets none. When [RetryPolicy.maxDelay] throws, the bound is unknown, so both the length and the
 * time the record keeps counting are [UNBOUNDED_HOLD_OFF]. Any other throw, like the
 * `NotImplementedError` of `TODO()`, passes through to the caller.
 */
internal fun RetryPolicy.holdOffWindow(
  failures: Int,
  failure: Throwable,
  onFailure: (Throwable) -> Unit,
): HoldOffWindow? {
  val bound =
    try {
      maxDelay.coerceAtLeast(Duration.ZERO)
    } catch (e: Exception) {
      onFailure(e)
      return HoldOffWindow(UNBOUNDED_HOLD_OFF, UNBOUNDED_HOLD_OFF)
    }
  val keep =
    if (bound.isInfinite()) {
      UNBOUNDED_HOLD_OFF
    } else {
      bound
    }
  val wait =
    try {
      delayAfter(failures, failure) ?: return null
    } catch (e: Exception) {
      onFailure(e)
      return HoldOffWindow(keep, keep)
    }
  return HoldOffWindow(minOf(wait.coerceAtLeast(Duration.ZERO), bound), keep)
}

/**
 * The longest wait of a policy that sets no bound or whose bound throws. A record keeps counting
 * failures this long after its window ends, and a policy that throws holds off this long.
 */
internal val UNBOUNDED_HOLD_OFF = 1.hours

@OptIn(ExperimentalAtomicApi::class)
private class InMemoryHoldOff : FetchHoldOff() {
  // Atomic for visibility only, since commands on one key can run on different threads
  private val current = AtomicReference<HoldOff?>(null)

  override fun load(now: Instant): HoldOff? {
    val record = current.load() ?: return null
    if (record.isSpent(now)) {
      current.store(null)
      return null
    }
    return record
  }

  override fun store(next: HoldOff?, now: Instant) {
    current.store(next)
  }
}
