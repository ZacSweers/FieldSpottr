// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import dev.zacsweers.fieldspottr.data.core.impl.keysSuperseding
import dev.zacsweers.fieldspottr.data.core.impl.rowsFollowed
import dev.zacsweers.fieldspottr.data.core.impl.timersHeld
import dev.zacsweers.fieldspottr.data.core.testing.LaggingOutboxStores
import dev.zacsweers.fieldspottr.data.core.testing.TestDataScope
import dev.zacsweers.fieldspottr.data.core.testing.TestFailures
import dev.zacsweers.fieldspottr.data.core.testing.TestSend
import dev.zacsweers.fieldspottr.data.core.testing.assertPending
import dev.zacsweers.fieldspottr.data.core.testing.awaitCancelled
import dev.zacsweers.fieldspottr.data.core.testing.awaitFailed
import dev.zacsweers.fieldspottr.data.core.testing.awaitSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.testClock
import dev.zacsweers.fieldspottr.data.core.testing.testDataScope
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer

/** Outbox entries with [OnPrevious.Supersede] around a drain, a failed call or a stopped owner. */
class OutboxSupersedeTest {
  private val send = TestSend<String>()

  private fun TestScope.dataOver(rows: OutboxStore<String>, online: Boolean): TestDataScope =
    testDataScope(
      clock = testClock(START),
      online = online,
      senders = setOf(Rows.sender(store = rows, send = send)),
    )

  /**
   * A drain on line k reads no head and waits before it acts on that read. Meanwhile b is stored
   * with Supersede. Once the drain goes on, c is stored with Supersede and must discard b, which
   * still waits.
   */
  @Test
  fun `a superseding entry stored while a drain reads an empty line is still superseded`() =
    runTest {
      val delegate = InMemoryOutboxStores().open(Rows.name, String.serializer())
      val gate = CompletableDeferred<Unit>()
      val gated = CompletableDeferred<Unit>()
      // Armed once x's send starts, so the drain's next head read after x is the gated one
      var armed = false
      val rows =
        object : OutboxStore<String> by delegate {
          override suspend fun head(key: String): OutboxEntry<String>? {
            val read = delegate.head(key)
            if (armed && key == "k" && read == null) {
              armed = false
              gated.complete(Unit)
              gate.await()
            }
            return read
          }
        }
      val data = dataOver(rows, online = true)
      val outbox = data.outbox(Rows, revert = send::revert)
      send.failure = { payload, _ ->
        if (payload == "x") {
          armed = true
        }
        null
      }

      outbox.enqueue("x", Ordering.Keyed("k")).awaitSucceeded()
      gated.await()
      // The drain holds a read of no head on k
      val b = outbox.enqueue("b", Superseding)
      b.stored.awaitSucceeded()
      runCurrent()
      assertThat(outbox.keysSuperseding, "keys once b is stored").isEqualTo(1)
      data.online.value = false
      gate.complete(Unit)
      runCurrent()
      assertThat(rows.entry(b.id).first()?.status).isEqualTo(OutboxStatus.Queued)
      assertThat(outbox.keysSuperseding, "keys once the drain went on").isEqualTo(1)

      val c = outbox.enqueue("c", Superseding)
      c.stored.awaitSucceeded()
      runCurrent()
      advanceTimeBy(1.seconds)
      runCurrent()

      assertThat(rows.entry(b.id).first()?.status, "b once c is stored")
        .isEqualTo(OutboxStatus.Discarded)
    }

  /**
   * Offline, the caller discards the only entry on line k. The key keeps its superseding entry
   * while offline, since a drain stops for the network before it reads the head. Once the network
   * returns, the drain finds no head and the key goes.
   */
  @Test
  fun `a key emptied while offline keeps its superseding entry until the network returns`() =
    runTest {
      val rows = InMemoryOutboxStores().open(Rows.name, String.serializer())
      val data = dataOver(rows, online = false)
      val outbox = data.outbox(Rows, revert = send::revert)
      val a = outbox.enqueue("a", Superseding)
      a.stored.awaitSucceeded()
      outbox.discard(a.id).awaitSucceeded()
      runCurrent()
      assertThat(outbox.keysSuperseding).isEqualTo(1)

      data.online.value = true
      runCurrent()
      advanceTimeBy(1.seconds)
      runCurrent()

      assertThat(outbox.keysSuperseding).isEqualTo(0)
    }

  /**
   * After a is stored, a superseding call whose apply throws and one whose insert throws both fail.
   * Neither discards a, and a later stored superseding call still does.
   */
  @Test
  fun `a superseding call whose apply or insert throws leaves the earlier entry`() = runTest {
    val delegate = InMemoryOutboxStores().open(Rows.name, String.serializer())
    val rows =
      object : OutboxStore<String> by delegate {
        override suspend fun insert(id: EntryId, key: String, payload: String) {
          check(payload != "badInsert") { "Disk full" }
          delegate.insert(id, key, payload)
        }
      }
    val data = dataOver(rows, online = false)
    val failures = TestFailures()
    val outbox =
      data.outbox(
        Rows,
        apply = { payload, _ -> check(payload != "badApply") { "Apply threw" } },
        revert = send::revert,
        onFailure = failures,
      )
    val a = outbox.enqueue("a", Superseding)
    a.stored.awaitSucceeded()

    outbox.enqueue("badApply", Superseding).awaitFailed<IllegalStateException>()
    outbox.enqueue("badInsert", Superseding).awaitFailed<IllegalStateException>()
    runCurrent()
    a.assertPending()
    assertThat(outbox.keysSuperseding).isEqualTo(1)
    assertThat(send.reverted).containsExactly("badInsert")

    val c = outbox.enqueue("c", Superseding)
    a.awaitCancelled()
    data.online.value = true
    c.awaitSucceeded()
    assertThat(send.sent).containsExactly("c")
    assertThat(send.reverted).containsExactly("badInsert", "a")
  }

  /**
   * A stored holder of reject key r waits, then a is stored with Supersede. A superseding call with
   * key r is rejected and discards nothing, and a later stored superseding call still discards a.
   */
  @Test
  fun `a superseding call rejected by a stored holder leaves the earlier entry`() = runTest {
    val data = dataOver(InMemoryOutboxStores().open(Rows.name, String.serializer()), online = false)
    val outbox = data.outbox(Rows, revert = send::revert)

    val holder = outbox.enqueue("h", admission = Admission.Reject("r"))
    val a = outbox.enqueue("a", Superseding)
    a.stored.awaitSucceeded()
    runCurrent()
    val b = outbox.enqueue("b", Superseding, Admission.Reject("r"))
    b.awaitFailed<CommandRejectedException>()
    runCurrent()

    a.assertPending()
    assertThat(outbox.keysSuperseding).isEqualTo(1)
    val c = outbox.enqueue("c", Superseding)
    a.awaitCancelled()
    data.online.value = true
    c.awaitSucceeded()
    holder.awaitSucceeded()
    assertThat(send.sent.toSet()).isEqualTo(setOf("h", "c"))
    assertThat(send.reverted).containsExactly("a")
    data.assertNoFailures()
  }

  /**
   * a fails for good, which reverts it, and the caller retries it while offline. A newer
   * superseding entry discards the retried a. The discard does not revert a again, since its row
   * keeps the reverted mark.
   */
  @Test
  fun `a retried failed superseding entry is superseded without a second revert`() = runTest {
    val data = dataOver(InMemoryOutboxStores().open(Rows.name, String.serializer()), online = true)
    val outbox = data.outbox(Rows, revert = send::revert)
    // Offline once the send ends, so the drain stops before it reads the line again
    send.failure = { payload, _ ->
      data.online.value = false
      PermanentFailure("Rejected $payload")
    }
    val a = outbox.enqueue("a", Superseding)
    a.awaitFailed<PermanentFailure>()
    send.succeed()
    val retried = outbox.retry(a.id)
    runCurrent()

    val b = outbox.enqueue("b", Superseding)
    retried.awaitCancelled()
    data.online.value = true
    b.awaitSucceeded()
    runCurrent()

    assertThat(send.sent).containsExactly("b")
    assertThat(send.reverted).containsExactly("a")
    assertThat(outbox.keysSuperseding).isEqualTo(0)
    assertThat(outbox.rowsFollowed).isEqualTo(0)
  }

  /**
   * a fails once and backs off, so it is Queued with a timer. A newer superseding entry discards a
   * and its timer, and b sends.
   */
  @Test
  fun `a superseding entry discards one backing off and drops its timer`() = runTest {
    val data = dataOver(InMemoryOutboxStores().open(Rows.name, String.serializer()), online = true)
    val outbox = data.outbox(Rows, revert = send::revert)
    send.failTransiently(times = 1)
    val a = outbox.enqueue("a", Superseding)
    a.stored.awaitSucceeded()
    runCurrent()
    assertThat(outbox.timersHeld).isEqualTo(1)

    val b = outbox.enqueue("b", Superseding)
    a.awaitCancelled()
    b.awaitSucceeded()
    runCurrent()

    assertThat(send.reverted).containsExactly("a")
    assertThat(outbox.timersHeld).isEqualTo(0)
    assertThat(outbox.keysSuperseding).isEqualTo(0)
    assertThat(outbox.rowsFollowed).isEqualTo(0)
  }

  /**
   * The caller discards a while a newer superseding entry makes the engine discard it too. a
   * reverts once. The caller's discard succeeds, or fails because a no longer waits.
   */
  @Test
  fun `a caller discard racing the engine discard reverts once`() = runTest {
    val data = dataOver(InMemoryOutboxStores().open(Rows.name, String.serializer()), online = false)
    val failures = TestFailures()
    val outbox = data.outbox(Rows, revert = send::revert, onFailure = failures)
    val a = outbox.enqueue("a", Superseding)
    a.stored.awaitSucceeded()

    val b = outbox.enqueue("b", Superseding)
    val discard = outbox.discard(a.id)
    a.awaitCancelled()
    val outcome = discard.await()
    runCurrent()

    assertThat(send.reverted).containsExactly("a")
    if (outcome is OperationState.Failed) {
      assertThat(outcome.cause).isInstanceOf<IllegalStateException>()
    }
    data.online.value = true
    b.awaitSucceeded()
    assertThat(send.sent).containsExactly("b")
  }

  /**
   * a is stored with Supersede. The owner stops after b's insert stored its row and before the
   * insert returned, so b's enqueue never discards a. The next owner sends a, then b.
   */
  @Test
  fun `an owner that stops right after the newer insert leaves both entries in order`() = runTest {
    val stores = LaggingOutboxStores(insertReturnsAfter = 1.seconds)
    val online = MutableStateFlow(false)
    val factory =
      DataScope.Factory(
        onFailure = TestFailures(),
        online = online,
        stores = stores,
        clock = testClock(START),
      )
    val owner =
      CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext.job))
    val first =
      factory.create(owner, setOf(Rows.sender(send = send))).outbox(Rows, revert = send::revert)
    first.enqueue("a", Superseding).stored.awaitSucceeded()
    first.enqueue("b", Superseding)
    advanceTimeBy(500)
    runCurrent()
    val rows = stores.delegate.open(Rows.name, String.serializer())
    assertThat(rows.entries().first().map { it.payload }).containsExactly("a", "b")
    owner.cancel()
    runCurrent()
    advanceTimeBy(1.seconds)
    runCurrent()

    factory
      .create(backgroundScope, setOf(Rows.sender(send = send)))
      .outbox(Rows, revert = send::revert)
    online.value = true
    runCurrent()
    advanceTimeBy(1.seconds)
    runCurrent()

    assertThat(send.sent).containsExactly("a", "b")
    assertThat(send.reverted).isEmpty()
  }

  private companion object {
    val START = Instant.fromEpochSeconds(1_700_000_000)
    val Rows = OutboxKey<String>("rows")
    /** The line "k", where each entry supersedes a waiting one before it that did the same. */
    val Superseding = Ordering.Keyed("k", OnPrevious.Supersede)
  }
}
