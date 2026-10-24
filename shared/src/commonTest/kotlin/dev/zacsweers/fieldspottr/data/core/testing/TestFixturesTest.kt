// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core.testing

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isSameInstanceAs
import dev.zacsweers.fieldspottr.data.core.Fetched
import dev.zacsweers.fieldspottr.data.core.Freshness
import dev.zacsweers.fieldspottr.data.core.Operation
import dev.zacsweers.fieldspottr.data.core.OperationState
import dev.zacsweers.fieldspottr.data.core.QueryStore
import dev.zacsweers.fieldspottr.data.core.RetryPolicy
import dev.zacsweers.fieldspottr.data.core.outbox
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** The test fixtures behave as their docs say, so tests built on them can trust them. */
class TestFixturesTest {

  @Test
  fun `the clock follows virtual time and shifts on its own`() = runTest {
    val start = Instant.parse("2026-06-10T12:00:00Z")
    val clock = testClock(start)
    assertThat(clock.now()).isEqualTo(start)

    advanceTimeBy(5.minutes)
    assertThat(clock.now()).isEqualTo(start + 5.minutes)

    clock.shift(-1.hours)
    assertThat(clock.now()).isEqualTo(start + 5.minutes - 1.hours)
  }

  @Test
  fun `an eager data scope settles a fetch before the request returns`() = runTest {
    var fetches = 0
    val data = testDataScope(eager = true)
    val query =
      data.query(QueryStore.inMemory<Int>(), fetch = { ++fetches }, Freshness.staleAfter(1.hours))

    query.requestFetch(force = true).assertSucceeded()
    assertThat(fetches).isEqualTo(1)
    data.assertNoFailures()
  }

  @Test
  fun `by default a data scope's work waits for runCurrent`() = runTest {
    val actions = testDataScope().newActions()
    val handle = actions.run {}

    handle.assertPending()
    runCurrent()
    handle.assertSucceeded()
  }

  @Test
  fun `freshness follows virtual time`() = runTest {
    var fetches = 0
    val query =
      testDataScope(eager = true)
        .query(QueryStore.inMemory<Int>(), fetch = { ++fetches }, Freshness.staleAfter(1.hours))
    query.requestFetch(force = false)

    advanceTimeBy(59.minutes)
    query.requestFetch(force = false)
    assertThat(fetches).isEqualTo(1)

    advanceTimeBy(1.minutes)
    query.requestFetch(force = false)
    assertThat(fetches).isEqualTo(2)
  }

  @Test
  fun `fetch and command failures land in separate lists`() = runTest {
    val data = testDataScope(eager = true)
    val query =
      data.query(
        QueryStore.inMemory<Int>(),
        fetch = { error("fetch failed") },
        Freshness.staleAfter(1.hours),
      )

    query.requestFetch(force = true).assertFailed<IllegalStateException>()
    data.newActions().run { throw IllegalArgumentException("command failed") }

    assertThat(data.fetchFailures.assertSingle<IllegalStateException>().message)
      .isEqualTo("fetch failed")
    assertThat(data.actionFailures.assertSingle<IllegalArgumentException>().message)
      .isEqualTo("command failed")
  }

  @Test
  fun `a runner with its own handler skips the shared list`() = runTest {
    val data = testDataScope(eager = true)
    val own = TestFailures()

    data.newActions(own).run { error("boom") }

    data.actionFailures.assertNone()
    own.assertSingle<IllegalStateException>()
  }

  @Test
  fun `take returns the failures and forgets them`() = runTest {
    val failures = TestFailures()
    val first = IllegalStateException("first")
    failures(first)

    assertThat(failures.take()).containsExactly(first)
    assertThat(failures.all).isEmpty()
    failures.assertNone()
  }

  @Test
  fun `assertNone fails with the first failure as its cause`() = runTest {
    val failures = TestFailures()
    val first = IllegalStateException("first")
    failures(first)
    failures(IllegalStateException("second"))

    val error = assertFailsWith<AssertionError> { failures.assertNone() }
    assertThat(error.cause).isNotNull().isSameInstanceAs(first)
  }

  @Test
  fun `outbox rows are readable by name while offline`() = runTest {
    val sent = mutableListOf<String>()
    val data = testDataScope(eager = true, online = false)
    val outbox = data.outbox<String>("notes", send = { sent += it })

    val handle = outbox.enqueue("a")
    handle.assertPending()
    assertThat(data.outboxStore<String>("notes").entries().first().map { it.payload })
      .containsExactly("a")

    data.online.value = true
    assertThat(sent).containsExactly("a")
    handle.assertSucceeded()
  }

  @Test
  fun `advance runs a backoff timer that ends exactly then`() = runTest {
    var attempts = 0
    val outbox =
      testDataScope(eager = true)
        .outbox<String>(
          "notes",
          send = {
            if (++attempts == 1) {
              error("transient")
            }
          },
          retry = RetryPolicy(maxAttempts = 3) { 10.seconds },
        )

    val handle = outbox.enqueue("a")
    assertThat(attempts).isEqualTo(1)

    advanceTimeBy(10.seconds)
    runCurrent()
    assertThat(attempts).isEqualTo(2)
    handle.assertSucceeded()
  }

  @Test
  fun `a test fetch holds calls until released`() = runTest {
    val fetch = TestFetch { 1 }
    val query =
      testDataScope(eager = true).query(QueryStore.inMemory(), fetch, Freshness.staleAfter(1.hours))

    fetch.hold()
    val handle = query.requestFetch(force = true)
    handle.assertRunning()
    assertThat(fetch.running).isEqualTo(1)

    fetch.release()
    handle.assertSucceeded()
    assertThat(fetch.callCount).isEqualTo(1)
    assertThat(fetch.running).isEqualTo(0)
  }

  @Test
  fun `a test fetch records a call a newer forced request cancels`() = runTest {
    val fetch = TestFetch { 1 }
    val query =
      testDataScope(eager = true).query(QueryStore.inMemory(), fetch, Freshness.staleAfter(1.hours))

    fetch.hold()
    val first = query.requestFetch(force = true)
    // After an invalidation, a forced request cancels the older forced fetch instead of joining it
    query.invalidate()
    val second = query.requestFetch(force = true)
    first.assertCancelled()
    assertThat(fetch.cancelled).containsExactly(Unit)

    fetch.release()
    second.assertSucceeded()
  }

  @Test
  fun `a test fetch answers with whatever respond gives now`() = runTest {
    val data = testDataScope(eager = true)
    val fetch = TestFetch { 1 }
    val query = data.query(QueryStore.inMemory(), fetch, Freshness.staleAfter(1.hours))

    query.requestFetch(force = true).assertSucceeded()
    fetch.respond = { error("down") }
    query.requestFetch(force = true).assertFailed<IllegalStateException>()
    data.fetchFailures.assertSingle<IllegalStateException>()
  }

  @Test
  fun `a keyed test fetch counts calls per key and how many ran at once`() = runTest {
    val fetch = TestKeyedFetch<String, String> { it }
    fetch.respond = { key -> "$key#${fetch.callCount(key)}" }
    val values =
      testDataScope(eager = true)
        .keyed(
          store = QueryStore.inMemoryByKey(),
          fetch = fetch,
          freshness = Freshness.staleAfter(1.hours),
        )

    fetch.hold()
    val both = values.requestFetch(listOf("a", "b"), force = true)
    assertThat(fetch.running).isEqualTo(2)
    fetch.release()
    both.assertSucceeded()

    values.requestFetch("a", force = true).assertSucceeded()
    assertThat(fetch.callCount("a")).isEqualTo(2)
    assertThat(fetch.callCount("b")).isEqualTo(1)
    assertThat(fetch.maxRunning).isEqualTo(2)
    assertThat(values.data("a").first()?.value).isEqualTo("a#2")
  }

  @Test
  fun `a recording store shows what a query did to it`() = runTest {
    val data = testDataScope(eager = true)
    val store = RecordingQueryStore<Int>()
    val query = data.query(store, TestFetch { 1 }, Freshness.staleAfter(1.hours))

    query.requestFetch(force = false).assertSucceeded()
    // Fresh now, so this one checks the stored time and writes nothing
    query.requestFetch(force = false).assertSucceeded()
    query.invalidate().assertSucceeded()

    assertThat(store.writes).containsExactly(Fetched(1, data.clock.now()))
    assertThat(store.calls.last()).isEqualTo(RecordingQueryStore.Call.Invalidate)
  }

  @Test
  fun `a test send holds a payload until released`() = runTest {
    val send = TestSend<String>()
    val outbox = testDataScope(eager = true).outbox<String>("notes", send, send::revert)

    send.hold("a")
    val handle = outbox.enqueue("a")
    handle.assertRunning()
    assertThat(send.attempts).containsExactly("a")
    assertThat(send.sent).isEmpty()

    send.release("a")
    handle.assertSucceeded()
    assertThat(send.sent).containsExactly("a")
  }

  @Test
  fun `a test send fails transiently and then for good`() = runTest {
    val send = TestSend<String>()
    val outbox =
      testDataScope(eager = true)
        .outbox<String>(
          "notes",
          send,
          send::revert,
          retry = RetryPolicy(maxAttempts = 3) { 1.seconds },
        )

    send.failTransiently()
    val retried = outbox.enqueue("a")
    assertThat(send.attempts("a")).isEqualTo(1)
    advanceTimeBy(1.seconds)
    runCurrent()
    retried.assertSucceeded()
    assertThat(send.attempts("a")).isEqualTo(2)

    send.failPermanently()
    outbox.enqueue("b").assertFailed<Throwable>()
    assertThat(send.reverted).containsExactly("b")
    assertThat(send.sent).containsExactly("a")
  }

  @Test
  fun `data scopes from one test factory share failures and outbox rows`() = runTest {
    val factory = testDataScopeFactory(eager = true, online = false)
    val owner =
      CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext.job))
    val screen = factory.create(owner)
    val app = factory.create(backgroundScope)

    screen.newActions().run { error("boom") }
    assertThat(app.actionFailures.take()).hasSize(1)

    val send = TestSend<String>()
    screen.outbox<String>("notes", send).enqueue("a")
    assertThat(factory.outboxStore<String>("notes").entries().first().map { it.payload })
      .containsExactly("a")
    // The screen's scope ends, so another owner can run the outbox and send what it left
    owner.coroutineContext.job.cancelAndJoin()
    app.outbox<String>("notes", send)
    factory.online.value = true
    assertThat(send.sent).containsExactly("a")
    factory.assertNoFailures()
  }

  @Test
  fun `suspending assertions wait for work that is not eager`() = runTest {
    val data = testDataScope()
    val actions = data.newActions()

    actions.run {}.awaitSucceeded()
    assertThat(actions.run { error("boom") }.awaitFailed<IllegalStateException>().message)
      .isEqualTo("boom")
  }

  @Test
  fun `a test operation settles once`() = runTest {
    val handle = TestOperation()
    handle.assertPending()

    handle.start()
    handle.assertRunning()
    handle.succeed()
    handle.assertSucceeded()

    assertFailsWith<IllegalStateException> { handle.fail(IllegalStateException("late")) }
    assertFailsWith<IllegalStateException> { handle.start() }
  }

  @Test
  fun `awaiting a test operation returns once the test settles it`() = runTest {
    val handle = TestOperation()
    val cause = IllegalStateException("rejected")
    val awaited = async { handle.await() }
    runCurrent()

    handle.fail(cause)
    assertThat(awaited.await()).isEqualTo(OperationState.Failed(cause))
    assertThat(handle.assertFailed<IllegalStateException>()).isSameInstanceAs(cause)
  }

  @Test
  fun `assertions name the state they found`() = runTest {
    val error = assertFailsWith<AssertionError> { Operation.Done.assertFailed<Throwable>() }
    assertThat(error.message.orEmpty()).contains("Succeeded")

    val cause = IllegalStateException("rejected")
    val failed = assertFailsWith<AssertionError> { Operation.failed(cause).assertSucceeded() }
    assertThat(failed.cause).isNotNull().isSameInstanceAs(cause)

    assertFailsWith<AssertionError> {
      Operation.failed(cause).assertFailed<IllegalArgumentException>()
    }
    Operation.cancelled.assertCancelled()
  }
}
