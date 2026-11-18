// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
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
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.withIndex
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull

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
  fun `a row that waits is reported and the backlog clears once it sends`() = runTest {
    val factory = factory(online = false)
    val data = factory.create(backgroundScope, setOf(Changes.sender(send = send)))
    val outbox = data.outbox(Changes)
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
  fun `consecutive ready reports reach the scheduler once and a row after empty again`() = runTest {
    val factory = factory(online = false)
    val data = factory.create(backgroundScope, setOf(Changes.sender(send = send)))
    val outbox = data.outbox(Changes)
    runCurrent()

    // Each enqueue reports the backlog, and it stays Ready, so the scheduler hears it once
    outbox.enqueue("a", key = "a")
    outbox.enqueue("b", key = "b")
    runCurrent()
    assertThat(scheduler.calls).containsExactly(Ready)

    data.online.value = true
    factory.backlog.first { it == Empty }
    data.online.value = false
    runCurrent()
    // After the backlog emptied, a drain may already be finishing, so a new row is told again
    outbox.enqueue("c")
    factory.backlog.first { it == Ready }
    assertThat(scheduler.calls).containsExactly(Ready, Ready)
  }

  @Test
  fun `a failed send reports when its backoff ends and then reports ready again`() = runTest {
    send.failTransiently()
    val factory = factory()
    val outbox =
      factory
        .create(
          backgroundScope,
          setOf(Changes.sender(retry = RetryPolicy(maxAttempts = 3) { 1.minutes }, send = send)),
        )
        .outbox(Changes)

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
  fun `the backlog is ready if any outbox is and otherwise the earliest backoff`() = runTest {
    send.failTransiently()
    val factory = factory()
    val data =
      factory.create(
        backgroundScope,
        setOf(
          Slow.sender(retry = RetryPolicy(maxAttempts = 3) { 5.minutes }, send = send),
          Quick.sender(retry = RetryPolicy(maxAttempts = 3) { 1.minutes }, send = send),
        ),
      )
    val slow = data.outbox(Slow)
    val quick = data.outbox(Quick)

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
  fun `an outbox whose scope ends leaves the backlog while its rows keep the work`() = runTest {
    val factory = factory(online = false)
    val owner =
      CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext.job))
    factory.create(owner, setOf(Changes.sender(send = send))).outbox(Changes).enqueue("a")
    factory.backlog.first { it == Ready }

    owner.cancel()

    // Nothing runs, so nothing counts, and the row stays for the next owner
    factory.backlog.first { it == Empty }
    assertThat(scheduler.calls).containsExactly(Ready)
    assertThat(factory.outboxStore<String>(Changes.name).entries().first()).hasSize(1)
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
      val data = factory.create(backgroundScope, setOf(Changes.sender(send = send)))
      val outbox = data.outbox(Changes)

      val first = outbox.enqueue("a")
      factory.backlog.first { it == Ready }
      data.online.value = true
      first.awaitSucceeded()
      factory.backlog.first { it == Empty }
      // The backlog still moves, and the next change is reported again
      outbox.enqueue("b").awaitSucceeded()

      assertThat(send.sent).containsExactly("a", "b")
      // Every call threw, so each report while rows waited made the call again
      assertThat(heard.distinct()).containsExactly(Ready)
      assertThat(data.actionFailures.all).isEqualTo(heard.map { failing })
    }

  @Test
  fun `awaitDrained waits for sends and returns the backoff the rows left wait for`() = runTest {
    val factory = factory()
    assertThat(factory.awaitDrained()).isEqualTo(Empty)

    send.hold("a")
    val outbox =
      factory
        .create(
          backgroundScope,
          setOf(Changes.sender(retry = RetryPolicy(maxAttempts = 3) { 5.minutes }, send = send)),
        )
        .outbox(Changes)
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

  @Test
  fun `an outbox that fails to build leaves the backlog reporting`() = runTest {
    val factory = factory()
    val rows = factory.outboxStore<String>(Changes.name)
    var failToOpen = true
    val flaky =
      object : OutboxStore<String> by rows {
        override fun entries(): Flow<List<OutboxEntry<String>>> {
          if (failToOpen) {
            failToOpen = false
            throw IllegalStateException("Rows did not open")
          }
          return rows.entries()
        }
      }

    val senders = setOf(Flaky.sender(store = flaky, send = send))

    assertFailsWith<IllegalStateException> { factory.create(backgroundScope, senders) }
    factory.create(backgroundScope, senders).outbox(Flaky)
    runCurrent()

    // Only the outbox that was built counts, so the backlog still reports
    assertThat(withTimeoutOrNull(1.minutes) { factory.backlog.first() }).isEqualTo(Empty)
  }

  @Test
  fun `a backoff whose deadline passed on the wall clock waits for the send`() = runTest {
    val factory = factory()
    val outbox =
      factory
        .create(
          backgroundScope,
          setOf(Changes.sender(retry = RetryPolicy(maxAttempts = 3) { 5.minutes }, send = send)),
        )
        .outbox(Changes)
    send.failTransiently()
    val handle = outbox.enqueue("a")
    runCurrent()
    assertThat(factory.backlog.first()).isEqualTo(BackingOff(START + 5.minutes))

    // Wall time passes the deadline while the timer's own delay runs, like in deep sleep
    factory.clock.shift(6.minutes)
    val drained = async { factory.awaitDrained() }
    runCurrent()
    assertThat(drained.isCompleted).isFalse()
    advanceTimeBy(1.minutes)
    runCurrent()

    // The timer reads the wall clock within a minute of waking, so the row sends then
    assertThat(drained.isCompleted).isTrue()
    assertThat(drained.await()).isEqualTo(Empty)
    handle.awaitSucceeded()
  }

  @Test
  fun `a waiter after a row is stored sees work owed while the watcher lags`() = runTest {
    val rows = inMemoryOutboxStore<String>()
    val catchUp = CompletableDeferred<Unit>()
    // The heads after the first list wait, like a database whose notifications fall behind
    val lagging =
      object : OutboxStore<String> by rows {
        override fun heads(): Flow<List<OutboxEntry<String>>> =
          rows.heads().withIndex().onEach { if (it.index > 0) catchUp.await() }.map { it.value }
      }
    val factory = factory()
    val outbox =
      factory
        .create(backgroundScope, setOf(Flaky.sender(store = lagging, send = send)))
        .outbox(Flaky)
    runCurrent()
    assertThat(factory.backlog.first()).isEqualTo(Empty)
    send.hold("a")

    val handle = outbox.enqueue("a")
    handle.stored.awaitSucceeded()
    val drained = async { factory.awaitDrained() }
    runCurrent()
    assertThat(drained.isCompleted).isFalse()

    // Once the row sends and the watcher catches up, the wait ends
    send.release("a")
    handle.awaitSucceeded()
    catchUp.complete(Unit)
    assertThat(drained.await()).isEqualTo(Empty)
  }

  @Test
  fun `a scheduler call that threw is made again on the next equal report`() = runTest {
    val failing = IllegalStateException("WorkManager not initialized")
    var throwNext = true
    val heard = mutableListOf<OutboxBacklog.Waiting>()
    val throwsOnce =
      object : OutboxScheduler {
        override fun rowsWaiting(backlog: OutboxBacklog.Waiting) {
          heard += backlog
          if (throwNext) {
            throwNext = false
            throw failing
          }
        }
      }
    val factory =
      testDataScopeFactory(clock = testClock(START), online = false, scheduler = throwsOnce)
    val data = factory.create(backgroundScope, setOf(Changes.sender(send = send)))
    val outbox = data.outbox(Changes)

    outbox.enqueue("a", key = "a").stored.awaitSucceeded()
    runCurrent()
    outbox.enqueue("b", key = "b").stored.awaitSucceeded()
    runCurrent()

    // The first call threw, so the backlog that stayed Ready was told again, and then only once
    assertThat(heard).containsExactly(Ready, Ready)
    assertThat(data.actionFailures.all).containsExactly(failing)
  }

  @Test
  fun `a waiting collector sees a backoff turn ready at its deadline while offline`() = runTest {
    send.failTransiently()
    val factory = factory()
    val data =
      factory.create(
        backgroundScope,
        setOf(Changes.sender(retry = RetryPolicy(maxAttempts = 3) { 5.minutes }, send = send)),
      )
    data.outbox(Changes).enqueue("a")
    factory.backlog.first { it == BackingOff(START + 5.minutes) }
    data.online.value = false
    runCurrent()

    val ready = async { factory.backlog.first { it == Ready } }
    advanceTimeBy(4.minutes)
    runCurrent()
    assertThat(ready.isCompleted).isFalse()
    // No row changes and the network stays down, so only the clock moves the backlog
    advanceTimeBy(1.minutes)
    runCurrent()

    assertThat(ready.isCompleted).isTrue()
    assertThat(send.attempts("a")).isEqualTo(1)
  }
}

private val START = Instant.fromEpochSeconds(1_700_000_000)
private val Changes = OutboxKey<String>("test")
private val Slow = OutboxKey<String>("slow")
private val Quick = OutboxKey<String>("quick")
private val Flaky = OutboxKey<String>("flaky")
