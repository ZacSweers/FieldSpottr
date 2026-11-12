// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core.impl

import dev.zacsweers.fieldspottr.data.core.KeyedDataQueries
import kotlin.time.Instant

/**
 * A staleness rule that an owner adds on top of a query's freshness, like the one
 * [KeyedDataQueries.invalidateAll] sets for every key. The query tells it when each fetch starts
 * and when one writes, so the rule can tell fetches made after the mark from those made before it.
 * The query asks it only about values that have a fetch time and are not invalidated, since the
 * others are stale anyway.
 */
internal interface StaleMark {
  /** Called as a fetch starts. Returns a token that [fetchWritten] gets back if it writes. */
  fun fetchStarted(): Long

  /**
   * Called once the fetch that got [started] from [fetchStarted] has written its value, fetched at
   * [fetchedAt], at [now].
   */
  fun fetchWritten(started: Long, fetchedAt: Instant, now: Instant)

  /** Whether data fetched at [fetchedAt] is stale at [now] under this rule alone. */
  fun isStale(fetchedAt: Instant, now: Instant): Boolean

  /** No rule, for a query outside a keyed set. */
  object None : StaleMark {
    override fun fetchStarted(): Long = 0

    override fun fetchWritten(started: Long, fetchedAt: Instant, now: Instant) {}

    override fun isStale(fetchedAt: Instant, now: Instant): Boolean = false
  }
}
