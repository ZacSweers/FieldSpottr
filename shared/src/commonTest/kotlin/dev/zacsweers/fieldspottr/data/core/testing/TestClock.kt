// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core.testing

import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope

/**
 * A clock that follows the test scheduler's virtual time. `advanceTimeBy` moves fetch times,
 * freshness checks and outbox backoff deadlines together with the `delay` timers that wake them, so
 * a test moves one thing instead of two.
 *
 * [shift] moves this clock alone, for a device clock that jumps, like one set back past a stored
 * fetch time.
 *
 * Seed stored fetch times from [now], never from `Clock.System`. A time from the real clock can be
 * ahead of this one, and a fetch time ahead of the clock always reads as stale.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TestClock(private val scheduler: TestCoroutineScheduler, start: Instant = DEFAULT_START) :
  Clock {
  // The instant at virtual time zero, so now() reads start however far the scheduler has run
  private val origin = start - scheduler.currentTime.milliseconds
  // Written from the test body. Volatile because stores on other dispatchers read the clock too.
  @Volatile private var shifted = Duration.ZERO

  override fun now(): Instant = origin + scheduler.currentTime.milliseconds + shifted

  /** Moves this clock by [by], forward or back, without moving virtual time. */
  fun shift(by: Duration) {
    shifted += by
  }

  companion object {
    /** A fixed start, so a test reads the same times on every run. */
    val DEFAULT_START: Instant = Instant.parse("2026-01-01T00:00:00Z")
  }
}

/** A [TestClock] on this test's scheduler, reading [start] now. */
fun TestScope.testClock(start: Instant = TestClock.DEFAULT_START): TestClock =
  TestClock(testScheduler, start)
