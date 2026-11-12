// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import assertk.assertions.messageContains
import dev.zacsweers.fieldspottr.data.core.impl.landedCommits
import dev.zacsweers.fieldspottr.data.core.testing.LaggingOutboxStores
import dev.zacsweers.fieldspottr.data.core.testing.TestFailures
import dev.zacsweers.fieldspottr.data.core.testing.TestSend
import dev.zacsweers.fieldspottr.data.core.testing.awaitFailed
import dev.zacsweers.fieldspottr.data.core.testing.awaitSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.insertNew
import dev.zacsweers.fieldspottr.data.core.testing.testClock
import dev.zacsweers.fieldspottr.data.core.testing.testDataScope
import dev.zacsweers.fieldspottr.data.core.testing.testDataScopeFactory
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer

/**
 * The two halves of an outbox: a sender that drains rows from the data scope's creation, and a
 * handle that a repository builds later and that commits or reverts what the sender left it.
 */
class OutboxSenderTest {
  @Serializable data class Add(val tag: String)

  @Test
  fun `a handle whose key holds another payload type throws`() = runTest {
    val data = testDataScope(senders = setOf(Tags.sender { _, _ -> }))

    val error = assertFailsWith<IllegalStateException> { data.outbox(OutboxKey<String>(Tags.name)) }
    assertThat(error).messageContains("cannot attach")
  }

  @Test
  fun `the reply builders throw for a plain key`() {
    val plain = OutboxKey<Add>("plain")

    assertFailsWith<IllegalArgumentException> {
      plain.exchangingSender<Add, List<String>> { _, _ -> emptyList() }
    }
    assertFailsWith<IllegalArgumentException> {
      plain.deliveringSender<Add, List<String>> { _, _ -> Delivery.Acknowledged }
    }
  }

  @Test
  fun `a sender with no handle sends the stored rows and the backlog empties`() = runTest {
    val factory = testDataScopeFactory(clock = testClock(START))
    val rows = factory.outboxStore<Add>(Tags.name)
    rows.insertNew("", Add("b"))
    val send = TestSend<Add>()

    factory.create(backgroundScope, setOf(Tags.sender(send = send)))

    assertThat(factory.backlog.first { it == OutboxBacklog.Empty }).isEqualTo(OutboxBacklog.Empty)
    assertThat(send.sent).containsExactly(Add("b"))
    // The server has it, and the row waits for a handle to commit it
    val row = rows.entries().first().single()
    assertThat(row.status).isEqualTo(OutboxStatus.Sent)
    assertThat(row.committed).isFalse()
    assertThat(row.sentAt).isEqualTo(START)
    factory.assertNoFailures()
  }

  @Test
  fun `rows sent before the handle exists commit in order once it attaches`() = runTest {
    val factory = testDataScopeFactory(clock = testClock(START))
    val rows = factory.outboxStore<Add>(Tags.name)
    for (tag in listOf("b", "c", "d")) {
      rows.insertNew("", Add(tag))
    }
    val data =
      factory.create(
        backgroundScope,
        setOf(
          Tags.deliveringSender { add, _ ->
            // The server answers only c, with what it holds then
            if (add.tag == "c") {
              Delivery.Replaced(listOf("a", "b", "C"))
            } else {
              Delivery.Acknowledged
            }
          }
        ),
      )
    val sent = rows.entries().first { list -> list.all { it.status == OutboxStatus.Sent } }
    assertThat(sent.map { it.reply }).containsExactly(null, """["a","b","C"]""", null)
    assertThat(sent.map { it.sentAt }).containsExactly(START, START, START)

    val truth = QueryStore.inMemory(Fetched(listOf("a"), START - 1.hours))
    val store = data.optimisticStore(Tags, base = truth, apply = ::addOnce)

    store.data.test {
      // The view shows the sent changes from the first value on, through the commits
      while (true) {
        val value = awaitItem()?.value
        assertThat(value.orEmpty().map { it.lowercase() }).containsExactly("a", "b", "c", "d")
        if (rows.entries().first().isEmpty()) {
          break
        }
      }
      cancelAndIgnoreRemainingEvents()
    }
    rows.entries().first { it.isEmpty() }
    // b applied, then c's answer replaced the truth, then d applied on top of it
    assertThat(truth.data.first()?.value).isEqualTo(listOf("a", "b", "C", "d"))
    assertThat(rows.entry(sent.last().id).first()?.committed).isEqualTo(true)
    factory.assertNoFailures()
  }

  @Test
  fun `a commit repeated after a crash before the row was marked leaves the truth right`() =
    runTest {
      val stores = LaggingOutboxStores()
      val failures = TestFailures()
      val clock = testClock(START)
      val factory = DataScope.Factory(onFailure = failures, stores = stores, clock = clock)
      val rows = stores.delegate.open(Tags.name, Add.serializer())
      rows.insertNew("", Add("b"))
      rows.insertNew("", Add("c"))
      val truth = QueryStore.inMemory(Fetched(listOf("a"), START - 1.hours))
      val sender = Tags.sender { _, _ -> }

      // The first process sends both rows before its repository exists, then builds it and dies
      // after the commit of b wrote the truth
      val first = ownerScope()
      val firstData = factory.create(first, setOf(sender))
      rows.entries().first { list -> list.all { it.status == OutboxStatus.Sent } }
      stores.failCommittedWrite = true
      firstData.optimisticStore(Tags, base = truth, apply = ::addOnce)
      truth.data.first { it?.value == listOf("a", "b") }
      first.coroutineContext.job.cancelAndJoin()
      assertThat(rows.owed().map { it.payload }).containsExactly(Add("b"), Add("c"))

      // The next process commits both again, and b, already in the truth, stays once
      stores.failCommittedWrite = false
      factory
        .create(backgroundScope, setOf(sender))
        .optimisticStore(Tags, base = truth, apply = ::addOnce)
      rows.entries().first { it.isEmpty() }

      assertThat(truth.data.first()?.value).isEqualTo(listOf("a", "b", "c"))
      assertThat(failures.all.map { it.message }.distinct())
        .containsExactly("The process died before the row was marked committed")
    }

  @Test
  fun `a server answer committed again after a newer fetch keeps the fetch`() = runTest {
    val stores = LaggingOutboxStores()
    val failures = TestFailures()
    val factory = DataScope.Factory(onFailure = failures, stores = stores, clock = testClock(START))
    val rows = stores.delegate.open(Tags.name, Add.serializer())
    rows.insertNew("", Add("c"))
    val truth = QueryStore.inMemory(Fetched(listOf("a"), START - 1.hours))
    val sender = Tags.exchangingSender { _, _ -> listOf("a", "C") }

    val first = ownerScope()
    val firstData = factory.create(first, setOf(sender))
    rows.entries().first { list -> list.all { it.status == OutboxStatus.Sent } }
    stores.failCommittedWrite = true
    firstData.optimisticStore(Tags, base = truth, apply = ::addOnce)
    truth.data.first { it == Fetched(listOf("a", "C"), START) }
    first.coroutineContext.job.cancelAndJoin()
    // A fetch from after the send lands before the next process commits again
    truth.write(listOf("a", "C", "z"), START + 1.minutes)

    stores.failCommittedWrite = false
    factory
      .create(backgroundScope, setOf(sender))
      .optimisticStore(Tags, base = truth, apply = ::addOnce)
    rows.entries().first { it.isEmpty() }

    assertThat(truth.data.first()).isEqualTo(Fetched(listOf("a", "C", "z"), START + 1.minutes))
  }

  @Test
  fun `a row that failed with no handle reverts once when a handle attaches`() = runTest {
    val factory = testDataScopeFactory()
    val rows = factory.outboxStore<Add>(Tags.name)
    val id = rows.insertNew("", Add("b"))
    val send = TestSend<Add>()
    send.failPermanently()
    val reverted = mutableListOf<Add>()

    val first = ownerScope()
    val data = factory.create(first, setOf(Tags.sender(send = send)))
    val failed = rows.entry(id).first { it?.status is OutboxStatus.Failed }
    assertThat(failed?.reverted).isEqualTo(false)
    data.outbox(Tags, revert = { add, _ -> reverted += add })
    rows.entry(id).first { it?.reverted == true }
    assertThat(reverted).containsExactly(Add("b"))
    first.coroutineContext.job.cancelAndJoin()

    // A later handle finds the row reverted, so it reverts nothing
    factory
      .create(backgroundScope, setOf(Tags.sender(send = send)))
      .outbox(Tags, revert = { add, _ -> reverted += add })
    runCurrent()
    assertThat(reverted).containsExactly(Add("b"))
    assertThat(factory.actionFailures.take().single()).isInstanceOf<PermanentFailure>()
    factory.assertNoFailures()
  }

  @Test
  fun `a handle that attaches during a send commits an owed row first and the send once`() =
    runTest {
      val factory = testDataScopeFactory(clock = testClock(START))
      val rows = factory.outboxStore<Add>(Tags.name)
      sentRow(rows, Add("x"))
      rows.insertNew("", Add("y"))
      val send = TestSend<Add>()
      send.hold(Add("y"))
      val data = factory.create(backgroundScope, setOf(Tags.sender(send = send)))
      runCurrent()
      assertThat(send.attempts).containsExactly(Add("y"))

      val truth = QueryStore.inMemory(Fetched(listOf("a"), START - 1.hours))
      val writes = mutableListOf<List<String>>()
      val counted =
        QueryStore.of(
          truth.data,
          write = { fetched, keepsStored ->
            writes += fetched.value
            truth.writeFetched(fetched, keepsStored)
          },
          clear = truth::clearFetched,
        )
      data.optimisticStore(Tags, base = counted, apply = ::addOnce)
      runCurrent()
      send.release(Add("y"))
      rows.entries().first { it.isEmpty() }

      // One write per commit, the owed row first
      assertThat(writes).containsExactly(listOf("a", "x"), listOf("a", "x", "y"))
      factory.assertNoFailures()
    }

  @Test
  fun `clear drops a sent change that waits for its commit`() = runTest {
    val factory = testDataScopeFactory(clock = testClock(START))
    val rows = factory.outboxStore<Add>(Tags.name)
    val id = sentRow(rows, Add("b"))
    val data = factory.create(backgroundScope, setOf(Tags.sender { _, _ -> }))
    val truth = QueryStore.inMemory(Fetched(listOf("a"), START - 1.hours))

    val store = data.optimisticStore(Tags, base = truth, apply = ::addOnce)
    store.clear()
    runCurrent()

    assertThat(truth.data.first()).isNull()
    assertThat(store.data.first()).isNull()
    assertThat(rows.entry(id).first()?.committed).isEqualTo(true)
    factory.assertNoFailures()
  }

  @Test
  fun `discard and retry leave a sent change that waits for its commit in view`() = runTest {
    val factory = testDataScopeFactory(clock = testClock(START))
    val rows = factory.outboxStore<Add>(Tags.name)
    sentRow(rows, Add("x"))
    val waiting = sentRow(rows, Add("y"))
    val data = factory.create(backgroundScope, setOf(Tags.sender { _, _ -> }))
    val truth = QueryStore.inMemory(Fetched(listOf("a"), START - 1.hours))
    // Holds the commit of x, so y stays sent and uncommitted
    val gate = CompletableDeferred<Unit>()
    val held =
      QueryStore.of(
        truth.data,
        write = { fetched, keepsStored ->
          gate.await()
          truth.writeFetched(fetched, keepsStored)
        },
        clear = truth::clearFetched,
      )
    val store = data.optimisticStore(Tags, base = held, apply = ::addOnce)

    store.data.test {
      assertThat(awaitItem()?.value).isEqualTo(listOf("a", "x", "y"))
      val discarded = store.discard(waiting)
      val retried = store.retry(waiting)
      discarded.awaitFailed<IllegalStateException>()
      retried.awaitSucceeded()
      gate.complete(Unit)
      rows.entries().first { it.isEmpty() }
      // Every value since kept y
      for (event in cancelAndConsumeRemainingEvents()) {
        if (event is app.cash.turbine.Event.Item) {
          assertThat(event.value?.value.orEmpty()).containsExactly("a", "x", "y")
        }
      }
    }
    assertThat(truth.data.first()?.value).isEqualTo(listOf("a", "x", "y"))
  }

  @Test
  fun `an answer that cannot be decoded leaves the truth stale and the change in view`() = runTest {
    val factory = testDataScopeFactory(clock = testClock(START))
    val rows = factory.outboxStore<Add>(Tags.name)
    val id = sentRow(rows, Add("b"), reply = "not json")
    val data = factory.create(backgroundScope, setOf(Tags.sender { _, _ -> }))
    val truth = QueryStore.inMemory(Fetched(listOf("a"), START - 1.hours))
    val own = TestFailures()

    val store = data.optimisticStore(Tags, base = truth, apply = ::addOnce, onFailure = own)
    rows.entry(id).first { it?.committed == true }
    runCurrent()

    assertThat(truth.data.first()?.invalidated).isEqualTo(true)
    assertThat(truth.data.first()?.value).isEqualTo(listOf("a"))
    // Until a fetch from after the send replaces the truth, the change still shows
    assertThat(store.data.first()?.value).isEqualTo(listOf("a", "b"))
    store.write(listOf("a", "b", "z"), START + 1.minutes)
    assertThat(store.data.first()?.value).isEqualTo(listOf("a", "b", "z"))
    assertThat(own.take().single()).messageContains("cannot decode the server's answer")
    factory.assertNoFailures()
  }

  @Test
  fun `a commit whose row read fails stays owed and lands later with the change in view`() =
    runTest {
      val stores = LaggingOutboxStores()
      val failures = TestFailures()
      val factory =
        DataScope.Factory(onFailure = failures, stores = stores, clock = testClock(START))
      val truth = QueryStore.inMemory(Fetched(listOf("a"), START - 1.hours))
      val data = factory.create(backgroundScope, setOf(Tags.sender { _, _ -> }))
      val own = TestFailures()
      val store = data.optimisticStore(Tags, base = truth, apply = ::addOnce, onFailure = own)
      val seen = viewOf(store)
      runCurrent()

      stores.failEntryReads = true
      val handle = store.mutate(Add("b"))
      handle.awaitSucceeded()
      runCurrent()
      // The commit failed, so the truth lacks the change and the row waits for its commit
      assertThat(truth.data.first()?.value).isEqualTo(listOf("a"))
      val owed = stores.delegate.open(Tags.name, Add.serializer()).owed()
      assertThat(owed.map { it.payload }).containsExactly(Add("b"))
      assertThat(own.all).isNotEmpty()

      stores.failEntryReads = false
      advanceTimeBy(2.minutes)
      runCurrent()

      assertThat(truth.data.first()).isEqualTo(Fetched(listOf("a", "b"), START - 1.hours))
      assertThat(stores.delegate.open(Tags.name, Add.serializer()).entries().first()).isEmpty()
      assertNoFlash(seen, "b")
    }

  @Test
  fun `a truth write that fails during a commit is retried and keeps the change in view`() =
    runTest {
      val data = testDataScope(clock = testClock(START), senders = setOf(Tags.sender { _, _ -> }))
      val truth = QueryStore.inMemory(Fetched(listOf("a"), START - 1.hours))
      var writes = 0
      val diskFull =
        QueryStore.of(
          truth.data,
          write = { fetched, keepsStored ->
            if (++writes == 1) {
              error("Disk full")
            }
            truth.writeFetched(fetched, keepsStored)
          },
          clear = truth::clearFetched,
        )
      val own = TestFailures()
      val store = data.optimisticStore(Tags, base = diskFull, apply = ::addOnce, onFailure = own)
      val seen = viewOf(store)
      runCurrent()

      store.mutate(Add("b")).awaitSucceeded()
      advanceTimeBy(2.minutes)
      runCurrent()

      // The first commit failed and was reported once, and the retry after a pause landed
      assertThat(own.take().map { it.message }).containsExactly("Disk full")
      assertThat(writes).isEqualTo(2)

      assertThat(truth.data.first()).isEqualTo(Fetched(listOf("a", "b"), START - 1.hours))
      assertThat(data.outboxStore<Add>(Tags.name).entries().first()).isEmpty()
      assertNoFlash(seen, "b")
      data.assertNoFailures()
    }

  @Test
  fun `a send that finishes before the handle is ready keeps the change in view`() = runTest {
    val gate = CompletableDeferred<Unit>()
    val inner = InMemoryOutboxStores().open(Tags.name, Add.serializer())
    // Owed reads wait, so the handle is not ready when the send finishes, and the commit of the
    // sent row waits too
    val slow =
      object : OutboxStore<Add> by inner {
        override suspend fun owed(): List<OutboxEntry<Add>> {
          gate.await()
          return inner.owed()
        }

        override suspend fun owed(key: String): List<OutboxEntry<Add>> {
          gate.await()
          return inner.owed(key)
        }
      }
    val send = TestSend<Add>()
    send.hold(Add("b"))
    val data =
      testDataScope(
        clock = testClock(START),
        senders = setOf(Tags.sender(store = slow, send = send)),
      )
    val truth = QueryStore.inMemory(Fetched(listOf("a"), START - 1.hours))
    val store = data.optimisticStore(Tags, base = truth, apply = ::addOnce)
    val seen = viewOf(store)

    store.mutate(Add("b"))
    runCurrent()
    send.release(Add("b"))
    advanceTimeBy(2.seconds)
    gate.complete(Unit)
    runCurrent()

    assertThat(truth.data.first()?.value).isEqualTo(listOf("a", "b"))
    assertNoFlash(seen, "b")
    data.assertNoFailures()
  }

  @Test
  fun `a handle key whose answer type differs from its sender's throws`() = runTest {
    val replyingSender = OutboxKey.replying<Add, Int>("counter").exchangingSender { _, _ -> 7 }
    val plainSender = OutboxKey<Add>("plain").sender { _, _ -> }
    val otherReply = OutboxKey.replying<Add, String>("text").exchangingSender { _, _ -> "x" }
    val data = testDataScope(senders = setOf(replyingSender, plainSender, otherReply))
    val truth = QueryStore.inMemory(Fetched(listOf("a"), START))

    val plainOnReplying =
      assertFailsWith<IllegalStateException> {
        data.optimisticStore(OutboxKey<Add>("counter"), base = truth, apply = ::addOnce)
      }
    assertThat(plainOnReplying).messageContains("Use the one OutboxKey val")
    assertFailsWith<IllegalStateException> {
      data.optimisticStore(
        OutboxKey.replying<Add, List<String>>("plain"),
        base = truth,
        apply = ::addOnce,
      )
    }
    assertFailsWith<IllegalStateException> {
      data.outbox(OutboxKey.replying<Add, Int>("text"))
    }
  }

  @Test
  fun `attach reads the owed rows once and each key reads only its own`() = runTest {
    val keys = 400
    val inner = InMemoryOutboxStores().open(Tags.name, Add.serializer())
    repeat(keys) { i ->
      val id = inner.insertNew("k$i", Add("t$i"))
      val row = checkNotNull(inner.entry(id).first())
      inner.update(row.copy(status = OutboxStatus.Sent, sentAt = START), OutboxStatus.Queued)
    }
    var wholeReads = 0
    var rowsRead = 0
    val counting =
      object : OutboxStore<Add> by inner {
        override suspend fun owed(): List<OutboxEntry<Add>> =
          inner.owed().also {
            wholeReads++
            rowsRead += it.size
          }

        override suspend fun owed(key: String): List<OutboxEntry<Add>> =
          inner.owed(key).also { rowsRead += it.size }
      }
    val data =
      testDataScope(
        clock = testClock(START),
        senders = setOf(Tags.sender(store = counting) { _, _ -> }),
      )

    data.outbox(Tags)
    runCurrent()

    assertThat(inner.owed()).isEmpty()
    assertThat(wholeReads).isEqualTo(1)
    // The attach's read, then one row per key
    assertThat(rowsRead).isEqualTo(2 * keys)
    data.assertNoFailures()
  }

  @Test
  fun `a commit that keeps failing retries once per pause for one change`() = runTest {
    assertThat(commitAttemptsOver30Minutes(changes = 1)).isLessThanOrEqualTo(RETRY_BOUND)
  }

  @Test
  fun `a commit that keeps failing retries once per pause however many rows wait`() = runTest {
    assertThat(commitAttemptsOver30Minutes(changes = 5)).isLessThanOrEqualTo(RETRY_BOUND)
  }

  /**
   * Sends [changes] increments 10 ms apart to a counter whose truth write always fails, waits 30
   * virtual minutes, and returns how many commits wrote, checking each failure was reported once.
   */
  private suspend fun TestScope.commitAttemptsOver30Minutes(changes: Int): Int {
    var server = 10
    val sender = Counter.exchangingSender { delta, _ -> server.also { server += delta } + delta }
    val data = testDataScope(clock = testClock(START), senders = setOf(sender))
    val truth = QueryStore.inMemory(Fetched(10, START - 1.hours))
    var attempts = 0
    val diskFull =
      QueryStore.of(
        truth.data,
        write = { _, _ ->
          attempts++
          error("Disk full")
        },
        clear = truth::clearFetched,
      )
    val own = TestFailures()
    val store =
      data.optimisticStore(
        Counter,
        base = diskFull,
        apply = { current, delta -> (current ?: 0) + delta },
        onFailure = own,
      )

    repeat(changes) {
      store.mutate(1)
      advanceTimeBy(10.milliseconds)
    }
    advanceTimeBy(30.minutes)

    assertThat(own.take().size).isEqualTo(attempts)
    return attempts
  }

  @Test
  fun `an unknown answer shows its change once through fetches until one from after the send`() =
    runTest {
      val factory = testDataScopeFactory(clock = testClock(START))
      val rows = factory.outboxStore<Int>(Counter.name)
      val id = rows.insertNew("", 1)
      val row = checkNotNull(rows.entry(id).first())
      rows.update(
        row.copy(status = OutboxStatus.Sent, sentAt = START, reply = "not json"),
        OutboxStatus.Queued,
      )
      val data = factory.create(backgroundScope, setOf(Counter.sender { _, _ -> }))
      val truth = QueryStore.inMemory(Fetched(10, START - 1.hours))
      val store =
        data.optimisticStore(
          Counter,
          base = truth,
          apply = { current, delta -> (current ?: 0) + delta },
          onFailure = {},
        )
      rows.entry(id).first { it?.committed == true }
      runCurrent()
      assertThat(store.data.first()?.value).isEqualTo(11)

      // Two fetches that read the server before the send
      store.writeFetched(Fetched(10, START - 30.minutes)) { _, _ -> false }
      assertThat(store.data.first()?.value).isEqualTo(11)
      store.writeFetched(Fetched(10, START - 20.minutes)) { _, _ -> false }
      assertThat(store.data.first()?.value).isEqualTo(11)
      assertThat(truth.data.first()?.value).isEqualTo(11)

      // One from after the send holds the change itself
      store.writeFetched(Fetched(11, START + 1.minutes)) { _, _ -> false }
      assertThat(store.data.first()?.value).isEqualTo(11)
      factory.assertNoFailures()
    }

  @Test
  fun `a commit whose truth write landed is not applied again when its row write fails`() =
    runTest {
      val stores = LaggingOutboxStores()
      val factory = DataScope.Factory(onFailure = {}, stores = stores, clock = testClock(START))
      val rows = stores.delegate.open(Counter.name, Int.serializer())
      val id = rows.insertNew("", 1)
      val row = checkNotNull(rows.entry(id).first())
      rows.update(row.copy(status = OutboxStatus.Sent, sentAt = START), OutboxStatus.Queued)
      val data = factory.create(backgroundScope, setOf(Counter.sender { _, _ -> }))
      val truth = QueryStore.inMemory(Fetched(10, START - 1.hours))

      stores.failCommittedWrite = true
      val store =
        data.optimisticStore(
          Counter,
          base = truth,
          apply = { current, delta -> (current ?: 0) + delta },
          onFailure = {},
        )
      val seen = mutableListOf<Int?>()
      backgroundScope.launch { store.data.collect { seen += it?.value } }
      advanceTimeBy(3.seconds)
      stores.failCommittedWrite = false
      advanceTimeBy(2.minutes)
      runCurrent()

      assertThat(truth.data.first()?.value).isEqualTo(11)
      assertThat(rows.entries().first()).isEmpty()
      assertThat(seen.filterNotNull().max()).isEqualTo(11)
    }

  @Test
  fun `a row write that keeps failing after a landed commit retries once per pause`() = runTest {
    val stores = LaggingOutboxStores()
    val failures = TestFailures()
    val factory = DataScope.Factory(onFailure = failures, stores = stores, clock = testClock(START))
    val rows = stores.delegate.open(Counter.name, Int.serializer())
    val id = rows.insertNew("", 1)
    val row = checkNotNull(rows.entry(id).first())
    rows.update(row.copy(status = OutboxStatus.Sent, sentAt = START), OutboxStatus.Queued)
    val data = factory.create(backgroundScope, setOf(Counter.sender { _, _ -> }))
    val truth = QueryStore.inMemory(Fetched(10, START - 1.hours))

    stores.failCommittedWrite = true
    data.optimisticStore(
      Counter,
      base = truth,
      apply = { current, delta -> (current ?: 0) + delta },
      onFailure = {},
    )
    advanceTimeBy(30.minutes)

    assertThat(truth.data.first()?.value).isEqualTo(11)
    assertThat(failures.take().size).isLessThanOrEqualTo(RETRY_BOUND)
  }

  @Test
  fun `a live commit that loses its row write to a clear leaves nothing landed`() = runTest {
    val (rows, gate) = waitingRows()
    val data =
      testDataScope(
        clock = testClock(START),
        senders = setOf(Tally.sender(store = rows) { _, _ -> }),
      )
    val stores = tallies(data)

    stores.mutate("x", 1)
    runCurrent()
    // The commit landed, and its row write waits
    assertThat(stores.landedCommits).isEqualTo(1)
    stores("x").clear()
    gate.complete(Unit)
    runCurrent()

    assertThat(stores.landedCommits).isEqualTo(0)
    data.assertNoFailures()
  }

  @Test
  fun `a deferred commit that loses its row write to a clear leaves nothing landed`() = runTest {
    val (rows, gate) = waitingRows()
    val id = rows.insertNew("\"y\"", KeyedMutation("y", 1))
    val row = checkNotNull(rows.entry(id).first())
    rows.update(row.copy(status = OutboxStatus.Sent, sentAt = START), OutboxStatus.Queued)
    val data =
      testDataScope(
        clock = testClock(START),
        senders = setOf(Tally.sender(store = rows) { _, _ -> }),
      )

    val stores = tallies(data)
    runCurrent()
    assertThat(stores.landedCommits).isEqualTo(1)
    stores("y").clear()
    gate.complete(Unit)
    runCurrent()

    assertThat(stores.landedCommits).isEqualTo(0)
    data.assertNoFailures()
  }

  /**
   * Tally rows in memory whose first write that marks a row committed waits for the returned gate,
   * so a clear can take the row first.
   */
  private fun waitingRows():
    Pair<OutboxStore<KeyedMutation<String, Int>>, CompletableDeferred<Unit>> {
    val gate = CompletableDeferred<Unit>()
    var held = false
    val inner = InMemoryOutboxStores().open(Tally.name, Tally.serializer)
    val rows =
      object : OutboxStore<KeyedMutation<String, Int>> by inner {
        override suspend fun update(
          entry: OutboxEntry<KeyedMutation<String, Int>>,
          expected: OutboxStatus,
        ): Boolean {
          if (entry.status == OutboxStatus.Sent && entry.committed && !held) {
            held = true
            gate.await()
          }
          return inner.update(entry, expected)
        }
      }
    return rows to gate
  }

  /**
   * A tally per key over [data], each adding its changes to 10. Adding is not idempotent, which the
   * store reports, so its own handler drops that report.
   */
  private fun tallies(data: DataScope): OptimisticQueryStores<String, Int, Int> =
    data.optimisticStores(
      Tally,
      base = { _: String -> QueryStore.inMemory(Fetched(10, START - 1.hours)) },
      apply = { current, delta -> (current ?: 0) + delta },
      onFailure = {},
    )

  /** Every value [store]'s data emits from now on, collected in the test's background. */
  private fun TestScope.viewOf(
    store: OptimisticQueryStore<List<String>, Add>
  ): List<List<String>?> {
    val seen = mutableListOf<List<String>?>()
    backgroundScope.launch { store.data.collect { seen += it?.value } }
    return seen
  }

  /** Fails if a value in [seen] lacks [tag] after one held it. */
  private fun assertNoFlash(seen: List<List<String>?>, tag: String) {
    val first = seen.indexOfFirst { it.orEmpty().contains(tag) }
    assertThat(first).isNotEqualTo(-1)
    val after = seen.drop(first)
    assertThat(after.filterNot { it.orEmpty().contains(tag) }, "values without $tag in $seen")
      .isEmpty()
  }

  @Test
  fun `a handle's change sends at once with one status write after the claim`() = runTest {
    val written = mutableListOf<OutboxEntry<Add>>()
    val inner = InMemoryOutboxStores().open(Tags.name, Add.serializer())
    val recording =
      object : OutboxStore<Add> by inner {
        override suspend fun update(entry: OutboxEntry<Add>, expected: OutboxStatus): Boolean {
          written += entry
          return inner.update(entry, expected)
        }
      }
    val send = TestSend<Add>()
    val data =
      testDataScope(
        eager = true,
        clock = testClock(START),
        senders = setOf(Tags.sender(store = recording, send = send)),
      )
    val store =
      data.optimisticStore(Tags, base = QueryStore.inMemory(Fetched(listOf("a"), START)), ::addOnce)

    store.mutate(Add("b")).awaitSucceeded()

    assertThat(send.sent).containsExactly(Add("b"))
    assertThat(written.map { it.status to it.committed })
      .containsExactly(OutboxStatus.Sending to false, OutboxStatus.Sent to true)
    assertThat(written.last().sentAt).isNotNull()
    assertThat(inner.entries().first()).isEmpty()
    data.assertNoFailures()
  }

  /** A scope under the test that a test cancels to end a data scope, like a process that dies. */
  private fun TestScope.ownerScope(): CoroutineScope =
    CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext.job))

  /** Stores [add] as a row that sent at [START] and waits for its commit, and returns its id. */
  private suspend fun sentRow(
    rows: OutboxStore<Add>,
    add: Add,
    reply: String? = null,
  ): EntryId {
    val id = rows.insertNew("", add)
    val row = checkNotNull(rows.entry(id).first())
    val sent = row.copy(status = OutboxStatus.Sent, sentAt = START, reply = reply)
    assertThat(rows.update(sent, expected = OutboxStatus.Queued)).isTrue()
    return id
  }

  private companion object {
    val START: Instant = Instant.parse("2026-06-10T12:00:00Z")
    val Tags = OutboxKey.replying<Add, List<String>>("tags")
    val Counter = OutboxKey.replying<Int, Int>("counter")
    val Tally = OutboxKey<KeyedMutation<String, Int>>("tally")

    /**
     * The most commits 30 minutes of failing retries may make for one key: one per pause as it
     * doubles from 1 second to 1 minute, then one a minute, with room for the first attempt.
     */
    const val RETRY_BOUND = 40

    /** Adds the tag once, so a change applied again changes nothing. */
    fun addOnce(current: List<String>?, add: Add): List<String> =
      if (add.tag in current.orEmpty()) {
        current.orEmpty()
      } else {
        current.orEmpty() + add.tag
      }
  }
}
