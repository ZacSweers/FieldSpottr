// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import dev.zacsweers.fieldspottr.data.core.DataScope
import dev.zacsweers.fieldspottr.data.core.OutboxBacklog
import dev.zacsweers.fieldspottr.data.core.awaitDrained
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The drain request's worker. It sends nothing itself. [startOutboxes] makes the app's outboxes
 * run, and the outboxes send in the scopes they were built in, while this awaits the factory's
 * backlog to keep the process alive until nothing can send now. Rows left in a backoff are covered
 * by the deadline request the scheduler queued when the backoff was reported, so they end this run
 * as a success too. A run that outlasts [timeout] retries by WorkManager's backoff.
 */
class OutboxDrainWorker(
  context: Context,
  params: WorkerParameters,
  private val factory: DataScope.Factory,
  private val startOutboxes: () -> Unit,
  private val timeout: Duration = 8.minutes,
) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    startOutboxes()
    return when (withTimeoutOrNull(timeout) { factory.awaitDrained() }) {
      OutboxBacklog.Empty,
      is OutboxBacklog.BackingOff -> Result.success()
      // awaitDrained never returns Ready. A timeout means the network or the server is slow.
      OutboxBacklog.Ready,
      null -> Result.retry()
    }
  }
}

/**
 * Hands the graph's data scope factory and [startOutboxes] to each [OutboxDrainWorker]. Null for
 * every other worker, so WorkManager builds those with its default factory. Set it on the app's
 * WorkManager configuration. [startOutboxes] reads the graph's outbox owners, which constructs the
 * ones not built yet, and their outboxes start draining. The owners are graph singletons, so the
 * outboxes the worker waits for are the ones the app runs, and the one-outbox-per-name check holds.
 */
class OutboxDrainWorkerFactory(
  private val factory: DataScope.Factory,
  private val startOutboxes: () -> Unit,
) : WorkerFactory() {
  override fun createWorker(
    appContext: Context,
    workerClassName: String,
    workerParameters: WorkerParameters,
  ): ListenableWorker? {
    if (workerClassName != OutboxDrainWorker::class.java.name) {
      return null
    }
    return OutboxDrainWorker(appContext, workerParameters, factory, startOutboxes)
  }
}
