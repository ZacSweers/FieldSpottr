// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
      withTimeout(TEST_TIMEOUT_MILLIS) { assertOneFetchAfterClear() }
    }

  private suspend fun CoroutineScope.assertOneFetchAfterClear() {
    val pauseNextSubmission = AtomicBoolean(false)
    val testThread = Thread.currentThread()
    val ownerJob = SupervisorJob()
    // Every job in the owner's tree when the barrier was armed. The barrier never holds those.
    val armedAmong = AtomicReference<Set<Job>>(emptySet())
    val firstJobFinished = CountDownLatch(1)
    val returnFromFirstSubmission = CountDownLatch(1)
    // Runs work on Default, and holds the next call from another thread that dispatches a job
    // started after the barrier was armed, until released. The clear itself resumes the query's
    // watcher from this thread, and the watcher's recheck is the first new job after that. A job
    // that already ran, like the watcher's backoff timer, can resume from another thread at any
    // time, so the barrier skips it.
    val dispatcher =
      object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
          val offTestThread = Thread.currentThread() !== testThread
          val job = context[Job]
          val newJob = job != null && job !in armedAmong.get()
          val pauseSubmitter =
            offTestThread && newJob && pauseNextSubmission.compareAndSet(true, false)
          Dispatchers.Default.dispatch(context) {
            block.run()
            if (pauseSubmitter) {
              firstJobFinished.countDown()
            }
          }
          if (pauseSubmitter) {
            check(returnFromFirstSubmission.await(WAIT_SECONDS, TimeUnit.SECONDS))
          }
        }
      }
    val owner = CoroutineScope(ownerJob + dispatcher)
    val reported = AtomicInteger()
    val attempts = AtomicInteger()
    val failure = IllegalStateException("offline")
    val data = DataScope.Factory(onFailure = { reported.incrementAndGet() }).create(owner)
    val store = QueryStore.inMemory(Fetched(1, Clock.System.now()))
    val query =
      data.query(store, Freshness.staleAfter(1.hours)) {
        attempts.incrementAndGet()
        throw failure
      }
    // A coroutine in the owner's scope that has nothing to do with the query. The test resumes it
    // from another thread after the barrier is armed.
    val resumeUnrelated = CompletableDeferred<Unit>()
    val unrelatedResumed = CountDownLatch(1)
    val unrelated = owner.launch {
      resumeUnrelated.await()
      unrelatedResumed.countDown()
    }
    val fastSawInitial = CountDownLatch(1)
    val slowSawInitial = CountDownLatch(1)
    val slowSawClear = CountDownLatch(1)
    val releaseSlow = CompletableDeferred<Unit>()
    val afterBothCollectors =
      Executors.newFixedThreadPool(2).asCoroutineDispatcher().use { collectors ->
        val fast =
          launch(collectors) {
            query.dataEager.take(2).collect {
              if (it != null) {
                fastSawInitial.countDown()
              }
            }
          }
        val slow =
          launch(collectors) {
            query.dataEager.take(2).collect {
              if (it != null) {
                slowSawInitial.countDown()
                withTimeout(WAIT_SECONDS * 1000) { releaseSlow.await() }
              } else {
                slowSawClear.countDown()
              }
            }
          }
        try {
          check(
            fastSawInitial.await(WAIT_SECONDS, TimeUnit.SECONDS) &&
              slowSawInitial.await(WAIT_SECONDS, TimeUnit.SECONDS)
          )
          assertThat(attempts.get()).isEqualTo(0)
          // A check on the query's key. The data is fresh, so it only waits for the work queued
          // before it.
          val checked =
            withTimeout(WAIT_SECONDS * 1000) { query.requestFetch(force = false).await() }
          assertThat(checked).isEqualTo(OperationState.Succeeded)
          armedAmong.set(ownerJob.descendants())
          pauseNextSubmission.set(true)
          // Resumed from a thread of its own, so its dispatch comes from off the test thread
          val resumer = thread { resumeUnrelated.complete(Unit) }
          resumer.join(WAIT_SECONDS * 1000)
          assertThat(resumer.isAlive).isFalse()
          check(unrelatedResumed.await(WAIT_SECONDS, TimeUnit.SECONDS))
          store.clear()
          check(firstJobFinished.await(WAIT_SECONDS, TimeUnit.SECONDS))
          // The recheck finished on Default, and its submitter has not returned from dispatch yet
          assertThat(query.status.value).isEqualTo(FetchStatus.Failed(failure))
          assertThat(attempts.get()).isEqualTo(1)
          assertThat(reported.get()).isEqualTo(1)
          releaseSlow.complete(Unit)
          check(slowSawClear.await(WAIT_SECONDS, TimeUnit.SECONDS))
          returnFromFirstSubmission.countDown()
          withTimeout(WAIT_SECONDS * 1000) {
            fast.join()
            slow.join()
            unrelated.join()
            // Queued on the query's key, so it waits for any second recheck the slow collector
            // submitted. The store is empty, so marking it stale changes nothing.
            assertThat(query.invalidate().await()).isEqualTo(OperationState.Succeeded)
          }
          attempts.get()
        } finally {
          pauseNextSubmission.set(false)
          resumeUnrelated.complete(Unit)
          releaseSlow.complete(Unit)
          returnFromFirstSubmission.countDown()
          fast.cancel()
          slow.cancel()
          owner.cancel()
        }
      }
    assertThat(afterBothCollectors).isEqualTo(1)
  }

  /** This job and every job under it now. */
  private fun Job.descendants(): Set<Job> = buildSet {
    add(this@descendants)
    for (child in children) {
      addAll(child.descendants())
    }
  }

  private companion object {
    /** How long each wait in the test may take. */
    const val WAIT_SECONDS = 8L

    /** How long the whole test may take. Every wait inside it is bounded on its own too. */
    const val TEST_TIMEOUT_MILLIS = 60_000L
  }
}
