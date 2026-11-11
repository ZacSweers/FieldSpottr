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
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import dev.zacsweers.fieldspottr.data.core.impl.rowsFollowed
import dev.zacsweers.fieldspottr.data.core.impl.timersHeld
import dev.zacsweers.fieldspottr.data.core.testing.TestDataScope
import dev.zacsweers.fieldspottr.data.core.testing.TestSend
import dev.zacsweers.fieldspottr.data.core.testing.assertCancelled
import dev.zacsweers.fieldspottr.data.core.testing.assertFailed
import dev.zacsweers.fieldspottr.data.core.testing.assertPending
import dev.zacsweers.fieldspottr.data.core.testing.assertSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.awaitCancelled
import dev.zacsweers.fieldspottr.data.core.testing.awaitFailed
import dev.zacsweers.fieldspottr.data.core.testing.awaitSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.testClock
import dev.zacsweers.fieldspottr.data.core.testing.testDataScope
import dev.zacsweers.fieldspottr.data.core.testing.testDataScopeFactory
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
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
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer

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
    val outbox = testDataScope().outbox("full", full, send = send, revert = { applied = false })

    val handle = outbox.enqueue("a") { applied = true }
    handle.awaitFailed<IllegalStateException>()

    // Nothing was stored, so the stored milestone failed too
    handle.stored.assertFailed<IllegalStateException>()
    assertThat(applied).isFalse()
    assertThat(rows.entries().first()).isEmpty()
  }

  @Test
  fun `the stored milestone settles once the row is in and before the send`() = runTest {
    val outbox = outbox(online = false)

    val handle = outbox.enqueue("a")
    handle.stored.awaitSucceeded()

    // The row waits for the network, and the handle still follows it
    handle.assertPending()
    assertThat(send.attempts).isEmpty()
    data.online.value = true
    handle.awaitSucceeded()
  }

  @Test
  fun `an enqueue whose owner stopped before the write cancels the stored milestone`() = runTest {
    val scopes = testDataScopeFactory(online = false)
    val owner =
      CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext.job))
    val outbox = scopes.create(owner).outbox<String>("notes", send = send)
    owner.cancel()

    val handle = outbox.enqueue("a")

    handle.stored.awaitCancelled()
    handle.awaitCancelled()
    assertThat(scopes.outboxStore<String>("notes").entries().first()).isEmpty()
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
    // The row is in, so the stored milestone stands, and the next owner sends it
    handle.stored.assertSucceeded()
    assertThat(scopes.outboxStore<String>("notes").entries().first()).hasSize(1)
  }

  @Test
  fun `a handle whose row cannot be read is reported and still settles by its send`() = runTest {
    val rows = inMemoryOutboxStore<String>()
    val unreadable =
      object : OutboxStore<String> by rows {
        override fun entry(id: EntryId): Flow<OutboxEntry<String>?> = flow {
          throw IllegalStateException("Corrupt row")
        }
      }
    val data = testDataScope()
    val outbox = data.outbox("unreadable", unreadable, send = send)

    // A failed read says nothing about the row, so it never settles the handle
    outbox.enqueue("a").awaitSucceeded()

    assertThat(send.sent).containsExactly("a")
    assertThat(data.actionFailures.take().map { it.message }.distinct())
      .containsExactly("Corrupt row")
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
      testDataScope()
        .outbox("slow", slow, send = send, retry = RetryPolicy(maxAttempts = 3) { 5.minutes })
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
    val outbox = scopes.create(owner).outbox("pausing", pausing, send = send, revert = { undone++ })
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
    scopes.create(owner).outbox("pausing", pausing, send = send, revert = { undone++ }).enqueue("a")

    pausing.written.await()
    owner.cancel()
    pausing.finish.complete(Unit)
    testScheduler.runCurrent()
    assertThat(undone).isEqualTo(1)

    // A fresh owner sees the row as failed and reverted, and a discard does not undo it twice
    val fresh =
      scopes.create(backgroundScope).outbox("rows", rows, send = send, revert = { undone++ })
    val id = rows.entries().first().single().id
    fresh.discard(id).awaitSucceeded()
    assertThat(undone).isEqualTo(1)
  }

  @Test
  fun `a store that fails to report its rows once still sends them`() = runTest {
    val rows = inMemoryOutboxStore<String>()
    rows.insert("k", "a")
    var failNextRead = true
    // The watcher reads the heads
    val flaky =
      object : OutboxStore<String> by rows {
        override fun heads(): Flow<List<OutboxEntry<String>>> = flow {
          if (failNextRead) {
            failNextRead = false
            throw IllegalStateException("Disk busy")
          }
          emitAll(rows.heads())
        }
      }
    val data = testDataScope()

    data.outbox("flaky", flaky, send = send)
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
    val outbox = testDataScope().outbox("slowView", slowView, send = send, revert = send::revert)
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
  fun `a retry started while the failed status is written reports its own result`() = runTest {
    send.failPermanently()
    val rows = inMemoryOutboxStore<String>()
    val failedShown = CompletableDeferred<Unit>()
    val finishFailed = CompletableDeferred<Unit>()
    // Publishes the failed status, then pauses before the update returns
    val slowFailure =
      object : OutboxStore<String> by rows {
        override suspend fun update(entry: OutboxEntry<String>, expected: OutboxStatus): Boolean {
          val updated = rows.update(entry, expected)
          if (entry.status is OutboxStatus.Failed && failedShown.complete(Unit)) {
            finishFailed.await()
          }
          return updated
        }
      }
    val outbox =
      testDataScope().outbox("slowFailure", slowFailure, send = send, revert = send::revert)
    val original = outbox.enqueue("a")
    failedShown.await()

    send.succeed()
    val retried = outbox.retry(rows.entries().first().single().id)
    testScheduler.runCurrent()
    finishFailed.complete(Unit)
    testScheduler.runCurrent()

    original.assertFailed<PermanentFailure>()
    retried.assertSucceeded()
    assertThat(send.sent).containsExactly("a")
  }

  @Test
  fun `a retry whose requeue throws fails with that exception`() = runTest {
    send.failTransiently()
    val rows = inMemoryOutboxStore<String>()
    val diskFull = IllegalStateException("Disk full")
    var failUpdates = false
    val failingRequeue =
      object : OutboxStore<String> by rows {
        override suspend fun update(entry: OutboxEntry<String>, expected: OutboxStatus): Boolean {
          if (failUpdates) {
            throw diskFull
          }
          return rows.update(entry, expected)
        }
      }
    data = testDataScope(clock = testClock(START))
    val outbox = data.outbox("failingRequeue", failingRequeue, send = send, revert = send::revert)
    outbox.enqueue("a")
    testScheduler.runCurrent()
    // Backing off after the failed attempt, and offline, so nothing else touches the row
    data.online.value = false
    assertThat(rows.entries().first().single().retryAt).isNotNull()

    failUpdates = true
    val retried = outbox.retry(rows.entries().first().single().id)
    testScheduler.runCurrent()

    assertThat(retried.awaitFailed<IllegalStateException>()).isSameInstanceAs(diskFull)
    assertThat(data.actionFailures.take()).containsExactly(diskFull)
  }

  @Test
  fun `a send that cannot be retried fails once and the next row sends`() = runTest {
    val rejected = IllegalArgumentException("400 Bad Request")
    send.failure = { payload, _ -> rejected.takeIf { payload == "a" } }
    data =
      testDataScope(
        clock = testClock(START),
        retry = RetryPolicy.exponential(retryable = { it !is IllegalArgumentException }),
      )
    val outbox = data.outbox<String>("test", send, send::revert)

    val first = outbox.enqueue("a", key = "k")
    val second = outbox.enqueue("b", key = "k")

    val failure = first.awaitFailed<PermanentFailure>()
    assertThat(failure.message).isEqualTo(rejected.toString())
    assertThat(failure.cause).isSameInstanceAs(rejected)
    second.awaitSucceeded()
    assertThat(data.actionFailures.take()).containsExactly(rejected)
    assertThat(send.attempts("a")).isEqualTo(1)
    assertThat(send.reverted).containsExactly("a")
    assertThat(send.sent).containsExactly("b")
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
        override suspend fun head(key: String): OutboxEntry<String>? {
          if (pauseNextRead) {
            pauseNextRead = false
            paused.complete(Unit)
            resume.await()
          }
          return rows.head(key)
        }
      }
    val data = testDataScope()
    val outbox =
      data.outbox(
        "slowRead",
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

  @Test
  fun `a drain whose read of the head fails tries again on its own`() = runTest {
    val rows = inMemoryOutboxStore<String>()
    val id = rows.insert("k", "a")
    var failNextRead = true
    // The drain reads its key's head, and the watcher reads every head without this
    val flaky =
      object : OutboxStore<String> by rows {
        override suspend fun head(key: String): OutboxEntry<String>? {
          if (failNextRead) {
            failNextRead = false
            throw IllegalStateException("Disk busy")
          }
          return rows.head(key)
        }
      }

    assertDrainRecovers(flaky, id)
  }

  @Test
  fun `a drain whose claim of the head fails tries again on its own`() = runTest {
    val rows = inMemoryOutboxStore<String>()
    val id = rows.insert("k", "a")
    var failClaim = true
    val flaky =
      object : OutboxStore<String> by rows {
        override suspend fun update(entry: OutboxEntry<String>, expected: OutboxStatus): Boolean {
          if (failClaim && entry.status == OutboxStatus.Sending) {
            failClaim = false
            throw IllegalStateException("Disk busy")
          }
          return rows.update(entry, expected)
        }
      }

    assertDrainRecovers(flaky, id)
  }

  /** The drain fails once with the watcher healthy, so no new row or reconnect starts another. */
  private fun TestScope.assertDrainRecovers(rows: OutboxStore<String>, id: EntryId) {
    val data = testDataScope()
    val handle = data.outbox("rows", rows, send = send).operation(id)
    testScheduler.runCurrent()
    data.actionFailures.assertSingle<IllegalStateException>()
    handle.assertPending()

    testScheduler.advanceTimeBy(1.minutes)
    testScheduler.runCurrent()
    handle.assertSucceeded()
    assertThat(send.sent).containsExactly("a")
  }

  @Test
  fun `an enqueue and a reconnect send while the watcher pauses after a store failure`() = runTest {
    val rows = inMemoryOutboxStore<String>()
    rows.insert("stored", "a")
    var failNextRead = true
    // The watcher reads the heads
    val flaky =
      object : OutboxStore<String> by rows {
        override fun heads(): Flow<List<OutboxEntry<String>>> = flow {
          if (failNextRead) {
            failNextRead = false
            throw IllegalStateException("Disk busy")
          }
          emitAll(rows.heads())
        }
      }
    val data = testDataScope()
    val outbox = data.outbox("flaky", flaky, send = send)
    testScheduler.runCurrent()
    data.actionFailures.assertSingle<IllegalStateException>()

    // No virtual time passes, so the watcher's pause has not ended
    outbox.enqueue("b", key = "new")
    testScheduler.runCurrent()
    assertThat(send.sent).containsExactly("b")
    data.online.value = false
    testScheduler.runCurrent()
    data.online.value = true
    testScheduler.runCurrent()

    assertThat(send.sent).containsExactly("b", "a")
  }

  @Test
  fun `a reconnect sends a row whose saved deadline passed while offline`() = runTest {
    send.failTransiently()
    val outbox = outbox(retry = RetryPolicy(maxAttempts = 3) { 5.minutes })
    val handle = outbox.enqueue("a")
    testScheduler.runCurrent()
    data.online.value = false
    testScheduler.runCurrent()

    // Wall time passes the deadline while the timer still waits, like while the device sleeps
    data.clock.shift(10.minutes)
    data.online.value = true
    testScheduler.runCurrent()

    handle.assertSucceeded()
    assertThat(send.attempts("a")).isEqualTo(2)
  }

  @Test
  fun `a clock moved back keeps a backing-off row waiting for its deadline`() = runTest {
    send.failTransiently()
    val outbox = outbox(retry = RetryPolicy(maxAttempts = 3) { 5.minutes })
    val handle = outbox.enqueue("a")
    testScheduler.runCurrent()

    data.clock.shift(-4.minutes)
    testScheduler.advanceTimeBy(5.minutes)
    testScheduler.runCurrent()
    assertThat(send.attempts("a")).isEqualTo(1)

    testScheduler.advanceTimeBy(4.minutes)
    testScheduler.runCurrent()
    handle.assertSucceeded()
  }

  @Test
  fun `an outbox that fails to build leaves its name to the same owner`() = runTest {
    val rows = InMemoryOutboxStores()
    var failToOpen = true
    val stores =
      object : OutboxStores {
        override fun <E> open(name: String, serializer: KSerializer<E>): OutboxStore<E> {
          val opened = rows.open(name, serializer)
          return object : OutboxStore<E> by opened {
            override fun entries(): Flow<List<OutboxEntry<E>>> {
              if (failToOpen) {
                failToOpen = false
                throw IllegalStateException("Rows did not open")
              }
              return opened.entries()
            }
          }
        }

        override suspend fun clearAll() = rows.clearAll()
      }
    val data = DataScope.Factory(onFailure = {}, stores = stores).create(backgroundScope)

    assertFailsWith<IllegalStateException> { data.outbox<String>("notes", send) }
    data.outbox<String>("notes", send).enqueue("a").awaitSucceeded()

    assertThat(send.sent).containsExactly("a")
    // Once built, it still keeps the name from a second outbox
    assertFailsWith<IllegalStateException> { data.outbox<String>("notes", send) }
  }

  @Test
  fun `a permanent failure is reported once and the handle carries it`() = runTest {
    val rejected = PermanentFailure("409 Conflict")
    send.failure = { _, _ -> rejected }
    val rows = inMemoryOutboxStore<String>()
    val scopes = testDataScopeFactory()
    val owner =
      CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext.job))
    val outbox = scopes.create(owner).outbox("rows", rows, send = send)

    val handle = outbox.enqueue("a")

    assertThat(handle.awaitFailed<PermanentFailure>()).isSameInstanceAs(rejected)
    assertThat(scopes.actionFailures.take()).containsExactly(rejected)
    // Re-attached in the same process, it still carries the exception
    val id = rows.entries().first().single().id
    assertThat(outbox.operation(id).awaitFailed<PermanentFailure>()).isSameInstanceAs(rejected)
    // A later owner, like after a restart, knows only the stored text
    owner.cancel()
    val later = scopes.create(backgroundScope).outbox("rows", rows, send = send)
    val reattached = later.operation(id).awaitFailed<PermanentFailure>()
    assertThat(reattached.message).isEqualTo(rejected.toString())
    scopes.assertNoFailures()
  }

  @Test
  fun `running out of attempts reports the last failure and the handle carries it`() = runTest {
    val flaky = IllegalStateException("503")
    send.failure = { _, _ -> flaky }
    val outbox = outbox(retry = RetryPolicy(maxAttempts = 1) { 1.seconds })

    val failure = outbox.enqueue("a").awaitFailed<PermanentFailure>()

    assertThat(failure.cause).isSameInstanceAs(flaky)
    assertThat(data.actionFailures.take()).containsExactly(flaky)
    assertThat(send.reverted).containsExactly("a")
  }

  @Test
  fun `the default policy keeps retrying with no attempt limit`() = runTest {
    val policy = RetryPolicy.exponential()
    val failure = IllegalStateException("503")

    assertThat(policy.delayAfter(1, failure)).isEqualTo(1.seconds)
    assertThat(policy.delayAfter(4, failure)).isEqualTo(8.seconds)
    assertThat(policy.delayAfter(1_000, failure)).isEqualTo(5.minutes)
    assertThat(policy.maxDelay).isEqualTo(5.minutes)
    assertThat(RetryPolicy.exponential(maxAttempts = 3).delayAfter(3, failure)).isEqualTo(null)
  }

  @Test
  fun `a policy can wait as long as the failure asks`() = runTest {
    send.failure = { _, attempt -> RateLimited(2.minutes).takeIf { attempt == 1 } }
    val honoring =
      object : RetryPolicy {
        override fun delayAfter(attempt: Int, failure: Throwable) =
          (failure as? RateLimited)?.retryAfter ?: 1.seconds
      }
    val outbox = outbox(retry = honoring)

    val handle = outbox.enqueue("a")
    val waiting = outbox.entries.first { it.singleOrNull()?.retryAt != null }.single()

    assertThat(waiting.retryAt).isEqualTo(START + 2.minutes)
    advanceTimeBy(2.minutes)
    runCurrent()
    handle.assertSucceeded()
  }

  @Test
  fun `a policy that throws is reported and the row retries`() = runTest {
    val broken = IllegalStateException("Policy bug")
    send.failTransiently()
    val outbox =
      outbox(
        retry =
          object : RetryPolicy {
            override fun delayAfter(attempt: Int, failure: Throwable): Nothing = throw broken
          }
      )

    val handle = outbox.enqueue("a")
    runCurrent()
    assertThat(data.actionFailures.take()).containsExactly(broken)
    handle.assertPending()

    advanceTimeBy(1.minutes)
    runCurrent()
    handle.assertSucceeded()
    assertThat(send.reverted).isEmpty()
  }

  @Test
  fun `a deadline far ahead waits no longer than the policy's bound`() = runTest {
    val rows = inMemoryOutboxStore<String>()
    val id = rows.insert("k", "a")
    val row = checkNotNull(rows.entry(id).first())
    // Written while the device clock was an hour ahead
    rows.update(row.copy(attempts = 1, retryAt = START + 1.hours), OutboxStatus.Queued)
    val factory = testDataScopeFactory(clock = testClock(START))
    val outbox =
      factory
        .create(backgroundScope)
        .outbox("rows", rows, send = send, retry = RetryPolicy.exponential(max = 5.minutes))
    val handle = outbox.operation(id)
    runCurrent()

    assertThat(factory.backlog.first()).isEqualTo(OutboxBacklog.BackingOff(START + 5.minutes))
    advanceTimeBy(5.minutes - 1.seconds)
    runCurrent()
    handle.assertPending()
    advanceTimeBy(1.seconds)
    runCurrent()
    handle.assertSucceeded()
  }

  @Test
  fun `a clock set back waits no longer than the policy's bound`() = runTest {
    send.failTransiently()
    val outbox = outbox(retry = RetryPolicy.exponential(initial = 5.minutes, max = 5.minutes))
    val handle = outbox.enqueue("a")
    runCurrent()

    data.clock.shift(-4.minutes)
    advanceTimeBy(5.minutes)
    runCurrent()

    handle.assertSucceeded()
    assertThat(send.attempts("a")).isEqualTo(2)
  }

  @Test
  fun `a row another writer inserts is sent`() = runTest {
    val rows = inMemoryOutboxStore<String>()
    testDataScope().outbox("rows", rows, send = send)
    runCurrent()

    rows.insert("k", "a")
    runCurrent()

    assertThat(send.sent).containsExactly("a")
    assertThat(rows.entries().first()).isEmpty()
  }

  @Test
  fun `handles whose rows another writer discards settle cancelled`() = runTest {
    val rows = inMemoryOutboxStore<String>()
    val outbox = testDataScope(online = false).outbox("rows", rows, send = send)
    val a = outbox.enqueue("a", key = "k")
    val b = outbox.enqueue("b", key = "k")
    val c = outbox.enqueue("c", key = "other")
    runCurrent()

    // Like an optimistic store's clear, which writes the rows itself
    for (row in rows.entries().first().filter { it.key == "k" }) {
      rows.update(row.copy(status = OutboxStatus.Discarded), OutboxStatus.Queued)
    }
    runCurrent()

    a.assertCancelled()
    b.assertCancelled()
    c.assertPending()
  }

  @Test
  fun `handles of rows a logout clears settle cancelled`() = runTest {
    val scopes = testDataScopeFactory(online = false)
    val outbox = scopes.create(backgroundScope).outbox<String>("notes", send = send)
    val a = outbox.enqueue("a", key = "k")
    val b = outbox.enqueue("b", key = "k")
    runCurrent()

    scopes.outboxStores.clearAll()
    runCurrent()

    a.assertCancelled()
    b.assertCancelled()
  }

  @Test
  fun `a re-attached handle whose row is cleared during its first read settles cancelled`() =
    runTest {
      val stores = InMemoryOutboxStores()
      val rows = stores.open("notes", String.serializer())
      val readTaken = CompletableDeferred<Unit>()
      val returnRead = CompletableDeferred<Unit>()
      var holdReads = false
      // Returns the row as it was, a little late, like a value crossing threads
      val lagging =
        object : OutboxStore<String> by rows {
          override fun entry(id: EntryId): Flow<OutboxEntry<String>?> = flow {
            val row = rows.entry(id).first()
            if (holdReads) {
              holdReads = false
              readTaken.complete(Unit)
              returnRead.await()
            }
            emit(row)
          }
        }
      // Left by an earlier process
      val id = rows.insert("k", "a")
      val outbox = testDataScope(online = false).outbox("lagging", lagging, send = send)
      runCurrent()

      holdReads = true
      val handle = outbox.operation(id)
      readTaken.await()
      // A logout clears the rows while the read is on its way back
      stores.clearAll()
      runCurrent()
      returnRead.complete(Unit)
      runCurrent()

      handle.assertCancelled()
      assertThat(outbox.rowsFollowed).isEqualTo(0)
    }

  @Test
  fun `a row read that fails during a sweep does not fail a handle whose row then sends`() =
    runTest {
      val rows = inMemoryOutboxStore<String>()
      var failReads = false
      val flaky =
        object : OutboxStore<String> by rows {
          override fun entry(id: EntryId): Flow<OutboxEntry<String>?> = flow {
            if (failReads) {
              throw IllegalStateException("database is locked")
            }
            emitAll(rows.entry(id))
          }
        }
      val data = testDataScope(online = false)
      val outbox = data.outbox("flaky", flaky, send = send)
      val a = outbox.enqueue("a", key = "k")
      val b = outbox.enqueue("b", key = "k")
      runCurrent()

      // Row reads fail while the key drains. The drain reads heads only.
      failReads = true
      data.online.value = true
      runCurrent()
      failReads = false
      runCurrent()

      assertThat(send.sent).containsExactly("a", "b")
      a.assertSucceeded()
      b.assertSucceeded()
      assertThat(data.actionFailures.take()).isNotEmpty()
    }

  @Test
  fun `a sweep whose read failed settles its handles once reads work again`() = runTest {
    val rows = inMemoryOutboxStore<String>()
    var failReads = false
    val flaky =
      object : OutboxStore<String> by rows {
        override fun entry(id: EntryId): Flow<OutboxEntry<String>?> = flow {
          if (failReads) {
            throw IllegalStateException("database is locked")
          }
          emitAll(rows.entry(id))
        }
      }
    val data = testDataScope(online = false)
    val outbox = data.outbox("flaky", flaky, send = send)
    val a = outbox.enqueue("a", key = "k")
    val b = outbox.enqueue("b", key = "k")
    runCurrent()

    // Another writer discards the key's rows while row reads fail, so the sweep learns nothing
    failReads = true
    for (row in rows.entries().first()) {
      rows.update(row.copy(status = OutboxStatus.Discarded), OutboxStatus.Queued)
    }
    runCurrent()
    a.assertPending()
    assertThat(data.actionFailures.take()).isNotEmpty()

    // No head on the key changes again, so only the sweep's own retry can settle them
    failReads = false
    advanceTimeBy(1.seconds)
    runCurrent()

    a.assertCancelled()
    b.assertCancelled()
    assertThat(outbox.rowsFollowed).isEqualTo(0)
  }

  @Test
  fun `a handle whose first read lags still carries the exception send threw`() = runTest {
    val rows = inMemoryOutboxStore<String>()
    val failedWritten = CompletableDeferred<Unit>()
    val finishWrite = CompletableDeferred<Unit>()
    val returnRead = CompletableDeferred<Unit>()
    val slow =
      object : OutboxStore<String> by rows {
        // Holds the drain between its Failed write and the settle
        override suspend fun update(entry: OutboxEntry<String>, expected: OutboxStatus): Boolean {
          val wrote = rows.update(entry, expected)
          if (entry.status is OutboxStatus.Failed) {
            failedWritten.complete(Unit)
            finishWrite.await()
          }
          return wrote
        }

        // The handle's one read waits, like a read queued behind other handles' reads
        override fun entry(id: EntryId): Flow<OutboxEntry<String>?> = flow {
          returnRead.await()
          emitAll(rows.entry(id))
        }
      }
    val rejection = PermanentFailure("rejected")
    send.failure = { _, _ -> rejection }
    val outbox = testDataScope().outbox("slow", slow, send = send, revert = send::revert)

    val handle = outbox.enqueue("a")
    failedWritten.await()
    returnRead.complete(Unit)
    runCurrent()
    finishWrite.complete(Unit)
    runCurrent()

    assertThat(handle.assertFailed<PermanentFailure>()).isSameInstanceAs(rejection)
  }

  @Test
  fun `a fired timer whose row was discarded elsewhere is dropped`() = runTest {
    val rows = inMemoryOutboxStore<String>()
    send.failure = { _, _ -> IllegalStateException("flaky") }
    val outbox = testDataScope().outbox("rows", rows, send, send::revert, RetryPolicy { 1.seconds })
    outbox.enqueue("a", key = "k")
    runCurrent()
    val row = rows.entries().first().single()
    assertThat(outbox.timersHeld).isEqualTo(1)

    // As an optimistic store's clear discards a key's rows, through the store
    rows.update(row.copy(status = OutboxStatus.Discarded, reverted = true), row.status)
    advanceTimeBy(5.seconds)
    runCurrent()

    assertThat(outbox.timersHeld).isEqualTo(0)
    assertThat(outbox.rowsFollowed).isEqualTo(0)
  }

  @Test
  fun `a timer that fired offline for a row discarded elsewhere is dropped on reconnect`() =
    runTest {
      val rows = inMemoryOutboxStore<String>()
      send.failure = { _, _ -> IllegalStateException("flaky") }
      val data = testDataScope()
      val outbox = data.outbox("rows", rows, send, send::revert, RetryPolicy { 1.seconds })
      outbox.enqueue("a", key = "k")
      runCurrent()
      val row = rows.entries().first().single()

      data.online.value = false
      rows.update(row.copy(status = OutboxStatus.Discarded, reverted = true), row.status)
      advanceTimeBy(5.seconds)
      runCurrent()
      data.online.value = true
      runCurrent()

      assertThat(outbox.timersHeld).isEqualTo(0)
    }

  /** A rejection that says when to try again, like a 429 with a `Retry-After` header. */
  private class RateLimited(val retryAfter: Duration) : Exception("429 Too Many Requests")

  private companion object {
    val START = Instant.parse("2026-06-10T12:00:00Z")
  }
}
