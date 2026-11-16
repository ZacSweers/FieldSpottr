// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import dev.zacsweers.fieldspottr.data.core.DataScope
import dev.zacsweers.fieldspottr.data.core.EntryId
import dev.zacsweers.fieldspottr.data.core.Fetched
import dev.zacsweers.fieldspottr.data.core.InMemoryQueryStore
import dev.zacsweers.fieldspottr.data.core.OptimisticQueryStore
import dev.zacsweers.fieldspottr.data.core.OutboxKey
import dev.zacsweers.fieldspottr.data.core.OutboxStatus
import dev.zacsweers.fieldspottr.data.core.QueryStore
import dev.zacsweers.fieldspottr.data.core.exchangingSender
import dev.zacsweers.fieldspottr.data.core.replying
import dev.zacsweers.fieldspottr.data.core.sender
import dev.zacsweers.fieldspottr.data.core.testing.TestFailures
import dev.zacsweers.fieldspottr.data.core.testing.TestSend
import dev.zacsweers.fieldspottr.data.core.testing.awaitSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.insertNew
import dev.zacsweers.fieldspottr.data.core.testing.testClock
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
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

  /** A new factory, like a new process: its own name claims, the same rows and network state. */
  private fun TestScope.process(): DataScope.Factory =
    DataScope.Factory(
      onFailure = TestFailures(),
      onFetchFailure = TestFailures(),
      online = online,
      stores = stores(),
      clock = testClock(START),
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
  fun `a change left sending is sent again with the same id`() = runTest {
    online.value = true
    send.hold(Add("b"))
    val first = owner()
    val before = process().create(first, setOf(sender)).tags()
    before.mutate(Add("b"))
    // The send has started and is waiting on the held gate
    attempts.first { it == 1 }
    val sending =
      before.pending.first { it.singleOrNull()?.status == OutboxStatus.Sending }.single()

    // The owner dies with the send in flight, so the row stays Sending
    first.cancel()
    send.release(Add("b"))

    val after = process().create(owner(), setOf(sender)).tags()
    after.pending.first { it.isEmpty() }

    assertThat(send.attempts).containsExactly(Add("b"), Add("b"))
    assertThat(send.sent).containsExactly(Add("b"))
    // Both attempts carried the row's id, so the server can tell the retry from a new change
    assertThat(sentIds).containsExactly(sending.id, sending.id)
    assertThat(after.data.first()?.value).isEqualTo(listOf("a", "b"))
  }

  @Test
  fun `a stored server answer is cleared once its row commits`() = runTest {
    val stores = SqlDelightOutboxStores({ temporaryDatabase.db().outboxRowQueries }, Dispatchers.IO)
    val rows = stores.open(ReplyingTags.name, Add.serializer())
    val ids = listOf(rows.insertNew("", Add("b")), rows.insertNew("", Add("c")))
    online.value = true
    val data =
      DataScope.Factory(
          onFailure = TestFailures(),
          onFetchFailure = TestFailures(),
          online = online,
          stores = stores,
          clock = testClock(START),
        )
        .create(
          owner(),
          setOf(ReplyingTags.exchangingSender { add, _ -> listOf("a", add.tag.uppercase()) }),
        )
    // No handle is attached, so each answer waits on its row
    val sent = rows.entries().first { list -> list.all { it.status == OutboxStatus.Sent } }
    assertThat(sent.map { it.reply }).containsExactly("""["a","B"]""", """["a","C"]""")
    assertThat(sent.map { it.committed }).containsExactly(false, false)

    data.optimisticStore(
      ReplyingTags,
      base = truth,
      apply = { current, add -> current.orEmpty() + add.tag },
    )
    rows.entries().first { it.isEmpty() }

    assertThat(truth.data.value).isEqualTo(Fetched(listOf("a", "C"), START))
    for (id in ids) {
      val committed = checkNotNull(rows.entry(id).first())
      assertThat(committed.committed).isTrue()
      assertThat(committed.reply).isNull()
    }
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

    // The newest answer replaces the truth, and nothing was sent again
    assertThat(truth.data.value).isEqualTo(Fetched(listOf("a", "C"), START))
    assertThat(replies.value).isEqualTo(2)
    for (id in ids) {
      val committed = checkNotNull(reopened.entry(id).first())
      assertThat(committed.status).isEqualTo(OutboxStatus.Sent)
      assertThat(committed.committed).isTrue()
      assertThat(committed.reply).isNull()
    }
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
}

private val START = Instant.fromEpochSeconds(1_700_000_000)

private val Tags = OutboxKey<OptimisticQueryStoreRestartTest.Add>("tags")

/** An outbox whose server answers each change with the tag list it holds. */
private val ReplyingTags =
  OutboxKey.replying<OptimisticQueryStoreRestartTest.Add, List<String>>("replyingTags")
