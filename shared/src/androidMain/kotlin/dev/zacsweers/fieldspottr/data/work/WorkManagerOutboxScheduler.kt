// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.work

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dev.zacsweers.fieldspottr.data.core.OutboxBacklog
import dev.zacsweers.fieldspottr.data.core.OutboxScheduler
import java.util.concurrent.TimeUnit
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Keeps a drain request queued while rows wait, so the process comes back once the network is there
 * even when the app was killed meanwhile, and stays until the rows are gone. While rows can send
 * now, one request stays queued under [DRAIN_NOW]. It is enqueued with KEEP, so every report after
 * the first finds the request already there and costs nothing. A backoff queues a request under
 * [DRAIN_AT_DEADLINE], delayed until the deadline. It is enqueued with REPLACE, so each report
 * moves it to the newest reported deadline, which is always the earliest among the waiting rows.
 * Both requests run [OutboxDrainWorker].
 *
 * [workManager] is read on the first report, not at construction, so the app can finish the graph
 * that the worker factory needs before WorkManager initializes. This depends on the core API and
 * androidx.work only, like the SQLDelight and DataStore stores, so it can move to an artifact of
 * its own.
 */
class WorkManagerOutboxScheduler(
  private val workManager: Lazy<WorkManager>,
  private val clock: Clock = Clock.System,
) : OutboxScheduler {
  override fun rowsWaiting(backlog: OutboxBacklog.Waiting) {
    when (backlog) {
      OutboxBacklog.Ready ->
        workManager.value.enqueueUniqueWork(
          DRAIN_NOW,
          ExistingWorkPolicy.KEEP,
          drain(Duration.ZERO),
        )
      is OutboxBacklog.BackingOff -> {
        // A deadline already passed sends now, which the in-process drainer does too
        val delay = (backlog.until - clock.now()).coerceAtLeast(Duration.ZERO)
        workManager.value.enqueueUniqueWork(
          DRAIN_AT_DEADLINE,
          ExistingWorkPolicy.REPLACE,
          drain(delay),
        )
      }
    }
  }

  private fun drain(delay: Duration): OneTimeWorkRequest =
    OneTimeWorkRequestBuilder<OutboxDrainWorker>()
      .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
      .setInitialDelay(delay.inWholeMilliseconds, TimeUnit.MILLISECONDS)
      // For a worker that timed out, not for the rows, whose own backoff lives on each row
      .setBackoffCriteria(
        BackoffPolicy.EXPONENTIAL,
        30.seconds.inWholeMilliseconds,
        TimeUnit.MILLISECONDS,
      )
      .build()

  companion object {
    /** The unique name of the request for rows that can send now. */
    const val DRAIN_NOW = "outbox-drain"
    /** The unique name of the request for the earliest backoff deadline. */
    const val DRAIN_AT_DEADLINE = "outbox-drain-at-deadline"
  }
}
