// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.isEqualTo
import dev.zacsweers.fieldspottr.data.core.impl.invoke
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * A recheck that runs and fails on another thread before the collector that submitted it gets its
 * handle back. Real threads, since only a dispatcher that holds the submitting call can open that
 * window.
 */
class ClearPublicationTest {

  @Test
  fun `one clear runs one fetch when its recheck finishes before the submitter returns`() =
    runBlocking {
      val pauseNextSubmission = AtomicBoolean(false)
      val firstJobFinished = CountDownLatch(1)
      val returnFromFirstSubmission = CountDownLatch(1)
      // Runs work on Default, and holds the call that dispatched the next job until released
      val dispatcher =
        object : CoroutineDispatcher() {
          override fun dispatch(context: CoroutineContext, block: Runnable) {
            val pauseSubmitter = pauseNextSubmission.compareAndSet(true, false)
            Dispatchers.Default.dispatch(context) {
              block.run()
              if (pauseSubmitter) {
                firstJobFinished.countDown()
              }
            }
            if (pauseSubmitter) {
              check(returnFromFirstSubmission.await(8, TimeUnit.SECONDS))
            }
          }
        }
      val owner = CoroutineScope(SupervisorJob() + dispatcher)
      val reported = AtomicInteger()
      val attempts = AtomicInteger()
      val failure = IllegalStateException("offline")
      val data = DataScope.Factory(onFailure = { reported.incrementAndGet() }).create(owner)
      val store = QueryStore.inMemory(Fetched(1, Clock.System.now()))
      val query =
        data.query(
          store,
          fetch = {
            attempts.incrementAndGet()
            throw failure
          },
          freshness = Freshness.staleAfter(1.hours),
        )
      val fastSawInitial = CountDownLatch(1)
      val slowSawInitial = CountDownLatch(1)
      val slowSawClear = CountDownLatch(1)
      val releaseSlow = CompletableDeferred<Unit>()
      val afterBothCollectors =
        Executors.newFixedThreadPool(2).asCoroutineDispatcher().use { collectors ->
          val fast =
            launch(collectors) {
              query.data.take(2).collect {
                if (it != null) {
                  fastSawInitial.countDown()
                }
              }
            }
          val slow =
            launch(collectors) {
              query.data.take(2).collect {
                if (it != null) {
                  slowSawInitial.countDown()
                  releaseSlow.await()
                } else {
                  slowSawClear.countDown()
                }
              }
            }
          try {
            check(
              fastSawInitial.await(8, TimeUnit.SECONDS) && slowSawInitial.await(8, TimeUnit.SECONDS)
            )
            assertThat(attempts.get()).isEqualTo(0)
            assertThat(query.mutate(requireFresh = false) {}.await())
              .isEqualTo(OperationState.Succeeded)
            pauseNextSubmission.set(true)
            store.clear()
            check(firstJobFinished.await(8, TimeUnit.SECONDS))
            // The recheck finished on Default, and its submitter has not returned from dispatch yet
            assertThat(query.status.value).isEqualTo(FetchStatus.Failed(failure))
            assertThat(attempts.get()).isEqualTo(1)
            assertThat(reported.get()).isEqualTo(1)
            releaseSlow.complete(Unit)
            check(slowSawClear.await(8, TimeUnit.SECONDS))
            returnFromFirstSubmission.countDown()
            withTimeout(8000L) {
              fast.join()
              slow.join()
              // Queued on the query's key, so it waits for any second recheck the slow collector
              // submitted
              assertThat(query.mutate(requireFresh = false) {}.await())
                .isEqualTo(OperationState.Succeeded)
            }
            attempts.get()
          } finally {
            releaseSlow.complete(Unit)
            returnFromFirstSubmission.countDown()
            fast.cancel()
            slow.cancel()
            owner.cancel()
          }
        }
      assertThat(afterBothCollectors).isEqualTo(1)
    }
}
