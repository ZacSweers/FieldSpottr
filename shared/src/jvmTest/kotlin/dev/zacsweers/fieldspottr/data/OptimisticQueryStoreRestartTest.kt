// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import dev.zacsweers.fieldspottr.data.core.DataScope
import dev.zacsweers.fieldspottr.data.core.EntryId
import dev.zacsweers.fieldspottr.data.core.Fetched
import dev.zacsweers.fieldspottr.data.core.InMemoryQueryStore
import dev.zacsweers.fieldspottr.data.core.OptimisticQueryStore
import dev.zacsweers.fieldspottr.data.core.OutboxEntry
import dev.zacsweers.fieldspottr.data.core.OutboxKey
import dev.zacsweers.fieldspottr.data.core.OutboxStatus
import dev.zacsweers.fieldspottr.data.core.OutboxStore
import dev.zacsweers.fieldspottr.data.core.OutboxStores
import dev.zacsweers.fieldspottr.data.core.PermanentFailure
import dev.zacsweers.fieldspottr.data.core.QueryStore
import dev.zacsweers.fieldspottr.data.core.exchangingSender
import dev.zacsweers.fieldspottr.data.core.replying
import dev.zacsweers.fieldspottr.data.core.sender
import dev.zacsweers.fieldspottr.data.core.testing.TestFailures
import dev.zacsweers.fieldspottr.data.core.testing.TestSend
import dev.zacsweers.fieldspottr.data.core.testing.awaitFailed
import dev.zacsweers.fieldspottr.data.core.testing.awaitSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.insertNew
import dev.zacsweers.fieldspottr.data.core.testing.testClock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.Volatile
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import org.junit.Rule

/**
 * An optimistic store whose rows live in the app's SQLite database, across two owners in turn, the
 * way a process restart leaves them. The truth is one in-memory store both owners share, standing
 * in for a durable one, since the rows are what these tests check.
 */
class OptimisticQueryStoreRestartTest {
  @get:Rule val temporaryDatabase = TemporaryDatabase()

  @Serializable data class Add(val tag: String)

  @Serializable data class Flag(val on: Boolean)

  private val send = TestSend<Add>()
  // Sends run on the owner's dispatcher, so the record is safe to write from any thread
  private val sentIds = CopyOnWriteArrayList<EntryId>()
  // How many send attempts have started, for a test to wait on
  private val attempts = MutableStateFlow(0)
  private val online = MutableStateFlow(false)
  private val truth: InMemoryQueryStore<List<String>> =
    QueryStore.inMemory(Fetched(listOf("a"), START))

  /**
   * Sends each row and records its id and attempt. Each process's data scope gets it at creation,
   * as the app's graph hands its senders to every new process.
   */
  private val sender = Tags.sender { add, id ->
    sentIds += id
    attempts.update { it + 1 }
    send(add, id)
  }

  /**
   * A new factory, like a new process: its own name claims, the same rows and network state. It
   * reads [clock], which follows virtual time by default, and keeps rows in [stores].
   */
  private fun TestScope.process(
    clock: Clock = testClock(START),
    stores: OutboxStores = stores(),
  ): DataScope.Factory =
    DataScope.Factory(
      onFailure = TestFailures(),
      onFetchFailure = TestFailures(),
      online = online,
      stores = stores,
      clock = clock,
    )

  /** A scope that ends with the test, or earlier when the test cancels it. */
  private fun TestScope.owner(): CoroutineScope =
    CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext.job))

  private fun DataScope.tags(): OptimisticQueryStore<List<String>, Add> =
    optimisticStore(Tags, base = truth, apply = { current, add -> current.orEmpty() + add.tag })

  @Test
  fun `rows outlive their owner and send in order after a restart`() = runTest {
    val first = owner()
    val before = process().create(first, setOf(sender)).tags()
    before.mutate(Add("b"))
    before.mutate(Add("c"))
    val waiting = before.pending.first { it.size == 2 }
    assertThat(before.data.first()?.value).isEqualTo(listOf("a", "b", "c"))

    first.cancel()

    val after = process().create(owner(), setOf(sender)).tags()
    // The rows are back from the database, so the changes still show before anything sends
    assertThat(after.data.first()?.value).isEqualTo(listOf("a", "b", "c"))
    online.value = true
    after.pending.first { it.isEmpty() }

    assertThat(send.sent).containsExactly(Add("b"), Add("c"))
    assertThat(sentIds).isEqualTo(waiting.map { it.id })
    assertThat(truth.data.value).isEqualTo(Fetched(listOf("a", "b", "c"), START))
    assertThat(after.data.first()?.value).isEqualTo(listOf("a", "b", "c"))
  }

  @Test
  fun `a stored server answer outlives a reopen and commits once a handle attaches`() = runTest {
    val rows = stores().open(ReplyingTags.name, Add.serializer())
    val ids = listOf(rows.insertNew("", Add("b")), rows.insertNew("", Add("c")))
    online.value = true
    val first = owner()
    process().create(first, setOf(replyingSender()))
    // No handle is attached, so each answer waits on its row
    val sent =
      rows.entries().first { list -> list.size == 2 && list.all { it.status == OutboxStatus.Sent } }
    assertThat(sent.map { it.reply }).containsExactly("""["a","B"]""", """["a","C"]""")
    stop(first)

    temporaryDatabase.reopen()

    val reopened = stores().open(ReplyingTags.name, Add.serializer())
    // The answers, send times and commit flags come back from the file as they were written
    assertThat(reopened.entries().first()).isEqualTo(sent)
    assertThat(reopened.owed()).isEqualTo(sent)
    val after = process().create(owner(), setOf(replyingSender()))
    after.optimisticStore(
      ReplyingTags,
      base = truth,
      apply = { current, add -> current.orEmpty() + add.tag },
    )
    reopened.entries().first { it.isEmpty() }

    // The newest answer replaces the truth, and nothing was sent again. c sent while b was owed at
    // the same instant, so c's send time is one millisecond later.
    assertThat(truth.data.value).isEqualTo(Fetched(listOf("a", "C"), START + 1.milliseconds))
    assertThat(replies.value).isEqualTo(2)
    for (id in ids) {
      val committed = checkNotNull(reopened.entry(id).first())
      assertThat(committed.status).isEqualTo(OutboxStatus.Sent)
      assertThat(committed.committed).isTrue()
      assertThat(committed.reply).isNull()
    }
  }

  @Test
  fun `a stored null answer outlives a reopen and replaces the truth with null`() = runTest {
    val rows = stores().open(NullableNote.name, Add.serializer())
    rows.insertNew("", Add("b"))
    online.value = true
    val first = owner()
    process().create(first, setOf(clearingSender()))
    val sent = rows.entries().first { list -> list.singleOrNull()?.status == OutboxStatus.Sent }
    // A null answer is stored as JSON null, distinct from an acknowledgment's missing reply
    assertThat(sent.single().reply).isEqualTo("null")
    stop(first)

    temporaryDatabase.reopen()

    val reopened = stores().open(NullableNote.name, Add.serializer())
    val note = QueryStore.inMemory<String?>(Fetched("draft", START - 1.hours))
    process()
      .create(owner(), setOf(clearingSender()))
      .optimisticStore(NullableNote, base = note, apply = { _, add -> add.tag })
    reopened.entries().first { it.isEmpty() }

    // The decoded null replaced the truth as a stored value, and nothing was sent again
    assertThat(note.data.value).isEqualTo(Fetched(null, START))
    assertThat(replies.value).isEqualTo(1)
  }

  @Test
  fun `a row committed before a reopen stays committed and applies once`() = runTest {
    online.value = true
    val first = owner()
    val before = process().create(first, setOf(sender)).tags()
    val handle = before.mutate(Add("b"))
    handle.awaitSucceeded()
    val rows = stores().open(Tags.name, Add.serializer())
    val committed = rows.entry(handle.id).first { it?.committed == true }
    assertThat(truth.data.value).isEqualTo(Fetched(listOf("a", "b"), START))
    stop(first)

    temporaryDatabase.reopen()

    val reopened = stores().open(Tags.name, Add.serializer())
    assertThat(reopened.entry(handle.id).first()).isEqualTo(committed)
    assertThat(reopened.owed()).isEmpty()
    val after = process().create(owner(), setOf(sender)).tags()
    assertThat(after.data.first()?.value).isEqualTo(listOf("a", "b"))
    assertThat(after.pending.first()).isEmpty()
    assertThat(truth.data.value).isEqualTo(Fetched(listOf("a", "b"), START))
    assertThat(send.sent).containsExactly(Add("b"))
    assertThat(reopened.entry(handle.id).first()).isEqualTo(committed)
  }

  @Test
  fun `a change left sending before a reopen is sent again with the same id`() = runTest {
    online.value = true
    send.hold(Add("b"))
    val first = owner()
    val before = process().create(first, setOf(sender)).tags()
    before.mutate(Add("b"))
    attempts.first { it == 1 }
    val sending =
      before.pending.first { it.singleOrNull()?.status == OutboxStatus.Sending }.single()
    first.cancel()
    send.release(Add("b"))
    stop(first)

    temporaryDatabase.reopen()

    val reopened = stores().open(Tags.name, Add.serializer())
    assertThat(reopened.entry(sending.id).first()?.status).isEqualTo(OutboxStatus.Sending)
    val after = process().create(owner(), setOf(sender)).tags()
    after.pending.first { it.isEmpty() }

    assertThat(send.attempts).containsExactly(Add("b"), Add("b"))
    assertThat(send.sent).containsExactly(Add("b"))
    assertThat(sentIds).containsExactly(sending.id, sending.id)
    assertThat(after.data.first()?.value).isEqualTo(listOf("a", "b"))
    assertThat(truth.data.value).isEqualTo(Fetched(listOf("a", "b"), START))
  }

  @Test
  fun `owed rows commit in the order they sent after a reopen`() = runTest {
    val flags = FlagServer()
    val first = owner()
    val (older, newer) = sendOlderLast(flags, first)
    assertOwedInSendOrder(older, newer)
    bounded("the owner to stop") { stop(first) }

    reopenAndCommit(flags, older, newer)
  }

  @Test
  fun `a read of owed rows that throws after a send keeps the send order`() = runTest {
    val flags = FlagServer()
    val failed = CompletableDeferred<Unit>()
    flags.acceptedOn = {
      nextOwedKeyRead.set {
        failed.complete(Unit)
        throw IllegalStateException("Disk error")
      }
    }
    val first = realTimeOwner()
    try {
      val (older, newer) = sendOlderLast(flags, first)
      bounded("the read that throws") { failed.await() }
      assertOwedInSendOrder(older, newer)
      bounded("the owner to stop") { stop(first) }

      reopenAndCommit(flags, older, newer)
    } finally {
      first.cancel()
    }
  }

  @Test
  fun `a read of owed rows that never returns after a send lets the owner stop`() = runTest {
    val flags = FlagServer()
    val gate = CompletableDeferred<Unit>()
    val held = CompletableDeferred<Unit>()
    val first = realTimeOwner()
    try {
      val (older, newer) = sendOlderLast(flags, first)
      assertOwedInSendOrder(older, newer)
      // Armed once the older row is saved, so the read it holds is a later settle of the key,
      // whenever the commit retry comes due
      nextOwedKeyRead.set {
        held.complete(Unit)
        gate.await()
      }
      bounded("the held read") { held.await() }
      bounded("the owner to stop") { stop(first) }
      assertThat(gate.isCompleted).isFalse()

      reopenAndCommit(flags, older, newer)
    } finally {
      gate.complete(Unit)
      first.cancel()
    }
  }

  @Test
  fun `a send that returns after the owner stopped is stored and not sent again`() = runTest {
    val rows = stores().open(Tags.name, Add.serializer())
    val id = rows.insertNew("", Add("b"))
    online.value = true
    val gate = CompletableDeferred<Unit>()
    // The owner's stop cannot cut the wait off, so the send returns after the stop
    val gated = Tags.sender { _, sent ->
      sentIds += sent
      attempts.update { it + 1 }
      withContext(NonCancellable) { gate.await() }
    }
    val first = realTimeOwner()
    try {
      process().create(first, setOf(gated))
      bounded("the send") { attempts.first { it == 1 } }

      first.cancel()
      gate.complete(Unit)
      bounded("the owner to stop") { first.coroutineContext.job.join() }

      // No handle is attached, so the row is stored owed
      val saved = checkNotNull(rows.entry(id).first())
      assertThat(saved.status).isEqualTo(OutboxStatus.Sent)
      assertThat(saved.committed).isFalse()

      temporaryDatabase.reopen()

      val reopened = stores().open(Tags.name, Add.serializer())
      val after = process().create(owner(), setOf(gated)).tags()
      bounded("the row to commit") { reopened.entries().first { it.isEmpty() } }

      assertThat(sentIds).containsExactly(id)
      assertThat(checkNotNull(reopened.entry(id).first()).committed).isTrue()
      assertThat(truth.data.value?.value).isEqualTo(listOf("a", "b"))
      assertThat(after.data.first()?.value).isEqualTo(listOf("a", "b"))
    } finally {
      gate.complete(Unit)
      first.cancel()
    }
  }

  /**
   * A flag server that rejects the first change that turns the flag on, so a retry sends it after a
   * newer change. It counts each row's accepted sends across processes.
   */
  private class FlagServer {
    val server = MutableStateFlow<Boolean?>(null)
    // Accepted sends by row id. Sends run on the owner's dispatcher.
    val sends = ConcurrentHashMap<EntryId, Int>()
    private val rejected = AtomicBoolean(false)

    /** Runs as the server accepts a change that turns the flag on. */
    @Volatile var acceptedOn: () -> Unit = {}

    val sender = Flags.sender { flag, id ->
      if (flag.on && rejected.compareAndSet(false, true)) {
        throw PermanentFailure("Rejected")
      }
      server.value = flag.on
      sends.merge(id, 1, Int::plus)
      if (flag.on) {
        acceptedOn()
      }
    }
    val truth = QueryStore.inMemory(Fetched(false, START - 1.hours))
  }

  /**
   * Runs a process on [owner] whose every commit fails, so each sent row stays owed. The older
   * change is rejected, the newer one sends, and then a retry sends the older one. Returns the ids
   * of the older and the newer row.
   */
  private suspend fun TestScope.sendOlderLast(
    flags: FlagServer,
    owner: CoroutineScope,
  ): Pair<EntryId, EntryId> {
    online.value = true
    val diskFull =
      QueryStore.of(
        flags.truth.data,
        write = { _, _ -> error("Disk full") },
        clear = flags.truth::clearFetched,
      )
    // The clock stands still while failed commits wait out their pauses, so both rows send at START
    val frozen =
      object : Clock {
        override fun now(): Instant = START
      }
    val store =
      process(frozen, OwedReadHooks(stores()))
        .create(owner, setOf(flags.sender))
        .optimisticStore(Flags, base = diskFull, apply = { _, flag -> flag.on })
    val older = store.mutate(Flag(true))
    bounded("the rejection") { older.awaitFailed<PermanentFailure>() }
    val newer = store.mutate(Flag(false))
    bounded("the newer send") { newer.awaitSucceeded() }
    val retried = store.retry(older.id)
    bounded("the older send") { retried.awaitSucceeded() }
    return older.id to newer.id
  }

  /** Checks that both rows are owed and that [older] records a send 1 ms after [newer]. */
  private suspend fun assertOwedInSendOrder(older: EntryId, newer: EntryId) {
    val owed = stores().open(Flags.name, Flag.serializer()).owed().associateBy { it.id }
    val olderRow = owed.getValue(older)
    val newerRow = owed.getValue(newer)
    for (row in listOf(olderRow, newerRow)) {
      assertThat(row.status).isEqualTo(OutboxStatus.Sent)
      assertThat(row.committed).isFalse()
    }
    assertThat(newerRow.sentAt).isEqualTo(START)
    // Recorded after the owed newer row, so it commits last
    assertThat(olderRow.sentAt).isEqualTo(START + 1.milliseconds)
  }

  /**
   * Reopens the database and commits both owed rows in a new process whose reads and base writes
   * work. Checks that the send times outlived the reopen, that [newer] commits before [older], and
   * that nothing sent again.
   */
  private suspend fun TestScope.reopenAndCommit(flags: FlagServer, older: EntryId, newer: EntryId) {
    nextOwedKeyRead.set(null)
    temporaryDatabase.reopen()

    val reopened = stores().open(Flags.name, Flag.serializer())
    assertOwedInSendOrder(older, newer)
    // The values the base is asked to write, in order
    val written = CopyOnWriteArrayList<Boolean>()
    val base =
      QueryStore.of(
        flags.truth.data,
        write = { fetched, keepsStored ->
          written += fetched.value
          flags.truth.writeFetched(fetched, keepsStored)
        },
        clear = flags.truth::clearFetched,
      )
    val after =
      process()
        .create(owner(), setOf(flags.sender))
        .optimisticStore(Flags, base = base, apply = { _, flag -> flag.on })
    bounded("the owed rows to commit") { reopened.entries().first { it.isEmpty() } }

    assertThat(written).containsExactly(false, true)
    assertThat(flags.server.value).isEqualTo(true)
    assertThat(flags.truth.data.value?.value).isEqualTo(true)
    assertThat(after.data.first()?.value).isEqualTo(true)
    assertThat(flags.sends).isEqualTo(mapOf(older to 1, newer to 1))
  }

  /**
   * A scope on a real dispatcher, so pauses take wall time. The test scheduler skips ahead to the
   * next pause whenever work waits on the database, so the newer row's commit retry would come due
   * while the older row sends. On wall time it is still due then, so a hook armed as the older row
   * sends runs in the first read of the key's owed rows after that send.
   */
  private fun realTimeOwner(): CoroutineScope =
    CoroutineScope(Dispatchers.Default.limitedParallelism(1) + Job())

  /** Waits for [block] for at most ten seconds of wall time, and fails the test after that. */
  private suspend fun <T : Any> bounded(what: String, block: suspend () -> T): T =
    withContext(Dispatchers.Default) { withTimeoutOrNull(10.seconds) { block() } }
      ?: fail("Timed out waiting for $what")

  // Runs once, before the next read of one key's owed rows, and is then cleared
  private val nextOwedKeyRead = AtomicReference<(suspend () -> Unit)?>(null)

  /** [stores] whose reads of one key's owed rows run [nextOwedKeyRead] first. */
  private inner class OwedReadHooks(private val stores: OutboxStores) : OutboxStores by stores {
    override fun <E> open(name: String, serializer: KSerializer<E>): OutboxStore<E> {
      val rows = stores.open(name, serializer)
      return object : OutboxStore<E> by rows {
        override suspend fun owed(key: String): List<OutboxEntry<E>> {
          nextOwedKeyRead.getAndSet(null)?.invoke()
          return rows.owed(key)
        }
      }
    }
  }

  /** Stores over whatever database [temporaryDatabase] has open when each call runs. */
  private fun stores(): SqlDelightOutboxStores =
    SqlDelightOutboxStores({ temporaryDatabase.db().outboxRowQueries }, Dispatchers.IO)

  /** Cancels [owner] and waits for its work to end, so no call reaches a driver that closes. */
  private suspend fun stop(owner: CoroutineScope) {
    owner.cancel()
    owner.coroutineContext.job.join()
  }

  // How many changes the replying sender answered, across both processes
  private val replies = MutableStateFlow(0)

  /** Answers each change with the tag list the server holds after it. */
  private fun replyingSender() = ReplyingTags.exchangingSender { add, _ ->
    replies.update { it + 1 }
    listOf("a", add.tag.uppercase())
  }

  /** Answers each change with null, as a server that clears the note. */
  private fun clearingSender() = NullableNote.exchangingSender { _, _ ->
    replies.update { it + 1 }
    null
  }
}

private val START = Instant.fromEpochSeconds(1_700_000_000)

private val Tags = OutboxKey<OptimisticQueryStoreRestartTest.Add>("tags")

private val Flags = OutboxKey<OptimisticQueryStoreRestartTest.Flag>("flags")

/** An outbox whose server answers each change with the tag list it holds. */
private val ReplyingTags =
  OutboxKey.replying<OptimisticQueryStoreRestartTest.Add, List<String>>("replyingTags")

/** An outbox whose server answers each change with a nullable note. */
private val NullableNote =
  OutboxKey.replying<OptimisticQueryStoreRestartTest.Add, String?>("nullableNote")
