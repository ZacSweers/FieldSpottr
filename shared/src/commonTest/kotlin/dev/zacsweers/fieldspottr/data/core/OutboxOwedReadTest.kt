// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import dev.zacsweers.fieldspottr.data.core.testing.TestFailures
import dev.zacsweers.fieldspottr.data.core.testing.TestSend
import dev.zacsweers.fieldspottr.data.core.testing.assertFailed
import dev.zacsweers.fieldspottr.data.core.testing.assertSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.insertNew
import kotlin.concurrent.Volatile
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * Reads of the rows a key owes its handle that throw or never return, and an owner that stops
 * around them. A read that throws must not leave a row sent without a send time after the key's
 * owed rows, and a read that waits must not keep the owner from stopping. An owner's stop must not
 * leave a row Sending once its send has ended, or skip a revert once its row is marked reverted.
 */
class OutboxOwedReadTest {
  @Serializable data class Flag(val on: Boolean)

  // Stopping it stops the data scope, so a test can check that the owner finishes
  private val ownerJob = Job()
  // The factory's handler, which hears the drain's failures
  private val failures = TestFailures()
  // The flag store's handler, which hears its failed commits
  private val own = TestFailures()
  private val flagRows = OwedReads(inMemoryOutboxStore<Flag>())
  private val textRows = OwedReads(inMemoryOutboxStore<String>())
  // The ids of the flag rows the server accepted, in order
  private val accepted = mutableListOf<EntryId>()
  private var server: Boolean? = null
  private val truth = QueryStore.inMemory(Fetched(false, START - 1.hours))
  // Runs after each write the flag store's truth takes
  private var afterTruthWrite: (() -> Unit)? = null

  /** A data scope over [ownerJob] whose clock stands still at [START], so sends tie. */
  private fun TestScope.dataScope(sender: OutboxSender<*>): DataScope =
    DataScope.Factory(onFailure = failures, online = MutableStateFlow(true), clock = FROZEN)
      .create(CoroutineScope(StandardTestDispatcher(testScheduler) + ownerJob), setOf(sender))

  /**
   * An optimistic flag store over [flagRows]. The server rejects the first change that turns the
   * flag on, and the truth fails its first [failingWrites] writes.
   */
  private fun TestScope.flags(failingWrites: Int): OptimisticQueryStore<Boolean, Flag> {
    var rejected = false
    val sender =
      Flags.sender(store = flagRows) { flag, id ->
        if (flag.on && !rejected) {
          rejected = true
          throw PermanentFailure("Rejected")
        }
        server = flag.on
        accepted += id
      }
    var failsLeft = failingWrites
    val base =
      QueryStore.of(
        truth.data,
        write = { fetched, keepsStored ->
          if (failsLeft > 0) {
            failsLeft--
            error("Disk full")
          }
          truth.writeFetched(fetched, keepsStored)
          afterTruthWrite?.invoke()
        },
        clear = truth::clearFetched,
      )
    return dataScope(sender)
      .optimisticStore(Flags, base = base, apply = { _, flag -> flag.on }, onFailure = own)
  }

  /**
   * Sends an older change, which the server rejects, then a newer one, whose commit fails, so the
   * newer row is owed at [START] with a commit retry due after a second. Returns both handles.
   */
  private suspend fun TestScope.olderRejectedNewerOwed(
    store: OptimisticQueryStore<Boolean, Flag>
  ): Pair<OutboxOperation, OutboxOperation> {
    // The handle reads the owed rows, so it is ready
    runCurrent()
    val older = store.mutate(Flag(true))
    runCurrent()
    older.assertFailed<PermanentFailure>()
    val newer = store.mutate(Flag(false))
    runCurrent()
    newer.assertSucceeded()
    val owed = flagRows.row(newer.id)
    assertThat(owed.status).isEqualTo(OutboxStatus.Sent)
    assertThat(owed.committed).isFalse()
    assertThat(owed.sentAt).isEqualTo(START)
    assertThat(own.take().map { it.message }).containsExactly("Disk full")
    assertThat(failures.take().map { it.message }).containsExactly("Rejected")
    return older to newer
  }

  @Test
  fun `a lookup that throws leaves the row unsent`() = runTest {
    try {
      // Two failed writes keep the newer row owed after the older one sends
      val store = flags(failingWrites = 2)
      val (older, newer) = olderRejectedNewerOwed(store)

      // Fails every pass that drains the key, the retry's and the one the store's change starts
      flagRows.failKeyReads = true
      val retried = store.retry(older.id)
      runCurrent()

      assertThat(accepted).containsExactly(newer.id)
      assertThat(flagRows.row(older.id).status).isEqualTo(OutboxStatus.Queued)
      assertThat(failures.take().map { it.message }).containsExactly("Disk error", "Disk error")

      flagRows.failKeyReads = false
      // The commit retry and the drain's retry are both due after a second
      advanceTimeBy(1.seconds)
      runCurrent()

      assertThat(accepted).containsExactly(newer.id, older.id)
      retried.assertSucceeded()
      val olderRow = flagRows.row(older.id)
      val newerRow = flagRows.row(newer.id)
      for (row in listOf(olderRow, newerRow)) {
        assertThat(row.status).isEqualTo(OutboxStatus.Sent)
        assertThat(row.committed).isFalse()
      }
      assertThat(newerRow.sentAt).isEqualTo(START)
      // Recorded after the owed newer row, so it commits last
      assertThat(olderRow.sentAt).isEqualTo(START + 1.milliseconds)

      advanceTimeBy(2.seconds)
      runCurrent()

      assertThat(server).isEqualTo(true)
      assertThat(truth.data.first()?.value).isEqualTo(true)
      assertThat(flagRows.owed()).isEmpty()
      assertThat(accepted).containsExactly(newer.id, older.id)
      assertThat(own.take().map { it.message }).containsExactly("Disk full")
      failures.assertNone()
    } finally {
      ownerJob.cancel()
    }
  }

  @Test
  fun `the owner can stop while the lookup waits`() = runTest {
    val gate = CompletableDeferred<Unit>()
    try {
      val store = flags(failingWrites = 1)
      val (older, newer) = olderRejectedNewerOwed(store)
      val entered = CompletableDeferred<Unit>()
      flagRows.nextKeyRead = {
        entered.complete(Unit)
        gate.await()
      }
      store.retry(older.id)
      runCurrent()
      assertThat(entered.isCompleted).isTrue()

      ownerJob.cancel()
      runCurrent()

      assertThat(ownerJob.isCompleted).isTrue()
      assertThat(gate.isCompleted).isFalse()
      assertThat(accepted).containsExactly(newer.id)
      assertThat(flagRows.row(older.id).status).isEqualTo(OutboxStatus.Queued)
    } finally {
      gate.complete(Unit)
      ownerJob.cancel()
    }
  }

  @Test
  fun `the owner can stop while the read after an owed write waits`() = runTest {
    val attaching = CompletableDeferred<Unit>()
    val settling = CompletableDeferred<Unit>()
    try {
      val entered = CompletableDeferred<Unit>()
      // The handle's read of every owed row waits, so the handle is attached and not ready
      textRows.nextAllRead = { attaching.await() }
      val sender =
        Rows.sender(store = textRows) { _, _ ->
          textRows.nextKeyRead = {
            entered.complete(Unit)
            settling.await()
          }
        }
      val outbox = dataScope(sender).outbox(Rows)
      val handle = outbox.enqueue("a", key = "k")
      runCurrent()

      assertThat(entered.isCompleted).isTrue()
      val row = textRows.row(handle.id)
      assertThat(row.status).isEqualTo(OutboxStatus.Sent)
      assertThat(row.committed).isFalse()
      handle.assertSucceeded()

      ownerJob.cancel()
      runCurrent()

      assertThat(ownerJob.isCompleted).isTrue()
      assertThat(attaching.isCompleted).isFalse()
      assertThat(settling.isCompleted).isFalse()
    } finally {
      attaching.complete(Unit)
      settling.complete(Unit)
      ownerJob.cancel()
    }
  }

  @Test
  fun `a stop while the read of older owed rows waits keeps the sent row`() = runTest {
    val settling = CompletableDeferred<Unit>()
    val send = TestSend<String>()
    val attempts = mutableListOf<EntryId>()
    val sender =
      Rows.sender(store = textRows) { payload, id ->
        attempts += id
        send(payload, id)
      }
    try {
      val y1 = textRows.insertNew("k", "y1")
      val y2 = textRows.insertNew("k", "y2")
      send.hold("y2")
      // No handle is attached, so y1 is stored owed, and y2's send waits
      val data = dataScope(sender)
      runCurrent()
      assertThat(textRows.row(y1).isOwed).isTrue()
      assertThat(textRows.row(y2).status).isEqualTo(OutboxStatus.Sending)

      val attached = CompletableDeferred<Unit>()
      textRows.nextAllRead = { attached.complete(Unit) }
      data.outbox(Rows)
      // The handle reads the owed rows and is ready
      runCurrent()
      assertThat(attached.isCompleted).isTrue()
      val entered = CompletableDeferred<Unit>()
      textRows.nextKeyRead = {
        entered.complete(Unit)
        settling.await()
      }
      send.release("y2")
      runCurrent()
      assertThat(entered.isCompleted).isTrue()
      assertThat(send.sent).containsExactly("y1", "y2")

      ownerJob.cancel()
      runCurrent()

      assertThat(ownerJob.isCompleted).isTrue()
      assertThat(settling.isCompleted).isFalse()
      // Saved before the read, one millisecond after the older row, so it commits second
      val saved = textRows.row(y2)
      assertThat(saved.status).isEqualTo(OutboxStatus.Sent)
      assertThat(saved.committed).isFalse()
      assertThat(saved.sentAt).isEqualTo(START + 1.milliseconds)
      assertThat(textRows.row(y1).isOwed).isTrue()

      // The next owner sends neither row again and settles both
      DataScope.Factory(onFailure = failures, online = MutableStateFlow(true), clock = FROZEN)
        .create(backgroundScope, setOf(sender))
        .outbox(Rows)
      runCurrent()

      assertThat(attempts).containsExactly(y1, y2)
      assertThat(textRows.owed("k")).isEmpty()
      failures.assertNone()
    } finally {
      settling.complete(Unit)
      ownerJob.cancel()
    }
  }

  @Test
  fun `a send that returns after the owner stopped is saved with no handle`() = runTest {
    val gate = CompletableDeferred<Unit>()
    val attempts = mutableListOf<EntryId>()
    val sender = gatedSender(gate, attempts)
    try {
      val id = textRows.insertNew("k", "a")
      dataScope(sender)
      runCurrent()
      assertThat(attempts).containsExactly(id)

      ownerJob.cancel()
      runCurrent()
      assertThat(ownerJob.isCompleted).isFalse()
      gate.complete(Unit)
      runCurrent()

      assertThat(ownerJob.isCompleted).isTrue()
      val saved = textRows.row(id)
      assertThat(saved.status).isEqualTo(OutboxStatus.Sent)
      assertThat(saved.committed).isFalse()

      // The next owner commits the row and does not send it again
      DataScope.Factory(onFailure = failures, online = MutableStateFlow(true), clock = FROZEN)
        .create(backgroundScope, setOf(sender))
        .outbox(Rows)
      runCurrent()

      assertThat(attempts).containsExactly(id)
      assertThat(textRows.owed("k")).isEmpty()
      failures.assertNone()
    } finally {
      gate.complete(Unit)
      ownerJob.cancel()
    }
  }

  @Test
  fun `a send that returns after the owner stopped is committed with a ready handle`() = runTest {
    val gate = CompletableDeferred<Unit>()
    val attempts = mutableListOf<EntryId>()
    val sender = gatedSender(gate, attempts)
    try {
      val outbox = dataScope(sender).outbox(Rows)
      // The handle reads the owed rows, so it is ready
      runCurrent()
      val handle = outbox.enqueue("a", key = "k")
      runCurrent()
      assertThat(attempts).containsExactly(handle.id)

      ownerJob.cancel()
      runCurrent()
      assertThat(ownerJob.isCompleted).isFalse()
      gate.complete(Unit)
      runCurrent()

      assertThat(ownerJob.isCompleted).isTrue()
      val saved = textRows.row(handle.id)
      assertThat(saved.status).isEqualTo(OutboxStatus.Sent)
      assertThat(saved.committed).isTrue()

      // The next owner finds nothing to send
      DataScope.Factory(onFailure = failures, online = MutableStateFlow(true), clock = FROZEN)
        .create(backgroundScope, setOf(sender))
        .outbox(Rows)
      runCurrent()

      assertThat(attempts).containsExactly(handle.id)
      assertThat(textRows.owed("k")).isEmpty()
      failures.assertNone()
    } finally {
      gate.complete(Unit)
      ownerJob.cancel()
    }
  }

  @Test
  fun `a stopped owner sends no further row`() = runTest {
    val gate = CompletableDeferred<Unit>()
    val attempts = mutableListOf<EntryId>()
    val sender = gatedSender(gate, attempts)
    try {
      val first = textRows.insertNew("k", "a")
      val second = textRows.insertNew("k", "b")
      dataScope(sender)
      runCurrent()
      assertThat(attempts).containsExactly(first)

      ownerJob.cancel()
      gate.complete(Unit)
      runCurrent()

      // The first send returned after the stop, so its row is saved, and the drain ends there. The
      // in-memory store never checks for cancellation, so the drain has to.
      assertThat(ownerJob.isCompleted).isTrue()
      assertThat(textRows.row(first).status).isEqualTo(OutboxStatus.Sent)
      assertThat(attempts).containsExactly(first)
      assertThat(textRows.row(second).status).isEqualTo(OutboxStatus.Queued)
    } finally {
      gate.complete(Unit)
      ownerJob.cancel()
    }
  }

  @Test
  fun `a stopped owner settles no owed row`() = runTest {
    val gate = CompletableDeferred<Unit>()
    val attempts = mutableListOf<EntryId>()
    val sender = gatedSender(gate, attempts)
    try {
      val id = textRows.insertNew("k", "a")
      val data = dataScope(sender)
      runCurrent()
      // The handle attaches while the send waits, so the sent row is stored owed
      val attached = CompletableDeferred<Unit>()
      textRows.nextAllRead = { attached.await() }
      data.outbox(Rows)
      runCurrent()

      ownerJob.cancel()
      gate.complete(Unit)
      runCurrent()

      assertThat(ownerJob.isCompleted).isTrue()
      assertThat(attached.isCompleted).isFalse()
      // Saved, and left for the next owner's handle to commit
      assertThat(textRows.row(id).isOwed).isTrue()

      DataScope.Factory(onFailure = failures, online = MutableStateFlow(true), clock = FROZEN)
        .create(backgroundScope, setOf(sender))
        .outbox(Rows)
      runCurrent()

      assertThat(attempts).containsExactly(id)
      assertThat(textRows.owed("k")).isEmpty()
      failures.assertNone()
    } finally {
      gate.complete(Unit)
      ownerJob.cancel()
    }
  }

  @Test
  fun `a row that failed for good is recorded when the owner stops before its write`() = runTest {
    val attempts = mutableListOf<EntryId>()
    val reverted = mutableListOf<String>()
    val revert: suspend (String, EntryId) -> Unit = { payload, _ -> reverted += payload }
    // The engine asks the policy between the send's end and the row's write, so the stop lands
    // there
    val stopThenFail =
      object : RetryPolicy {
        override fun delayAfter(attempt: Int, failure: Throwable): Duration? {
          ownerJob.cancel()
          return null
        }
      }
    val sender =
      Rows.sender(store = textRows, retry = stopThenFail) { payload, id ->
        attempts += id
        error("Refused $payload")
      }
    try {
      val outbox = dataScope(sender).outbox(Rows, revert = revert)
      // The handle reads the owed rows, so it is ready
      runCurrent()
      val handle = outbox.enqueue("a", key = "k")
      runCurrent()

      assertThat(ownerJob.isCompleted).isTrue()
      val saved = textRows.row(handle.id)
      assertThat(saved.status).isInstanceOf<OutboxStatus.Failed>()
      assertThat(saved.reverted).isTrue()
      assertThat(reverted).containsExactly("a")
      assertThat(failures.take().map { it.message }).containsExactly("Refused a")

      // The next owner neither sends the row again nor reverts it again
      DataScope.Factory(onFailure = failures, online = MutableStateFlow(true), clock = FROZEN)
        .create(backgroundScope, setOf(sender))
        .outbox(Rows, revert = revert)
      runCurrent()

      assertThat(attempts).containsExactly(handle.id)
      assertThat(reverted).containsExactly("a")
      failures.assertNone()
    } finally {
      ownerJob.cancel()
    }
  }

  @Test
  fun `a row that will retry is recorded when the owner stops before its write`() = runTest {
    // The engine asks the policy between the send's end and the row's write, so the stop lands
    // there
    val stopThenWait =
      object : RetryPolicy {
        override fun delayAfter(attempt: Int, failure: Throwable): Duration? {
          // The write checks for cancellation first, as a store on another dispatcher does
          textRows.nextUpdate = { currentCoroutineContext().ensureActive() }
          ownerJob.cancel()
          return 5.seconds
        }
      }
    val sender =
      Rows.sender(store = textRows, retry = stopThenWait) { payload, _ -> error("Busy $payload") }
    try {
      val id = textRows.insertNew("k", "a")
      dataScope(sender)
      runCurrent()

      assertThat(ownerJob.isCompleted).isTrue()
      val saved = textRows.row(id)
      assertThat(saved.status).isEqualTo(OutboxStatus.Queued)
      assertThat(saved.attempts).isEqualTo(1)
      assertThat(saved.retryAt).isEqualTo(START + 5.seconds)
      failures.assertNone()
    } finally {
      ownerJob.cancel()
    }
  }

  @Test
  fun `a stopped owner commits no further owed row`() = runTest {
    try {
      // One failed write leaves the first change owed, and the second waits behind it
      val store = flags(failingWrites = 1)
      // The handle reads the owed rows, so it is ready
      runCurrent()
      val first = store.mutate(Flag(false))
      runCurrent()
      val second = store.mutate(Flag(false))
      runCurrent()
      assertThat(flagRows.owed().map { it.id }).containsExactly(first.id, second.id)
      assertThat(own.take().map { it.message }).containsExactly("Disk full")

      // The commit retry comes due, and the owner stops during the first row's commit
      afterTruthWrite = { ownerJob.cancel() }
      advanceTimeBy(1.seconds)
      runCurrent()

      assertThat(ownerJob.isCompleted).isTrue()
      assertThat(flagRows.owed().map { it.id }).containsExactly(second.id)
      failures.assertNone()
    } finally {
      ownerJob.cancel()
    }
  }

  @Test
  fun `a stop during an apply still stores the row`() = runTest {
    val send = TestSend<String>()
    val sender = Rows.sender(store = textRows, send = send)
    val applied = mutableListOf<String>()
    try {
      val outbox =
        dataScope(sender)
          .outbox(
            Rows,
            apply = { payload, _ ->
              applied += payload
              // The owner stops once the change is made. The apply then checks for cancellation,
              // as a write on another dispatcher does when it returns.
              ownerJob.cancel()
              currentCoroutineContext().ensureActive()
            },
          )
      runCurrent()
      val handle = outbox.enqueue("a", key = "k")
      runCurrent()

      assertThat(ownerJob.isCompleted).isTrue()
      assertThat(applied).containsExactly("a")
      // The change is made, so its row is stored for the next owner to send
      assertThat(textRows.row(handle.id).status).isEqualTo(OutboxStatus.Queued)
      assertThat(send.attempts).isEmpty()

      DataScope.Factory(onFailure = failures, online = MutableStateFlow(true), clock = FROZEN)
        .create(backgroundScope, setOf(sender))
      runCurrent()

      assertThat(send.sent).containsExactly("a")
      failures.assertNone()
    } finally {
      ownerJob.cancel()
    }
  }

  @Test
  fun `a stop while the write of a sent row waits keeps the row`() = runTest {
    val writing = CompletableDeferred<Unit>()
    val attempts = mutableListOf<EntryId>()
    try {
      val entered = CompletableDeferred<Unit>()
      val sender =
        Rows.sender(store = textRows) { _, id ->
          attempts += id
          // The next write is the one that records this send
          textRows.nextUpdate = {
            entered.complete(Unit)
            writing.await()
          }
        }
      val outbox = dataScope(sender).outbox(Rows)
      // The handle reads the owed rows, so it is ready
      runCurrent()
      val handle = outbox.enqueue("a", key = "k")
      runCurrent()
      assertThat(entered.isCompleted).isTrue()
      assertThat(textRows.row(handle.id).status).isEqualTo(OutboxStatus.Sending)

      ownerJob.cancel()
      runCurrent()
      // The write has started on the row's key, so the stop waits for it
      assertThat(ownerJob.isCompleted).isFalse()
      writing.complete(Unit)
      runCurrent()

      assertThat(ownerJob.isCompleted).isTrue()
      val saved = textRows.row(handle.id)
      assertThat(saved.status).isEqualTo(OutboxStatus.Sent)
      assertThat(saved.committed).isTrue()

      // The next owner finds nothing to send
      DataScope.Factory(onFailure = failures, online = MutableStateFlow(true), clock = FROZEN)
        .create(backgroundScope, setOf(sender))
        .outbox(Rows)
      runCurrent()

      assertThat(attempts).containsExactly(handle.id)
      failures.assertNone()
    } finally {
      writing.complete(Unit)
      ownerJob.cancel()
    }
  }

  @Test
  fun `a stop after an owed failed row is marked reverted still reverts it`() = runTest {
    val reverting = CompletableDeferred<Unit>()
    val reverted = mutableListOf<String>()
    try {
      val sender =
        Rows.sender(store = textRows) { payload, _ -> throw PermanentFailure("Rejected $payload") }
      val id = textRows.insertNew("k", "a")
      // No handle is attached, so the failed row is stored owed
      val data = dataScope(sender)
      runCurrent()
      assertThat(textRows.row(id).isOwed).isTrue()
      assertThat(failures.take().map { it.message }).containsExactly("Rejected a")

      val entered = CompletableDeferred<Unit>()
      data.outbox(
        Rows,
        revert = { payload, _ ->
          entered.complete(Unit)
          reverting.await()
          reverted += payload
        },
      )
      runCurrent()
      assertThat(entered.isCompleted).isTrue()
      assertThat(textRows.row(id).reverted).isTrue()

      ownerJob.cancel()
      runCurrent()
      // No later owner reverts a row marked reverted, so the stop waits for this revert
      assertThat(ownerJob.isCompleted).isFalse()
      reverting.complete(Unit)
      runCurrent()

      assertThat(ownerJob.isCompleted).isTrue()
      assertThat(reverted).containsExactly("a")
      failures.assertNone()
    } finally {
      reverting.complete(Unit)
      ownerJob.cancel()
    }
  }

  /**
   * A sender over [textRows] that records each attempt in [attempts], then waits for [gate] where
   * the owner's stop cannot cut the wait off, and then returns. So a send can return after its
   * owner stopped.
   */
  private fun gatedSender(gate: CompletableDeferred<Unit>, attempts: MutableList<EntryId>) =
    Rows.sender(store = textRows) { _, id ->
      attempts += id
      withContext(NonCancellable) { gate.await() }
    }

  @Test
  fun `the owner can stop while the read after a failed row waits`() = runTest {
    val attaching = CompletableDeferred<Unit>()
    val settling = CompletableDeferred<Unit>()
    try {
      val entered = CompletableDeferred<Unit>()
      // The handle's read of every owed row waits, so the handle is attached and not ready
      textRows.nextAllRead = { attaching.await() }
      val sender =
        Rows.sender(store = textRows) { payload, _ ->
          textRows.nextKeyRead = {
            entered.complete(Unit)
            settling.await()
          }
          throw PermanentFailure("Rejected $payload")
        }
      val outbox = dataScope(sender).outbox(Rows)
      val handle = outbox.enqueue("a", key = "k")
      runCurrent()

      assertThat(entered.isCompleted).isTrue()
      val row = textRows.row(handle.id)
      assertThat(row.status).isInstanceOf<OutboxStatus.Failed>()
      assertThat(row.reverted).isFalse()
      handle.assertFailed<PermanentFailure>()

      ownerJob.cancel()
      runCurrent()

      assertThat(ownerJob.isCompleted).isTrue()
      assertThat(attaching.isCompleted).isFalse()
      assertThat(settling.isCompleted).isFalse()
    } finally {
      attaching.complete(Unit)
      settling.complete(Unit)
      ownerJob.cancel()
    }
  }

  /**
   * [rows] with a hook before each kind of owed read and before a row update. A hook runs once,
   * before the next such call, and a hook that throws fails that call. [failKeyReads] fails every
   * read of one key.
   */
  private class OwedReads<E>(private val rows: OutboxStore<E>) : OutboxStore<E> by rows {
    /** Runs before the next read of every owed row. */
    @Volatile var nextAllRead: (suspend () -> Unit)? = null

    /** Runs before the next read of one key's owed rows. */
    @Volatile var nextKeyRead: (suspend () -> Unit)? = null

    /** Whether every read of one key's owed rows throws, until it is cleared. */
    @Volatile var failKeyReads = false

    /** Runs before the next write that updates a row. */
    @Volatile var nextUpdate: (suspend () -> Unit)? = null

    override suspend fun update(entry: OutboxEntry<E>, expected: OutboxStatus): Boolean {
      nextUpdate?.let { hook ->
        nextUpdate = null
        hook()
      }
      return rows.update(entry, expected)
    }

    override suspend fun owed(): List<OutboxEntry<E>> {
      nextAllRead?.let { hook ->
        nextAllRead = null
        hook()
      }
      return rows.owed()
    }

    override suspend fun owed(key: String): List<OutboxEntry<E>> {
      if (failKeyReads) {
        throw IllegalStateException("Disk error")
      }
      nextKeyRead?.let { hook ->
        nextKeyRead = null
        hook()
      }
      return rows.owed(key)
    }

    /** Row [id] as stored now. Fails if it is gone. */
    suspend fun row(id: EntryId): OutboxEntry<E> =
      checkNotNull(rows.entry(id).first()) { "${id.value} is gone" }
  }

  private companion object {
    val START: Instant = Instant.parse("2026-06-10T12:00:00Z")
    val FROZEN =
      object : Clock {
        override fun now(): Instant = START
      }
    val Flags = OutboxKey<Flag>("flags")
    val Rows = OutboxKey<String>("rows")
  }
}
