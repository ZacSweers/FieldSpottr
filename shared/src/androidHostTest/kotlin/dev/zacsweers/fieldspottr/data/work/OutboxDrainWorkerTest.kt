// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.work

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import assertk.assertThat
import assertk.assertions.isEqualTo
import dev.zacsweers.fieldspottr.data.core.DataScope
import dev.zacsweers.fieldspottr.data.core.DelicateDataLayerApi
import dev.zacsweers.fieldspottr.data.core.OutboxBacklog
import dev.zacsweers.fieldspottr.data.core.OutboxSender
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** What a drain run returns to WorkManager for each way the drain can end. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OutboxDrainWorkerTest {
  private val context: Context = RuntimeEnvironment.getApplication()

  @Test
  fun `an empty backlog ends the run as a success, once the data scope was read`() = runTest {
    var read = 0
    // A real data scope with no senders, which is all the worker needs to read
    val data = DataScope.Factory(onFailure = {}).create(backgroundScope)
    // Through the app's factory, which is what hands the worker the graph's pieces
    val worker =
      TestListenableWorkerBuilder<OutboxDrainWorker>(context)
        .setWorkerFactory(
          OutboxDrainWorkerFactory(FakeFactory(flowOf(OutboxBacklog.Empty))) {
            read++
            data
          }
        )
        .build()

    assertThat(worker.doWork()).isEqualTo(ListenableWorker.Result.success())
    assertThat(read).isEqualTo(1)
  }

  @Test
  fun `rows left in a backoff end the run too, since the deadline has a request of its own`() =
    runTest {
      val worker =
        worker(
          flowOf(OutboxBacklog.BackingOff(Instant.fromEpochSeconds(0) + 1.minutes)),
          DataScope.Factory(onFailure = {}).create(backgroundScope),
        )

      assertThat(worker.doWork()).isEqualTo(ListenableWorker.Result.success())
    }

  @Test
  fun `a drain that outlasts the timeout retries`() = runTest {
    val worker =
      worker(
        flow { awaitCancellation() },
        DataScope.Factory(onFailure = {}).create(backgroundScope),
        timeout = 1.seconds,
      )

    assertThat(worker.doWork()).isEqualTo(ListenableWorker.Result.retry())
  }

  private fun worker(
    backlog: Flow<OutboxBacklog>,
    data: DataScope,
    timeout: Duration = 8.minutes,
  ): OutboxDrainWorker =
    TestListenableWorkerBuilder<OutboxDrainWorker>(context)
      .setWorkerFactory(
        object : WorkerFactory() {
          override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
          ): ListenableWorker =
            OutboxDrainWorker(appContext, workerParameters, FakeFactory(backlog), { data }, timeout)
        }
      )
      .build()
}

/**
 * A factory whose backlog is [backlog]. The worker reads the data scope it is handed and never
 * creates one itself.
 */
@OptIn(DelicateDataLayerApi::class)
private class FakeFactory(override val backlog: Flow<OutboxBacklog>) : DataScope.Factory {
  override fun create(scope: CoroutineScope, senders: Set<OutboxSender<*>>): DataScope =
    error("The drain worker creates no data scope")
}
