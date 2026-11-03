// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import dev.zacsweers.fieldspottr.data.core.OutboxBacklog.BackingOff
import dev.zacsweers.fieldspottr.data.core.OutboxBacklog.Empty
import dev.zacsweers.fieldspottr.data.core.OutboxBacklog.Ready
import dev.zacsweers.fieldspottr.data.core.testing.RecordingOutboxScheduler
import dev.zacsweers.fieldspottr.data.core.testing.TestDataScopeFactory
import dev.zacsweers.fieldspottr.data.core.testing.TestSend
import dev.zacsweers.fieldspottr.data.core.testing.awaitSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.testClock
import dev.zacsweers.fieldspottr.data.core.testing.testDataScopeFactory
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The backlog flow is a state, so it can skip a value that another replaces at once. These tests
 * wait on it for a state that lasts, and check the exact sequence on the scheduler, which hears
 * every change.
 */
class OutboxBacklogTest {
  private val send = TestSend<String>()
  private val scheduler = RecordingOutboxScheduler()

  private fun TestScope.factory(online: Boolean = true): TestDataScopeFactory =
    testDataScopeFactory(clock = testClock(START), online = online, scheduler = scheduler)

  @Test
  fun `a row that waits is reported, and the backlog clears once it sends`() = runTest {
    val factory = factory(online = false)
    val data = factory.create(backgroundScope)
    val outbox = data.outbox<String>("test", send)
    runCurrent()
    // An outbox with nothing to send schedules nothing
    assertThat(factory.backlog.first()).isEqualTo(Empty)
    assertThat(scheduler.calls).isEmpty()

    outbox.enqueue("a")
    factory.backlog.first { it == Ready }
    assertThat(scheduler.calls).containsExactly(Ready)

    data.online.value = true
    factory.backlog.first { it == Empty }
    // The scheduler never hears that the backlog is empty, as its KDoc explains
    assertThat(scheduler.calls).containsExactly(Ready)
    assertThat(send.sent).containsExactly("a")
  }

  @Test
  fun `a failed send reports when its backoff ends, and the end reports ready again`() = runTest {
    send.failTransiently()
    val factory = factory()
    val outbox =
      factory
        .create(backgroundScope)
        .outbox<String>("test", send, retry = RetryPolicy(maxAttempts = 3) { 1.minutes })

    val handle = outbox.enqueue("a")
    factory.backlog.first { it == BackingOff(START + 1.minutes) }
    assertThat(scheduler.calls).containsExactly(Ready, BackingOff(START + 1.minutes))

    // The deadline on the row and the timer that wakes the drain both follow virtual time. The
    // retry is held, so the row's Sending state lasts long enough to be read.
    send.hold("a")
    advanceTimeBy(1.minutes)
    runCurrent()
    factory.backlog.first { it == Ready }
    assertThat(scheduler.calls).containsExactly(Ready, BackingOff(START + 1.minutes), Ready)

    send.release("a")
    handle.awaitSucceeded()
    factory.backlog.first { it == Empty }
    assertThat(send.sent).containsExactly("a")
  }

  @Test
  fun `the backlog is ready if any outbox is, else the earliest backoff`() = runTest {
    send.failTransiently()
    val factory = factory()
    val data = factory.create(backgroundScope)
    val slow = data.outbox<String>("slow", send, retry = RetryPolicy(maxAttempts = 3) { 5.minutes })
    val quick =
      data.outbox<String>("quick", send, retry = RetryPolicy(maxAttempts = 3) { 1.minutes })

    slow.enqueue("a")
    factory.backlog.first { it == BackingOff(START + 5.minutes) }
    quick.enqueue("b")
    factory.backlog.first { it == BackingOff(START + 1.minutes) }

    advanceTimeBy(1.minutes)
    runCurrent()
    // b sent on its retry, so a's deadline is the earliest again
    factory.backlog.first { it == BackingOff(START + 5.minutes) }
    assertThat(send.sent).containsExactly("b")

    // A row that can send now outranks a backoff, and sending it goes back to the backoff
    send.succeed()
    quick.enqueue("c").awaitSucceeded()
    factory.backlog.first { it == BackingOff(START + 5.minutes) }
    assertThat(scheduler.calls.takeLast(2)).containsExactly(Ready, BackingOff(START + 5.minutes))
  }

  @Test
  fun `an outbox whose scope ends leaves the backlog, and its rows keep the work`() = runTest {
    val factory = factory(online = false)
    val owner =
      CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext.job))
    factory.create(owner).outbox<String>("test", send).enqueue("a")
    factory.backlog.first { it == Ready }

    owner.cancel()

    // Nothing runs, so nothing counts, and the row stays for the next owner
    factory.backlog.first { it == Empty }
    assertThat(scheduler.calls).containsExactly(Ready)
    assertThat(factory.outboxStore<String>("test").entries().first()).hasSize(1)
  }

  @Test
  fun `a scheduler that throws is reported and holds up neither the drain nor the next report`() =
    runTest {
      val failing = IllegalStateException("WorkManager not initialized")
      val heard = mutableListOf<OutboxBacklog.Waiting>()
      val throwing =
        object : OutboxScheduler {
          override fun rowsWaiting(backlog: OutboxBacklog.Waiting) {
            heard += backlog
            throw failing
          }
        }
      val factory =
        testDataScopeFactory(clock = testClock(START), online = false, scheduler = throwing)
      val data = factory.create(backgroundScope)
      val outbox = data.outbox<String>("test", send)

      val first = outbox.enqueue("a")
      factory.backlog.first { it == Ready }
      data.online.value = true
      first.awaitSucceeded()
      factory.backlog.first { it == Empty }
      // The backlog still moves, and the next change is reported again
      outbox.enqueue("b").awaitSucceeded()

      assertThat(send.sent).containsExactly("a", "b")
      assertThat(heard).containsExactly(Ready, Ready)
      assertThat(data.actionFailures.all).containsExactly(failing, failing)
    }

  @Test
  fun `awaitDrained waits for sends and returns the backoff the rows left wait for`() = runTest {
    val factory = factory()
    assertThat(factory.awaitDrained()).isEqualTo(Empty)

    send.hold("a")
    val outbox =
      factory
        .create(backgroundScope)
        .outbox<String>("test", send, retry = RetryPolicy(maxAttempts = 3) { 5.minutes })
    outbox.enqueue("a")
    val drained = async { factory.awaitDrained() }
    runCurrent()
    assertThat(drained.isCompleted).isFalse()
    send.release("a")
    assertThat(drained.await()).isEqualTo(Empty)

    send.failTransiently()
    outbox.enqueue("b")
    runCurrent()
    assertThat(factory.awaitDrained()).isEqualTo(BackingOff(START + 5.minutes))
  }
}

private val START = Instant.fromEpochSeconds(1_700_000_000)
