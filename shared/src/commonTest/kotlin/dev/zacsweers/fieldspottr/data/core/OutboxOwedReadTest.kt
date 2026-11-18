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
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable

/**
 * Reads of the rows a key owes its handle that throw or never return. A read that throws must not
 * leave a row sent without a send time after the key's owed rows, and a read that waits must not
 * keep the owner from stopping.
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
  fun `the owner can stop while the read before a live commit waits`() = runTest {
    val settling = CompletableDeferred<Unit>()
    val send = TestSend<String>()
    try {
      val y1 = textRows.insertNew("k", "y1")
      val y2 = textRows.insertNew("k", "y2")
      send.hold("y2")
      // No handle is attached, so y1 is stored owed, and y2's send waits
      val data = dataScope(Rows.sender(store = textRows, send = send))
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

      ownerJob.cancel()
      runCurrent()

      assertThat(ownerJob.isCompleted).isTrue()
      assertThat(settling.isCompleted).isFalse()
      assertThat(send.sent).containsExactly("y1", "y2")
      assertThat(textRows.row(y2).status).isEqualTo(OutboxStatus.Sending)
      assertThat(textRows.row(y1).isOwed).isTrue()
    } finally {
      settling.complete(Unit)
      ownerJob.cancel()
    }
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
   * [rows] with a hook before each kind of owed read. A hook runs once, before the next such read,
   * and a hook that throws fails that read. [failKeyReads] fails every read of one key.
   */
  private class OwedReads<E>(private val rows: OutboxStore<E>) : OutboxStore<E> by rows {
    /** Runs before the next read of every owed row. */
    @Volatile var nextAllRead: (suspend () -> Unit)? = null

    /** Runs before the next read of one key's owed rows. */
    @Volatile var nextKeyRead: (suspend () -> Unit)? = null

    /** Whether every read of one key's owed rows throws, until it is cleared. */
    @Volatile var failKeyReads = false

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
