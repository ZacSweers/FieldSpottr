// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isTrue
import dev.zacsweers.fieldspottr.data.core.testing.TestFailures
import dev.zacsweers.fieldspottr.data.core.testing.TestFetch
import dev.zacsweers.fieldspottr.data.core.testing.TestSend
import dev.zacsweers.fieldspottr.data.core.testing.assertPending
import dev.zacsweers.fieldspottr.data.core.testing.assertSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.awaitSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.insertNew
import dev.zacsweers.fieldspottr.data.core.testing.testClock
import dev.zacsweers.fieldspottr.data.core.testing.testDataScope
import dev.zacsweers.fieldspottr.data.core.testing.testDataScopeFactory
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * How queries and outboxes follow the network state that [DataScope.Factory] takes: the state at
 * creation, repeated disconnects and reconnects, retries, who collects the state and for how long,
 * and what the core reads from a flow that is current or stale.
 */
class ConnectivityTest {

  private val store = QueryStore.inMemory<Int>()
  private val send = TestSend<String>()

  /** A fetch that fails while [network] is down and otherwise answers 1. */
  private fun serverFetch(network: StateFlow<Boolean>) = TestFetch {
    check(network.value) { "Offline" }
    1
  }

  /** Collects [query]'s `stateEager` for one value, then runs the work that started. */
  private suspend fun TestScope.collectOnce(query: DataQuery<Int>) {
    query.stateEager.first()
    runCurrent()
  }

  // Initial state

  @Test
  fun `a scope that starts offline fails a first load fast and shows the failure`() = runTest {
    val data = testDataScope(clock = testClock(START), online = false)
    val fetch = serverFetch(data.online)
    val query = data.query(store, Freshness.staleAfter(1.hours), fetch = fetch)
    val states = mutableListOf<QueryState<Int>>()

    backgroundScope.launch { query.stateEager.toList(states) }
    runCurrent()

    assertThat(fetch.callCount).isEqualTo(1)
    assertThat(states.last().fetched).isNull()
    assertThat(states.last().status).isInstanceOf<FetchStatus.Failed>()
    data.fetchFailures.assertSingle<IllegalStateException>()
  }

  @Test
  fun `a scope that starts offline holds its outbox rows`() = runTest {
    val data =
      testDataScope(
        clock = testClock(START),
        online = false,
        senders = setOf(Changes.sender(send = send)),
      )
    val outbox = data.outbox(Changes)

    val handles = listOf(outbox.enqueue("a"), outbox.enqueue("b"))
    runCurrent()

    assertThat(send.attempts).isEmpty()
    assertThat(outbox.entries.first().map { it.payload }).containsExactly("a", "b")
    handles.forEach { it.assertPending() }
  }

  @Test
  fun `a scope that starts online loads a query and sends rows stored before it`() = runTest {
    val factory = testDataScopeFactory(clock = testClock(START))
    factory.outboxStore<String>(Changes.name).insertNew("", "stored")
    val data = factory.create(backgroundScope, setOf(Changes.sender(send = send)))
    val fetch = serverFetch(data.online)
    val query = data.query(store, Freshness.staleAfter(1.hours), fetch = fetch)
    runCurrent()
    assertThat(send.sent).containsExactly("stored")

    backgroundScope.launch { query.stateEager.collect {} }
    runCurrent()

    assertThat(fetch.callCount).isEqualTo(1)
    assertThat(query.status.value).isEqualTo(FetchStatus.Idle)
    data.assertNoFailures()
  }

  // Repeated disconnect and reconnect cycles

  @Test
  fun `each reconnect checks a collected query once and fetches one at a time`() = runTest {
    val data = testDataScope(clock = testClock(START))
    val fetch = serverFetch(data.online)
    val query = data.query(store, Freshness.staleAfter(1.hours), fetch = fetch)
    // Two collectors share one watcher, so one check per reconnect
    backgroundScope.launch { query.stateEager.collect {} }
    backgroundScope.launch { query.stateEager.collect {} }
    runCurrent()
    assertThat(fetch.callCount).isEqualTo(1)

    repeat(3) { cycle ->
      data.online.value = false
      runCurrent()
      // Stale by the reconnect, so its check fetches
      advanceTimeBy(2.hours)
      data.online.value = true
      runCurrent()
      assertThat(fetch.callCount).isEqualTo(cycle + 2)
      assertThat(query.status.value).isEqualTo(FetchStatus.Idle)
    }
    assertThat(fetch.maxRunning).isEqualTo(1)
    data.assertNoFailures()
  }

  @Test
  fun `repeated reconnects leave a fresh value that fetched fine`() = runTest {
    val data = testDataScope(clock = testClock(START))
    val fetch = serverFetch(data.online)
    val query = data.query(store, Freshness.staleAfter(1.hours), fetch = fetch)
    backgroundScope.launch { query.stateEager.collect {} }
    runCurrent()

    repeat(3) {
      data.online.value = false
      runCurrent()
      data.online.value = true
      runCurrent()
    }

    assertThat(fetch.callCount).isEqualTo(1)
  }

  @Test
  fun `reconnects while a reconnect fetch runs add no fetch beside it`() = runTest {
    val data = testDataScope(clock = testClock(START))
    val fetch = serverFetch(data.online)
    val query = data.query(store, Freshness.staleAfter(1.hours), fetch = fetch)
    backgroundScope.launch { query.stateEager.collect {} }
    runCurrent()
    advanceTimeBy(2.hours)
    fetch.hold()

    repeat(4) {
      data.online.value = false
      runCurrent()
      data.online.value = true
      runCurrent()
    }
    assertThat(fetch.running).isEqualTo(1)
    fetch.release()
    runCurrent()

    // The checks queued behind the first reconnect's fetch find its fresh value
    assertThat(fetch.callCount).isEqualTo(2)
    assertThat(fetch.maxRunning).isEqualTo(1)
    assertThat(query.status.value).isEqualTo(FetchStatus.Idle)
  }

  @Test
  fun `rows drain in order on each reconnect`() = runTest {
    val data =
      testDataScope(
        clock = testClock(START),
        online = false,
        senders = setOf(Changes.sender(send = send)),
      )
    val outbox = data.outbox(Changes)
    val handles = mutableListOf<Operation>()
    val sentBefore = mutableListOf<String>()

    listOf(listOf("a", "b"), listOf("c", "d"), listOf("e")).forEach { batch ->
      batch.forEach { handles += outbox.enqueue(it) }
      runCurrent()
      // Offline, so only earlier batches went out
      assertThat(send.sent).isEqualTo(sentBefore)
      data.online.value = true
      runCurrent()
      sentBefore += batch
      assertThat(send.sent).isEqualTo(sentBefore)
      data.online.value = false
      runCurrent()
    }

    assertThat(send.sent).containsExactly("a", "b", "c", "d", "e")
    assertThat(send.attempts).containsExactly("a", "b", "c", "d", "e")
    handles.forEach { it.assertSucceeded() }
  }

  // Retry behavior

  @Test
  fun `a failure made online still holds off a collector that returns after a reconnect`() =
    runTest {
      val data = testDataScope(clock = testClock(START))
      val fetch = serverFetch(data.online)
      val query = data.query(store, Freshness.staleAfter(1.hours), fetch = fetch)
      val respond = fetch.respond
      fetch.respond = { error("Server down") }
      collectOnce(query)
      assertThat(fetch.callCount).isEqualTo(1)

      // Nothing collects, so the reconnect checks nothing
      data.online.value = false
      runCurrent()
      data.online.value = true
      runCurrent()
      fetch.respond = respond
      // Inside the 1 second window of the default policy
      collectOnce(query)
      assertThat(fetch.callCount).isEqualTo(1)

      advanceTimeBy(1.seconds)
      collectOnce(query)
      assertThat(fetch.callCount).isEqualTo(2)
    }

  @Test
  fun `a declined failure made online holds through every reconnect until a refresh succeeds`() =
    runTest {
      val data =
        testDataScope(
          clock = testClock(START),
          fetchRetry = RetryPolicy(maxAttempts = 1) { 1.seconds },
        )
      val fetch = serverFetch(data.online)
      val query = data.query(store, Freshness.staleAfter(1.hours), fetch = fetch)
      val respond = fetch.respond
      fetch.respond = { error("Server down") }
      backgroundScope.launch { query.stateEager.collect {} }
      runCurrent()
      fetch.respond = respond

      repeat(3) {
        data.online.value = false
        runCurrent()
        data.online.value = true
        runCurrent()
      }
      assertThat(fetch.callCount).isEqualTo(1)
      assertThat(query.status.value).isInstanceOf<FetchStatus.Failed>()

      query.refresh().awaitSucceeded()
      advanceTimeBy(2.hours)
      data.online.value = false
      runCurrent()
      data.online.value = true
      runCurrent()

      // The success cleared the decline, so the reconnect fetches the stale value
      assertThat(fetch.callCount).isEqualTo(3)
    }

  @Test
  fun `a reconnect keeps a send backoff whose deadline has not passed`() = runTest {
    send.failTransiently()
    val data =
      testDataScope(
        clock = testClock(START),
        senders =
          setOf(Changes.sender(retry = RetryPolicy(maxAttempts = 3) { 5.minutes }, send = send)),
      )
    val handle = data.outbox(Changes).enqueue("a")
    runCurrent()
    assertThat(send.attempts("a")).isEqualTo(1)

    data.online.value = false
    runCurrent()
    advanceTimeBy(1.minutes)
    data.online.value = true
    runCurrent()
    assertThat(send.attempts("a")).isEqualTo(1)

    advanceTimeBy(4.minutes)
    runCurrent()
    assertThat(send.attempts("a")).isEqualTo(2)
    handle.assertSucceeded()
  }

  @Test
  fun `a send backoff that ends while offline sends on reconnect`() = runTest {
    send.failTransiently()
    val data =
      testDataScope(
        clock = testClock(START),
        senders =
          setOf(Changes.sender(retry = RetryPolicy(maxAttempts = 3) { 5.minutes }, send = send)),
      )
    val handle = data.outbox(Changes).enqueue("a")
    runCurrent()
    data.online.value = false
    runCurrent()

    // The timer fires while offline, so its drain sends nothing
    advanceTimeBy(10.minutes)
    runCurrent()
    assertThat(send.attempts("a")).isEqualTo(1)
    data.online.value = true
    runCurrent()

    assertThat(send.attempts("a")).isEqualTo(2)
    handle.assertSucceeded()
  }

  // Collector lifecycle

  @Test
  fun `a query collects the network only while its stateEager has a collector`() = runTest {
    val data = testDataScope(clock = testClock(START))
    val query = data.query(store, Freshness.staleAfter(1.hours), fetch = serverFetch(data.online))
    val subscriptions = data.online.subscriptionCount

    val readers =
      listOf(
        backgroundScope.launch { query.stored.collect {} },
        backgroundScope.launch { query.status.collect {} },
      )
    runCurrent()
    assertThat(subscriptions.value).isEqualTo(0)

    val first = backgroundScope.launch { query.stateEager.collect {} }
    runCurrent()
    assertThat(subscriptions.value).isEqualTo(1)
    val second = backgroundScope.launch { query.stateEager.collect {} }
    runCurrent()
    assertThat(subscriptions.value).isEqualTo(1)

    first.cancel()
    runCurrent()
    assertThat(subscriptions.value).isEqualTo(1)
    second.cancel()
    runCurrent()
    assertThat(subscriptions.value).isEqualTo(0)

    // A collector that leaves after one value leaves no subscription either
    collectOnce(query)
    assertThat(subscriptions.value).isEqualTo(0)
    readers.forEach { it.cancel() }
  }

  @Test
  fun `an outbox engine collects the network until its scope is cancelled`() = runTest {
    val owner =
      CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext.job))
    val factory = testDataScopeFactory(clock = testClock(START))
    val data = factory.create(owner, setOf(Changes.sender(send = send)))
    val query = data.query(store, Freshness.staleAfter(1.hours), fetch = serverFetch(data.online))
    val subscriptions = factory.online.subscriptionCount
    runCurrent()
    assertThat(subscriptions.value).isEqualTo(1)

    // A collector outside the data scope adds the query's watcher
    backgroundScope.launch { query.stateEager.collect {} }
    runCurrent()
    assertThat(subscriptions.value).isEqualTo(2)
    repeat(2) {
      factory.online.value = false
      runCurrent()
      factory.online.value = true
      runCurrent()
    }
    assertThat(subscriptions.value).isEqualTo(2)

    // The watcher runs in the data scope, so it stops with it while the collector stays
    owner.cancel()
    runCurrent()
    assertThat(subscriptions.value).isEqualTo(0)
  }

  @Test
  fun `a data scope without senders leaves the network uncollected`() = runTest {
    val data = testDataScope(clock = testClock(START))
    data.query(store, Freshness.staleAfter(1.hours), fetch = serverFetch(data.online))
    runCurrent()

    assertThat(data.online.subscriptionCount.value).isEqualTo(0)
  }

  @Test
  fun `a collector that missed a reconnect fetches stale data at once`() = runTest {
    val data = testDataScope(clock = testClock(START))
    val fetch = serverFetch(data.online)
    val query = data.query(store, Freshness.staleAfter(1.hours), fetch = fetch)
    collectOnce(query)
    assertThat(fetch.callCount).isEqualTo(1)

    data.online.value = false
    runCurrent()
    advanceTimeBy(2.hours)
    data.online.value = true
    runCurrent()
    // Nothing collected, so nothing checked
    assertThat(fetch.callCount).isEqualTo(1)
    collectOnce(query)

    assertThat(fetch.callCount).isEqualTo(2)
  }

  // Currency of the network state

  /**
   * Runs a first load while [network] is down, from a collector that leaves after one value, so no
   * watcher collects [online]. The network then returns while nothing collects, and a second
   * collector arrives inside the 1 second hold-off window. Returns the fetch.
   */
  private suspend fun TestScope.failOfflineThenReturn(
    network: MutableStateFlow<Boolean>,
    online: StateFlow<Boolean>,
  ): TestFetch<Int> {
    val data =
      DataScope.Factory(onFailure = TestFailures(), online = online, clock = testClock(START))
        .create(backgroundScope)
    val fetch = serverFetch(network)
    val query = data.query(store, Freshness.staleAfter(1.hours), fetch = fetch)
    runCurrent()
    network.value = false
    runCurrent()
    collectOnce(query)
    assertThat(fetch.callCount).isEqualTo(1)

    network.value = true
    runCurrent()
    collectOnce(query)
    return fetch
  }

  @Test
  fun `a flow shared eagerly records an offline failure that a returning collector skips`() =
    runTest {
      val network = MutableStateFlow(true)
      val online = network.stateIn(backgroundScope, SharingStarted.Eagerly, true)

      val fetch = failOfflineThenReturn(network, online)

      assertThat(fetch.callCount).isEqualTo(2)
    }

  @Test
  fun `a flow shared while subscribed records an offline failure as online and holds off`() =
    runTest {
      val network = MutableStateFlow(true)
      val online = network.stateIn(backgroundScope, SharingStarted.WhileSubscribed(), true)

      val fetch = failOfflineThenReturn(network, online)

      // The failure read the stale true, so it holds off like a server failure
      assertThat(online.value).isTrue()
      assertThat(fetch.callCount).isEqualTo(1)
      advanceTimeBy(1.seconds)
      runCurrent()
      assertThat(fetch.callCount).isEqualTo(1)
    }

  private companion object {
    val START: Instant = Instant.parse("2026-06-10T12:00:00Z")
    val Changes = OutboxKey<String>("changes")
  }
}
