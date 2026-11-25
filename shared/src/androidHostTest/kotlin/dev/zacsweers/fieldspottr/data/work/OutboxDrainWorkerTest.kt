// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.work

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import dev.zacsweers.fieldspottr.data.core.DataScope
import dev.zacsweers.fieldspottr.data.core.DelicateDataLayerApi
import dev.zacsweers.fieldspottr.data.core.EntryId
import dev.zacsweers.fieldspottr.data.core.InMemoryOutboxStores
import dev.zacsweers.fieldspottr.data.core.OutboxBacklog
import dev.zacsweers.fieldspottr.data.core.OutboxKey
import dev.zacsweers.fieldspottr.data.core.OutboxSender
import dev.zacsweers.fieldspottr.data.core.sender
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
  fun `an empty backlog ends the run as a success, once the senders were started`() = runTest {
    var started = 0
    var startedBeforeTheWait = false
    // Records whether the senders were started by the time the worker reads the backlog
    val backlog: Flow<OutboxBacklog> = flow {
      startedBeforeTheWait = started == 1
      emit(OutboxBacklog.Empty)
    }
    // Through the app's factory, which is what hands the worker the graph's pieces
    val worker =
      TestListenableWorkerBuilder<OutboxDrainWorker>(context)
        .setWorkerFactory(OutboxDrainWorkerFactory(FakeFactory(backlog)) { started += 1 })
        .build()

    assertThat(worker.doWork()).isEqualTo(ListenableWorker.Result.success())
    assertThat(started).isEqualTo(1)
    assertThat(startedBeforeTheWait).isTrue()
  }

  @Test
  fun `a run after a restart drains a stored row with no repository built`() = runTest {
    val stores = InMemoryOutboxStores()
    // Left by the process before the restart
    stores.open(Notes.name, Notes.serializer).insert(EntryId("left"), "k", "a")
    val sent = mutableListOf<String>()
    val failures = mutableListOf<Throwable>()
    val factory = DataScope.Factory(onFailure = { failures += it }, stores = stores)
    // Creates the data scope with the senders only, as the graph's app data scope does
    val worker =
      TestListenableWorkerBuilder<OutboxDrainWorker>(context)
        .setWorkerFactory(
          OutboxDrainWorkerFactory(factory) {
            factory.create(backgroundScope, setOf(Notes.sender { payload, _ -> sent += payload }))
          }
        )
        .build()

    assertThat(worker.doWork()).isEqualTo(ListenableWorker.Result.success())
    assertThat(sent).containsExactly("a")
    assertThat(failures).isEmpty()
  }

  @Test
  fun `rows left in a backoff end the run too, since the deadline has a request of its own`() =
    runTest {
      val worker = worker(flowOf(OutboxBacklog.BackingOff(Instant.fromEpochSeconds(0) + 1.minutes)))

      assertThat(worker.doWork()).isEqualTo(ListenableWorker.Result.success())
    }

  @Test
  fun `a drain that outlasts the timeout retries`() = runTest {
    val worker = worker(flow { awaitCancellation() }, timeout = 1.seconds)

    assertThat(worker.doWork()).isEqualTo(ListenableWorker.Result.retry())
  }

  private fun worker(
    backlog: Flow<OutboxBacklog>,
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
            OutboxDrainWorker(appContext, workerParameters, FakeFactory(backlog), {}, timeout)
        }
      )
      .build()

  private companion object {
    val Notes = OutboxKey<String>("notes")
  }
}

/** A factory whose backlog is [backlog]. The worker never creates a data scope itself. */
@OptIn(DelicateDataLayerApi::class)
private class FakeFactory(override val backlog: Flow<OutboxBacklog>) : DataScope.Factory {
  override fun create(scope: CoroutineScope, senders: Set<OutboxSender<*>>): DataScope =
    error("The drain worker creates no data scope")
}
