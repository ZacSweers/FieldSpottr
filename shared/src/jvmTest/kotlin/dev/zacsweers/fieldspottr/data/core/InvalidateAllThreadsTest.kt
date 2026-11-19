// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.isEqualTo
import dev.zacsweers.fieldspottr.data.core.testing.TestThread
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Two invalidateAll() calls on different threads, where the older call stores its record last. Real
 * threads, since only a clock that stalls one caller can open that window.
 */
class InvalidateAllThreadsTest {
  @Test
  fun `an older invalidateAll that stores last never undoes a newer one`() = runBlocking {
    val older = Instant.parse("2026-06-10T12:00:00Z")
    val newer = older + 2.minutes
    // The thread whose clock read stalls, and returns the older time once it resumes
    val stalling = AtomicReference<Thread?>(null)
    val stalled = CountDownLatch(1)
    val resume = CountDownLatch(1)
    val clock =
      object : Clock {
        override fun now(): Instant {
          if (Thread.currentThread() === stalling.get()) {
            stalled.countDown()
            check(resume.await(10, SECONDS)) { "The stalled clock read was never resumed" }
            return older
          }
          return newer
        }
      }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
      val stores = QueryStore.inMemoryByKey<String, String>()
      // Fetched between the two calls, and fresh by freshness alone
      stores("k").write("stored", older + 1.minutes)
      val fetches = AtomicInteger()
      val values =
        DataScope.Factory(onFailure = {}, clock = clock).create(scope).keyed(
          store = stores,
          freshness = Freshness.staleAfter(1.hours),
        ) { key ->
          fetches.incrementAndGet()
          "$key!"
        }

      // The older call takes its ticket first, then stalls in its clock read
      val first = TestThread {
        stalling.set(Thread.currentThread())
        values.invalidateAll()
      }
      check(stalled.await(10, SECONDS)) { "The older call never read the clock" }
      values.invalidateAll()
      resume.countDown()
      first.joinOrFail(10_000)

      // The newer call's record stays, so the value fetched before it is stale
      assertThat(withTimeout(10_000) { values.fetchIfStale("k").await() })
        .isEqualTo(OperationState.Succeeded)
      assertThat(fetches.get()).isEqualTo(1)
    } finally {
      scope.cancel()
    }
  }
}
