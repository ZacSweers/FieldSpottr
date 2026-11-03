// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.work

import android.content.Context
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import dev.zacsweers.fieldspottr.data.core.OutboxBacklog
import dev.zacsweers.fieldspottr.data.work.WorkManagerOutboxScheduler.Companion.DRAIN_AT_DEADLINE
import dev.zacsweers.fieldspottr.data.work.WorkManagerOutboxScheduler.Companion.DRAIN_NOW
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The requests the scheduler keeps queued, read back from a test WorkManager. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WorkManagerOutboxSchedulerTest {
  private val context: Context = RuntimeEnvironment.getApplication()
  private val clock =
    object : Clock {
      override fun now(): Instant = NOW
    }
  private val workManager: WorkManager
    get() = WorkManager.getInstance(context)

  private val scheduler = WorkManagerOutboxScheduler(lazy { workManager }, clock)

  @BeforeTest
  fun setUp() {
    WorkManagerTestInitHelper.initializeTestWorkManager(
      context,
      Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
    )
  }

  @Test
  fun `rows that can send now keep one immediate drain queued across reports`() {
    scheduler.rowsWaiting(OutboxBacklog.Ready)
    scheduler.rowsWaiting(OutboxBacklog.Ready)

    val infos = pending(DRAIN_NOW)
    assertThat(infos).hasSize(1)
    val info = infos.single()
    assertThat(info.state).isEqualTo(WorkInfo.State.ENQUEUED)
    assertThat(info.initialDelayMillis).isEqualTo(0L)
    assertThat(info.constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
  }

  @Test
  fun `a backoff queues a drain at its deadline and the newest deadline replaces it`() {
    scheduler.rowsWaiting(OutboxBacklog.BackingOff(NOW + 5.minutes))
    assertThat(pending(DRAIN_AT_DEADLINE).single().initialDelayMillis)
      .isEqualTo(5.minutes.inWholeMilliseconds)

    scheduler.rowsWaiting(OutboxBacklog.BackingOff(NOW + 2.minutes))
    assertThat(pending(DRAIN_AT_DEADLINE).single().initialDelayMillis)
      .isEqualTo(2.minutes.inWholeMilliseconds)
  }

  @Test
  fun `a deadline already passed drains now`() {
    scheduler.rowsWaiting(OutboxBacklog.BackingOff(NOW - 1.minutes))

    assertThat(pending(DRAIN_AT_DEADLINE).single().initialDelayMillis).isEqualTo(0L)
  }

  private fun pending(name: String): List<WorkInfo> =
    workManager.getWorkInfosForUniqueWork(name).get().filterNot { it.state.isFinished }
}

private val NOW = Instant.fromEpochSeconds(1_700_000_000)
