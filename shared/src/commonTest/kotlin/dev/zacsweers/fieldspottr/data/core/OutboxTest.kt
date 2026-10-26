// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import dev.zacsweers.fieldspottr.data.core.impl.inMemoryOutboxStore
import dev.zacsweers.fieldspottr.data.core.testing.TestDataScope
import dev.zacsweers.fieldspottr.data.core.testing.TestSend
import dev.zacsweers.fieldspottr.data.core.testing.assertCancelled
import dev.zacsweers.fieldspottr.data.core.testing.assertFailed
import dev.zacsweers.fieldspottr.data.core.testing.assertPending
import dev.zacsweers.fieldspottr.data.core.testing.assertSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.awaitFailed
import dev.zacsweers.fieldspottr.data.core.testing.awaitSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.testClock
import dev.zacsweers.fieldspottr.data.core.testing.testDataScope
import dev.zacsweers.fieldspottr.data.core.testing.testDataScopeFactory
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class OutboxTest {

  private val send = TestSend<String>()
  private lateinit var data: TestDataScope

  private fun TestScope.outbox(
    online: Boolean = true,
    retry: RetryPolicy = RetryPolicy(maxAttempts = 3) { 1.seconds },
  ): Outbox<String> {
    data = testDataScope(clock = testClock(START), online = online)
    return data.outbox<String>("test", send, send::revert, retry)
  }

  @Test
  fun `an enqueued row applies its change before it inserts and sends`() = runTest {
    val outbox = outbox()
    var applied = false

    val handle = outbox.enqueue("a") { applied = true }

    assertThat(handle.await()).isEqualTo(OperationState.Succeeded)
    assertThat(applied).isTrue()
    assertThat(send.sent).containsExactly("a")
    assertThat(outbox.entries.first()).isEmpty()
  }

  @Test
  fun `rows on one key send in order and other keys do not wait`() = runTest {
    send.hold("a")
    val outbox = outbox()

    outbox.enqueue("a", key = "k")
    val b = outbox.enqueue("b", key = "k")
    val c = outbox.enqueue("c", key = "other")

    assertThat(c.await()).isEqualTo(OperationState.Succeeded)
    assertThat(send.sent).containsExactly("c")
    send.release("a")
    assertThat(b.await()).isEqualTo(OperationState.Succeeded)
    assertThat(send.sent).containsExactly("c", "a", "b")
  }

  @Test
  fun `a failed apply inserts nothing and fails the handle`() = runTest {
    val outbox = outbox()

    val handle = outbox.enqueue("a") { error("apply failed") }

    assertThat(handle.await()).isInstanceOf<OperationState.Failed>()
    assertThat(outbox.entries.first()).isEmpty()
    assertThat(send.sent).isEmpty()
  }

  @Test
  fun `a transient failure backs off and then sends`() = runTest {
    send.failTransiently()
    val outbox = outbox()

    val handle = outbox.enqueue("a")
    val waiting = outbox.entries.first { it.singleOrNull()?.retryAt != null }.single()
    assertThat(waiting.attempts).isEqualTo(1)
    assertThat(waiting.retryAt).isEqualTo(START + 1.seconds)

    // The deadline on the row and the timer that wakes the drain both follow virtual time
    advanceTimeBy(1.seconds)
    runCurrent()

    assertThat(handle.await()).isEqualTo(OperationState.Succeeded)
    assertThat(send.sent).containsExactly("a")
  }

  @Test
  fun `a permanent failure fails the row and reverts it once`() = runTest {
    send.failPermanently()
    val outbox = outbox()

    assertThat(outbox.enqueue("a").await()).isInstanceOf<OperationState.Failed>()

    assertThat(send.reverted).containsExactly("a")
    assertThat(outbox.entries.first().single().status).isInstanceOf<OutboxStatus.Failed>()
  }

  @Test
  fun `running out of attempts fails the row`() = runTest {
    send.failure = { _, _ -> IllegalStateException("flaky") }
    val outbox = outbox(retry = RetryPolicy(maxAttempts = 1) { 1.seconds })

    assertThat(outbox.enqueue("a").await()).isInstanceOf<OperationState.Failed>()

    assertThat(send.reverted).containsExactly("a")
  }

  @Test
  fun `retry requeues a failed row without reverting again`() = runTest {
    send.failPermanently()
    val outbox = outbox()
    outbox.enqueue("a").await()
    val id = outbox.entries.first().single().id

    send.succeed()

    assertThat(outbox.retry(id).await()).isEqualTo(OperationState.Succeeded)
    assertThat(send.sent).containsExactly("a")
    assertThat(send.reverted).containsExactly("a")
  }

  @Test
  fun `discard reverts a queued row and cancels its handle`() = runTest {
    val outbox = outbox(online = false)
    val handle = outbox.enqueue("a")
    val id = outbox.entries.first { it.isNotEmpty() }.single().id

    assertThat(outbox.discard(id).await()).isEqualTo(OperationState.Succeeded)

    assertThat(send.reverted).containsExactly("a")
    assertThat(handle.await()).isEqualTo(OperationState.Cancelled)
    assertThat(outbox.entries.first()).isEmpty()
  }

  @Test
  fun `rows wait for the network`() = runTest {
    val outbox = outbox(online = false)

    val handle = outbox.enqueue("a")
    outbox.entries.first { it.isNotEmpty() }
    testScheduler.runCurrent()
    assertThat(send.sent).isEmpty()

    data.online.value = true
    assertThat(handle.await()).isEqualTo(OperationState.Succeeded)
    assertThat(send.sent).containsExactly("a")
  }

  @Test
  fun `operation re-attaches to a row by id`() = runTest {
    send.hold("a")
    val outbox = outbox()
    outbox.enqueue("a")
    val id = outbox.entries.first { it.isNotEmpty() }.single().id

    val reattached = outbox.operation(id)
    send.release("a")

    assertThat(reattached.await()).isEqualTo(OperationState.Succeeded)
    assertThat(outbox.operation(EntryId("unknown")).await()).isEqualTo(OperationState.Cancelled)
  }

  @Test
  fun `a rejected insert undoes the optimistic change`() = runTest {
    val rows = inMemoryOutboxStore<String>()
    // Stores nothing and throws, like a full disk
    val full =
      object : OutboxStore<String> by rows {
        override suspend fun insert(key: String, payload: String): EntryId =
          throw IllegalStateException("Disk full")
      }
    var applied = false
    val outbox = testDataScope().outbox(full, send = send, revert = { applied = false })

    outbox.enqueue("a") { applied = true }.awaitFailed<IllegalStateException>()

    assertThat(applied).isFalse()
    assertThat(rows.entries().first()).isEmpty()
  }

  @Test
  fun `stopping the owner cancels its pending handles and keeps the rows`() = runTest {
    val scopes = testDataScopeFactory(online = false)
    val owner =
      CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext.job))
    val handle = scopes.create(owner).outbox<String>("notes", send = send).enqueue("a")
    scopes.outboxStore<String>("notes").entries().first { it.isNotEmpty() }

    owner.cancel()
    testScheduler.runCurrent()

    handle.assertCancelled()
    // The next owner sends it
    assertThat(scopes.outboxStore<String>("notes").entries().first()).hasSize(1)
  }

  @Test
  fun `a handle whose row cannot be read fails`() = runTest {
    val rows = inMemoryOutboxStore<String>()
    val unreadable =
      object : OutboxStore<String> by rows {
        override fun entry(id: EntryId): Flow<OutboxEntry<String>?> = flow {
          throw IllegalStateException("Corrupt row")
        }
      }
    val outbox = testDataScope().outbox(unreadable, send = send)

    outbox.enqueue("a").awaitFailed<IllegalStateException>()
  }

  @Test
  fun `discarding a head that is backing off sends the next row now`() = runTest {
    send.failure = { payload, _ -> IllegalStateException("Transient").takeIf { payload == "a" } }
    val outbox = outbox(retry = RetryPolicy(maxAttempts = 3) { 5.minutes })
    outbox.enqueue("a", key = "k")
    val b = outbox.enqueue("b", key = "k")
    val a = outbox.entries.first { rows -> rows.any { it.retryAt != null } }.first()

    val discarded = outbox.discard(a.id)
    // No time passes, so only the discard can wake the key
    testScheduler.runCurrent()

    discarded.assertSucceeded()
    b.assertSucceeded()
    assertThat(send.sent).containsExactly("b")
  }

  @Test
  fun `discarding a head while its backoff is written sends the next row now`() = runTest {
    send.failure = { payload, _ -> IllegalStateException("Transient").takeIf { payload == "a" } }
    val rows = inMemoryOutboxStore<String>()
    val published = CompletableDeferred<Unit>()
    val finishWrite = CompletableDeferred<Unit>()
    // Shows the backoff before the write returns, like a database that notifies inside its
    // transaction
    val slow =
      object : OutboxStore<String> by rows {
        override suspend fun update(entry: OutboxEntry<String>, expected: OutboxStatus): Boolean {
          val wrote = rows.update(entry, expected)
          if (entry.retryAt != null && !published.isCompleted) {
            published.complete(Unit)
            finishWrite.await()
          }
          return wrote
        }
      }
    val outbox =
      testDataScope().outbox(slow, send = send, retry = RetryPolicy(maxAttempts = 3) { 5.minutes })
    val a = outbox.enqueue("a", key = "k")
    val b = outbox.enqueue("b", key = "k")
    published.await()

    val discarded = outbox.discard(rows.entries().first().first { it.payload == "a" }.id)
    testScheduler.runCurrent()
    finishWrite.complete(Unit)
    // No time passes, so the backoff written for the discarded row must not hold the key
    testScheduler.runCurrent()

    discarded.assertSucceeded()
    a.assertCancelled()
    b.assertSucceeded()
    assertThat(send.sent).containsExactly("b")
  }

  @Test
  fun `a discard whose owner stops during its write still undoes the change`() = runTest {
    val rows = inMemoryOutboxStore<String>()
    val pausing = PauseAfterRevertedWrite(rows)
    val scopes = testDataScopeFactory(online = false)
    val owner =
      CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext.job))
    var undone = 0
    val outbox = scopes.create(owner).outbox(pausing, send = send, revert = { undone++ })
    outbox.enqueue("a")
    val id = rows.entries().first { it.isNotEmpty() }.single().id

    outbox.discard(id)
    pausing.written.await()
    owner.cancel()
    pausing.finish.complete(Unit)
    testScheduler.runCurrent()

    // The row says reverted, so the undo ran
    assertThat(undone).isEqualTo(1)
    assertThat(rows.entry(id).first()?.reverted).isEqualTo(true)
  }

  @Test
  fun `a rejection whose owner stops during its write still undoes the change`() = runTest {
    send.failPermanently()
    val rows = inMemoryOutboxStore<String>()
    val pausing = PauseAfterRevertedWrite(rows)
    val scopes = testDataScopeFactory()
    val owner =
      CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext.job))
    var undone = 0
    scopes.create(owner).outbox(pausing, send = send, revert = { undone++ }).enqueue("a")

    pausing.written.await()
    owner.cancel()
    pausing.finish.complete(Unit)
    testScheduler.runCurrent()
    assertThat(undone).isEqualTo(1)

    // A fresh owner sees the row as failed and reverted, and a discard does not undo it twice
    val fresh = scopes.create(backgroundScope).outbox(rows, send = send, revert = { undone++ })
    val id = rows.entries().first().single().id
    fresh.discard(id).awaitSucceeded()
    assertThat(undone).isEqualTo(1)
  }

  @Test
  fun `a store that fails to report its rows once still sends them`() = runTest {
    val rows = inMemoryOutboxStore<String>()
    rows.insert("k", "a")
    var failNextRead = true
    val flaky =
      object : OutboxStore<String> by rows {
        override fun entries(): Flow<List<OutboxEntry<String>>> = flow {
          if (failNextRead) {
            failNextRead = false
            throw IllegalStateException("Disk busy")
          }
          emitAll(rows.entries())
        }
      }
    val data = testDataScope()

    data.outbox(flaky, send = send)
    testScheduler.runCurrent()
    data.actionFailures.assertSingle<IllegalStateException>()
    testScheduler.advanceTimeBy(1.minutes)
    testScheduler.runCurrent()

    assertThat(send.sent).containsExactly("a")
  }

  @Test
  fun `a handle keeps its own attempt's result when a retry succeeds first`() = runTest {
    send.failPermanently()
    val rows = inMemoryOutboxStore<String>()
    val firstView = CompletableDeferred<Unit>()
    var holdNextView = true
    // Holds the first view of a row, like an observer that falls behind
    val slowView =
      object : OutboxStore<String> by rows {
        override fun entry(id: EntryId): Flow<OutboxEntry<String>?> = flow {
          if (holdNextView) {
            holdNextView = false
            firstView.await()
          }
          emitAll(rows.entry(id))
        }
      }
    val outbox = testDataScope().outbox(slowView, send = send, revert = send::revert)
    val original = outbox.enqueue("a")
    testScheduler.runCurrent()

    send.succeed()
    val retried = outbox.retry(rows.entries().first().single().id)
    testScheduler.runCurrent()
    firstView.complete(Unit)
    testScheduler.runCurrent()

    retried.assertSucceeded()
    original.assertFailed<PermanentFailure>()
  }

  @Test
  fun `a row whose drain went offline mid read stays queued`() = runTest {
    send.failure = { _, _ -> IllegalStateException("No network") }
    val rows = inMemoryOutboxStore<String>()
    val paused = CompletableDeferred<Unit>()
    val resume = CompletableDeferred<Unit>()
    var pauseNextRead = false
    val slowRead =
      object : OutboxStore<String> by rows {
        override fun entries(): Flow<List<OutboxEntry<String>>> = flow {
          if (pauseNextRead) {
            pauseNextRead = false
            paused.complete(Unit)
            resume.await()
          }
          emitAll(rows.entries())
        }
      }
    val data = testDataScope()
    val outbox =
      data.outbox(
        slowRead,
        send = send,
        revert = send::revert,
        retry = RetryPolicy(maxAttempts = 1) { 1.seconds },
      )
    testScheduler.runCurrent()

    // The drain for the new row pauses in its read of the head
    pauseNextRead = true
    val handle = outbox.enqueue("a")
    paused.await()
    data.online.value = false
    testScheduler.runCurrent()
    resume.complete(Unit)
    testScheduler.runCurrent()

    assertThat(send.attempts).isEmpty()
    assertThat(send.reverted).isEmpty()
    handle.assertPending()
    send.succeed()
    data.online.value = true
    testScheduler.runCurrent()
    handle.assertSucceeded()
  }

  /**
   * Pauses the first update that marks a row reverted, after it commits and before it returns, like
   * a database that notifies inside its transaction.
   */
  private class PauseAfterRevertedWrite(private val rows: OutboxStore<String>) :
    OutboxStore<String> by rows {
    val written = CompletableDeferred<Unit>()
    val finish = CompletableDeferred<Unit>()

    override suspend fun update(entry: OutboxEntry<String>, expected: OutboxStatus): Boolean {
      val wrote = rows.update(entry, expected)
      if (entry.reverted && !written.isCompleted) {
        written.complete(Unit)
        finish.await()
      }
      return wrote
    }
  }

  @Test
  fun `discarding a row behind a backing-off head keeps the head waiting`() = runTest {
    send.failure = { payload, _ -> IllegalStateException("Transient").takeIf { payload == "a" } }
    val outbox = outbox(retry = RetryPolicy(maxAttempts = 3) { 5.minutes })
    val a = outbox.enqueue("a", key = "k")
    val b = outbox.enqueue("b", key = "k")
    val c = outbox.enqueue("c", key = "k")
    val rows = outbox.entries.first { rows -> rows.any { it.retryAt != null } }

    outbox.discard(rows.single { it.payload == "b" }.id)
    testScheduler.runCurrent()

    b.assertCancelled()
    a.assertPending()
    c.assertPending()
    assertThat(send.attempts).containsExactly("a")
  }

  private companion object {
    val START = Instant.parse("2026-06-10T12:00:00Z")
  }
}
