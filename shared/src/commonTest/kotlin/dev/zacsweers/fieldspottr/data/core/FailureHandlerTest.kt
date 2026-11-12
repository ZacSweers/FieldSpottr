// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import assertk.assertions.isSameInstanceAs
import dev.zacsweers.fieldspottr.data.core.testing.TestFailures
import dev.zacsweers.fieldspottr.data.core.testing.TestFetch
import dev.zacsweers.fieldspottr.data.core.testing.TestSend
import dev.zacsweers.fieldspottr.data.core.testing.awaitFailed
import dev.zacsweers.fieldspottr.data.core.testing.awaitSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.clearFetched
import dev.zacsweers.fieldspottr.data.core.testing.testClock
import dev.zacsweers.fieldspottr.data.core.testing.testDataScope
import dev.zacsweers.fieldspottr.data.core.testing.testDataScopeFactory
import dev.zacsweers.fieldspottr.data.core.testing.writeFetched
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest

/**
 * Failure handlers of their own on queries, outboxes and optimistic stores, and handlers that
 * throw, like a crash reporter that rethrows in a debug build.
 */
class FailureHandlerTest {
  // What reached the owner scope's exception handler
  private val handled = mutableListOf<Throwable>()
  // What the throwing handler threw, in order
  private val thrown = mutableListOf<Throwable>()

  /** A handler that records each failure it hears in [thrown] and then throws. */
  private val throwing: (Throwable) -> Unit = { failure ->
    val failed = HandlerFailure(failure)
    thrown += failed
    throw failed
  }

  /** A scope under the test whose exception handler records into [handled]. */
  private fun TestScope.owner(): CoroutineScope =
    CoroutineScope(
      backgroundScope.coroutineContext +
        SupervisorJob(backgroundScope.coroutineContext.job) +
        CoroutineExceptionHandler { _, t -> handled += t }
    )

  @Test
  fun `an outbox whose handler throws still sends later rows`() = runTest {
    val factory = testDataScopeFactory()
    val send = TestSend<String>()
    send.failure = { payload, _ -> PermanentFailure("Rejected $payload").takeIf { payload == "a" } }
    val data = factory.create(owner(), setOf(Changes.sender(onFailure = throwing, send = send)))
    val outbox = data.outbox(Changes, revert = send::revert, onFailure = throwing)

    val a = outbox.enqueue("a", key = "k")
    val b = outbox.enqueue("b", key = "k")
    a.awaitFailed<PermanentFailure>()
    b.awaitSucceeded()
    val c = outbox.enqueue("c", key = "k")
    c.awaitSucceeded()

    assertThat(send.sent).containsExactly("b", "c")
    assertThat(send.reverted).containsExactly("a")
    assertThat(thrown.single().cause).isNotNull().isInstanceOf<PermanentFailure>()
    assertThat(handled).isEqualTo(thrown)
    factory.assertNoFailures()
  }

  @Test
  fun `a query whose handler throws keeps watching its store`() = runTest {
    val state = MutableStateFlow<Fetched<Int>?>(Fetched(1, START))
    val broken = IllegalStateException("store failed")
    var collections = 0
    // The collector's collection comes first and the watcher's second, which fails after a value
    val flaky = flow {
      if (++collections == 2) {
        emit(state.value)
        throw broken
      }
      emitAll(state)
    }
    val store =
      QueryStore.of(
        flaky,
        clear = state::clearFetched,
        write = { fetched, keepsStored ->
          state.writeFetched(fetched, keepsStored)
        },
      )
    val factory = testDataScopeFactory(clock = testClock(START))
    val fetch = TestFetch { 2 }
    val query =
      factory
        .create(owner())
        .query(store, Freshness.staleAfter(1.hours), onFailure = throwing, fetch = fetch)

    backgroundScope.launch { query.data.collect {} }
    testScheduler.runCurrent()
    assertThat(thrown.map { it.cause }).containsExactly(broken)

    // The watcher survived the throw, so it collects again and sees the store lose its value
    advanceTimeBy(1.seconds)
    testScheduler.runCurrent()
    state.value = null
    testScheduler.runCurrent()

    assertThat(fetch.callCount).isEqualTo(1)
    assertThat(state.value).isEqualTo(Fetched(2, START + 1.seconds))
    assertThat(handled).isEqualTo(thrown)
    factory.assertNoFailures()
  }

  @Test
  fun `a runner whose handler throws keeps running commands`() = runTest {
    val data = testDataScopeFactory().create(owner())
    val actions = data.newActionRunner(onFailure = throwing)

    val first = actions.run { error("first") }
    val second = actions.run { error("second") }
    val third = actions.run {}
    first.awaitFailed<IllegalStateException>()
    second.awaitFailed<IllegalStateException>()
    third.awaitSucceeded()

    assertThat(thrown.map { it.cause?.message }).containsExactly("first", "second")
    // Once per throw, each the handler's own exception
    assertThat(handled).isEqualTo(thrown)
  }

  @Test
  fun `the factory's handlers are guarded too`() = runTest {
    val data =
      DataScope.Factory(onFailure = throwing, onFetchFailure = throwing, clock = testClock(START))
        .create(owner())
    val fetch = TestFetch<Int> { error("fetch failed") }
    val query = data.query(QueryStore.inMemory(), Freshness.staleAfter(1.hours), fetch = fetch)
    val actions = data.newActionRunner()

    query.refresh().awaitFailed<IllegalStateException>()
    actions.run { error("command failed") }.awaitFailed<IllegalStateException>()
    fetch.respond = { 1 }
    query.refresh().awaitSucceeded()
    actions.run {}.awaitSucceeded()

    assertThat(thrown.map { it.cause?.message }).containsExactly("fetch failed", "command failed")
    assertThat(handled).isEqualTo(thrown)
  }

  @Test
  fun `a throwing handler on a scope without an exception handler leaves the data scope running`() {
    val reporter = IllegalStateException("reporter")
    var ownerActive = false
    var afterwards: OperationState? = null
    // kotlinx-coroutines-test collects an exception that reaches the platform's uncaught exception
    // handler while a test runs, and runTest throws it once the body finishes. Catching it here
    // shows that it reached that handler and was not lost.
    val uncaught =
      assertFailsWith<IllegalStateException> {
        runTest {
          val owner = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
          val runner =
            DataScope.Factory(onFailure = { throw reporter }).create(owner).newActionRunner()

          runner.run { error("command failed") }
          testScheduler.runCurrent()
          ownerActive = owner.isActive
          afterwards = runner.run {}.await()
          owner.cancel()
        }
      }

    assertThat(uncaught).isSameInstanceAs(reporter)
    assertThat(ownerActive).isEqualTo(true)
    assertThat(afterwards).isEqualTo(OperationState.Succeeded)
  }

  @Test
  fun `a query's own handler hears its fetch and other failures`() = runTest {
    val own = TestFailures()
    val data = testDataScope(clock = testClock(START))
    val stored = MutableStateFlow<Fetched<Int>?>(Fetched(1, START))
    val store =
      QueryStore.of(
        stored,
        clear = stored::clearFetched,
        invalidate = { error("invalidate failed") },
        write = { fetched, keepsStored ->
          stored.writeFetched(fetched, keepsStored)
        },
      )
    val query =
      data.query(store, Freshness.staleAfter(1.hours), onFailure = own) {
        error("fetch failed")
      }

    query.refresh().awaitFailed<IllegalStateException>()
    query.invalidate().awaitFailed<IllegalStateException>()

    assertThat(own.take().map { it.message }).containsExactly("fetch failed", "invalidate failed")
    data.assertNoFailures()
  }

  @Test
  fun `a keyed set's own handler hears every key's failures`() = runTest {
    val own = TestFailures()
    val data = testDataScope(clock = testClock(START))
    val values =
      data.keyed(
        store = QueryStore.inMemoryByKey<String, Int>(),
        freshness = Freshness.staleAfter(1.hours),
        onFailure = own,
      ) { key ->
        error("$key failed")
      }

    values.refresh("a").awaitFailed<IllegalStateException>()
    values.refresh("b").awaitFailed<IllegalStateException>()

    assertThat(own.take().map { it.message }).containsExactly("a failed", "b failed")
    data.assertNoFailures()
  }

  @Test
  fun `a sender's own handler hears a row that fails for good`() = runTest {
    val own = TestFailures()
    val send = TestSend<String>()
    send.failPermanently()
    val data = testDataScope(senders = setOf(Changes.sender(onFailure = own, send = send)))
    val outbox = data.outbox(Changes)

    outbox.enqueue("a").awaitFailed<PermanentFailure>()

    assertThat(own.take().single()).isInstanceOf<PermanentFailure>()
    data.assertNoFailures()
  }

  @Test
  fun `a handle's own handler hears a revert that throws and the sender's hears the send`() =
    runTest {
      val handle = TestFailures()
      val drain = TestFailures()
      val send = TestSend<String>()
      send.failPermanently()
      val data = testDataScope(senders = setOf(Changes.sender(onFailure = drain, send = send)))
      val broken = IllegalStateException("revert failed")
      val outbox = data.outbox(Changes, revert = { _, _ -> throw broken }, onFailure = handle)

      outbox.enqueue("a").awaitFailed<PermanentFailure>()

      assertThat(handle.take()).containsExactly(broken)
      assertThat(drain.take().single()).isInstanceOf<PermanentFailure>()
      data.assertNoFailures()
    }

  @Test
  fun `an optimistic store's own handler hears an apply that throws`() = runTest {
    val own = TestFailures()
    val counts = OutboxKey<String>("counts")
    val data = testDataScope(clock = testClock(START), senders = setOf(counts.sender { _, _ -> }))
    val broken = IllegalStateException("apply failed")
    val store =
      data.optimisticStore(
        counts,
        base = QueryStore.inMemory(Fetched(1, START)),
        apply = { _, _ -> throw broken },
        onFailure = own,
      )

    // The server has the change, so the handle succeeds even though apply throws on commit
    store.mutate("a").awaitSucceeded()

    assertThat(own.all).isNotEmpty()
    assertThat(own.all.filterNot { it === broken || it.cause === broken }).isEmpty()
    data.assertNoFailures()
  }

  /** What [throwing] throws, with the failure it heard as its cause. */
  private class HandlerFailure(heard: Throwable) : RuntimeException("Handler failed", heard)

  private companion object {
    val START = Instant.parse("2026-06-10T12:00:00Z")
    val Changes = OutboxKey<String>("changes")
  }
}
