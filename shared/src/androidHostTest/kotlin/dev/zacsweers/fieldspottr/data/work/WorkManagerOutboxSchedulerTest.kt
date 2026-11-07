// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.work

import android.content.Context
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import dev.zacsweers.fieldspottr.data.core.OutboxBacklog
import dev.zacsweers.fieldspottr.data.work.WorkManagerOutboxScheduler.Companion.DRAIN_AT_DEADLINE
import dev.zacsweers.fieldspottr.data.work.WorkManagerOutboxScheduler.Companion.DRAIN_NOW
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
  // Lets the drain a test starts return
  private val finishDrain = CompletableDeferred<Unit>()

  @BeforeTest
  fun setUp() {
    WorkManagerTestInitHelper.initializeTestWorkManager(
      context,
      Configuration.Builder()
        .setExecutor(SynchronousExecutor())
        .setTaskExecutor(SynchronousExecutor())
        .setWorkerFactory(
          object : WorkerFactory() {
            override fun createWorker(
              appContext: Context,
              workerClassName: String,
              workerParameters: WorkerParameters,
            ): ListenableWorker = HeldDrain(appContext, workerParameters, finishDrain)
          }
        )
        .build(),
    )
  }

  @Test
  fun `rows that can send now queue an immediate drain`() {
    scheduler.rowsWaiting(OutboxBacklog.Ready)

    val info = pending(DRAIN_NOW).single()
    assertThat(info.state).isEqualTo(WorkInfo.State.ENQUEUED)
    assertThat(info.initialDelayMillis).isEqualTo(0L)
    assertThat(info.constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
  }

  @Test
  fun `a report while a drain finishes queues another drain to run after it`() {
    scheduler.rowsWaiting(OutboxBacklog.Ready)
    val first = pending(DRAIN_NOW).single().id
    // The drain runs and has seen the backlog empty, but has not returned yet
    WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(first)
    assertThat(workManager.getWorkInfoById(first).get()!!.state).isEqualTo(WorkInfo.State.RUNNING)

    // A row enqueued meanwhile, which that drain will never see
    scheduler.rowsWaiting(OutboxBacklog.Ready)
    finishDrain.complete(Unit)

    assertThat(workManager.getWorkInfoById(first).get()!!.state).isEqualTo(WorkInfo.State.SUCCEEDED)
    val next = pending(DRAIN_NOW).single()
    assertThat(next.id).isNotEqualTo(first)
    assertThat(next.state).isEqualTo(WorkInfo.State.ENQUEUED)
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

/**
 * A drain that runs until [finish] completes. It runs unconfined, so it starts on the thread that
 * meets its constraints and returns on the one that completes [finish], and a test sees each state
 * as soon as the call that causes it returns.
 */
private class HeldDrain(
  context: Context,
  params: WorkerParameters,
  private val finish: CompletableDeferred<Unit>,
) : CoroutineWorker(context, params) {
  @Suppress("OVERRIDE_DEPRECATION")
  override val coroutineContext: CoroutineDispatcher = Dispatchers.Unconfined

  override suspend fun doWork(): Result {
    finish.await()
    return Result.success()
  }
}
