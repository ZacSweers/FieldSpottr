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
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.messageContains
import dev.zacsweers.fieldspottr.data.core.impl.committedRecords
import dev.zacsweers.fieldspottr.data.core.impl.committedValues
import dev.zacsweers.fieldspottr.data.core.testing.TestDataScope
import dev.zacsweers.fieldspottr.data.core.testing.TestFailures
import dev.zacsweers.fieldspottr.data.core.testing.TestFetch
import dev.zacsweers.fieldspottr.data.core.testing.TestKeyedFetch
import dev.zacsweers.fieldspottr.data.core.testing.TestSend
import dev.zacsweers.fieldspottr.data.core.testing.assertCancelled
import dev.zacsweers.fieldspottr.data.core.testing.awaitFailed
import dev.zacsweers.fieldspottr.data.core.testing.awaitSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.testClock
import dev.zacsweers.fieldspottr.data.core.testing.testDataScope
import dev.zacsweers.fieldspottr.data.core.testing.testDataScopeFactory
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

class MutableQueryStoreTest {
  /** Adds one tag. */
  @Serializable data class Add(val tag: String)

  private val send = TestSend<Add>()
  // The row ids each send attempt was given, in attempt order
  private val sentIds = mutableListOf<EntryId>()
  private val fetch = TestFetch { listOf("a") }
  private lateinit var data: TestDataScope
  // The base store, to check what the truth holds apart from the view
  private lateinit var truth: InMemoryQueryStore<List<String>>

  /**
   * A tag list store seeded with [stored], and a fresh query over it. With [idempotent] false it
   * appends instead, so a change the overlay applied twice would show. The store then reports the
   * apply once, which [assertOnlyIdempotencyReport] expects.
   */
  private fun TestScope.tags(
    online: Boolean = true,
    stored: Fetched<List<String>>? = Fetched(listOf("a"), START),
    idempotent: Boolean = true,
  ): Pair<MutableQueryStore<List<String>, Add>, DataQuery<List<String>>> {
    data = testDataScope(clock = testClock(START), online = online)
    truth = QueryStore.inMemory(stored)
    val store =
      data.mutableStore<List<String>, Add>(
        name = "tags",
        base = truth,
        apply =
          if (idempotent) {
            ::addOnce
          } else {
            ::append
          },
        send = { add, id ->
          sentIds += id
          send(add, id)
        },
      )
    val query = data.query(store, fetch, Freshness.staleAfter(1.hours))
    return store to query
  }

  /** Passes when the only failure is the store's one report that [append] is not idempotent. */
  private fun assertOnlyIdempotencyReport() {
    data.fetchFailures.assertNone()
    assertThat(data.actionFailures.assertSingle<IllegalStateException>())
      .messageContains("not idempotent")
  }

  @Test
  fun `a change shows at once and stays once sent`() = runTest {
    val (store, query) = tags()
    query.data.test {
      assertThat(awaitItem()?.value).isEqualTo(listOf("a"))
      val handle = store.mutate(Add("b"))
      assertThat(awaitItem()?.value).isEqualTo(listOf("a", "b"))
      handle.awaitSucceeded()
      // The truth took the change as the row left, so the value never moved
      expectNoEvents()
    }
    assertThat(send.sent).containsExactly(Add("b"))
    assertThat(store.pending.first()).isEmpty()
    // The truth keeps its fetch time, since the server never said the rest is current
    assertThat(query.stored.first()).isEqualTo(Fetched(listOf("a", "b"), START))
    data.assertNoFailures()
  }

  @Test
  fun `a fetch that lands while a change waits keeps the change on top`() = runTest {
    send.hold(Add("b"))
    val (store, query) = tags(idempotent = false)
    query.data.test {
      assertThat(awaitItem()?.value).isEqualTo(listOf("a"))
      val handle = store.mutate(Add("b"))
      assertThat(awaitItem()?.value).isEqualTo(listOf("a", "b"))

      fetch.respond = { listOf("a", "z") }
      query.requestFetch(force = true).awaitSucceeded()
      assertThat(awaitItem()?.value).isEqualTo(listOf("a", "z", "b"))

      send.release(Add("b"))
      handle.awaitSucceeded()
      expectNoEvents()
    }
    assertThat(query.stored.first()?.value).isEqualTo(listOf("a", "z", "b"))
    assertOnlyIdempotencyReport()
  }

  @Test
  fun `a fetch that read the server before a send finished does not hide the change`() = runTest {
    val (store, query) = tags(idempotent = false)
    query.data.test {
      assertThat(awaitItem()?.value).isEqualTo(listOf("a"))
      // The refresh reads the server before the change reaches it, and lands after the send
      fetch.respond = { listOf("a", "z") }
      fetch.hold()
      val refresh = query.requestFetch(force = true)
      advanceTimeBy(1.seconds)
      store.mutate(Add("b")).awaitSucceeded()
      assertThat(awaitItem()?.value).isEqualTo(listOf("a", "b"))

      fetch.release()
      refresh.awaitSucceeded()
      // The fetch may lack the change, so the truth takes it too, and a restart that has no record
      // of the send still shows it
      assertThat(awaitItem()?.value).isEqualTo(listOf("a", "z", "b"))
      assertThat(truth.data.value?.value).isEqualTo(listOf("a", "z", "b"))

      // A fetch from after the send says what the server holds, and here it dropped the change
      advanceTimeBy(1.seconds)
      query.requestFetch(force = true).awaitSucceeded()
      assertThat(awaitItem()?.value).isEqualTo(listOf("a", "z"))
    }
    assertOnlyIdempotencyReport()
  }

  @Test
  fun `a later change carries one that an earlier fetch left out`() = runTest {
    val (store, query) = tags(idempotent = false)
    fetch.respond = { listOf("a", "z") }
    fetch.hold()
    val refresh = query.requestFetch(force = true)
    advanceTimeBy(1.seconds)
    store.mutate(Add("b")).awaitSucceeded()
    fetch.release()
    refresh.awaitSucceeded()
    assertThat(query.stored.first()?.value).isEqualTo(listOf("a", "z", "b"))

    store.mutate(Add("c")).awaitSucceeded()

    // The commit folded onto the view, so the truth now holds both changes
    assertThat(truth.data.value?.value).isEqualTo(listOf("a", "z", "b", "c"))
    assertThat(query.stored.first()?.value).isEqualTo(listOf("a", "z", "b", "c"))
    assertOnlyIdempotencyReport()
  }

  @Test
  fun `a rejected change drops out and comes back on retry`() = runTest {
    send.failPermanently()
    val (store, query) = tags()
    query.data.test {
      assertThat(awaitItem()?.value).isEqualTo(listOf("a"))
      val handle = store.mutate(Add("b"))
      assertThat(awaitItem()?.value).isEqualTo(listOf("a", "b"))
      handle.awaitFailed<PermanentFailure>()
      assertThat(awaitItem()?.value).isEqualTo(listOf("a"))
      val failed = store.pending.first().single()
      assertThat(failed.status).isInstanceOf<OutboxStatus.Failed>()

      send.succeed()
      store.retry(failed.id).awaitSucceeded()
      assertThat(awaitItem()?.value).isEqualTo(listOf("a", "b"))
      expectNoEvents()
    }
    assertThat(send.sent).containsExactly(Add("b"))
    assertThat(store.pending.first()).isEmpty()
  }

  @Test
  fun `a discarded change drops out for good`() = runTest {
    val (store, query) = tags(online = false)
    query.data.test {
      assertThat(awaitItem()?.value).isEqualTo(listOf("a"))
      val handle = store.mutate(Add("b"))
      assertThat(awaitItem()?.value).isEqualTo(listOf("a", "b"))
      store.discard(store.pending.first().single().id).awaitSucceeded()
      assertThat(awaitItem()?.value).isEqualTo(listOf("a"))
      assertThat(handle.state.value).isEqualTo(OperationState.Cancelled)
    }
    data.online.value = true
    assertThat(send.sent).isEmpty()
    assertThat(store.pending.first()).isEmpty()
  }

  @Test
  fun `clear drops the waiting changes with the truth`() = runTest {
    val (store, query) = tags(online = false)
    query.data.test {
      assertThat(awaitItem()?.value).isEqualTo(listOf("a"))
      val handle = store.mutate(Add("b"))
      assertThat(awaitItem()?.value).isEqualTo(listOf("a", "b"))
      store.pending.first { it.isNotEmpty() }

      store.clear()

      // The rows go first, then the truth, and a collector on another dispatcher may see only the
      // end of that. The query then fetches again, as it does whenever a stored value goes away,
      // which a logout avoids by cancelling the scope first.
      var next = awaitItem()
      if (next != null) {
        assertThat(next.value).isEqualTo(listOf("a"))
        next = awaitItem()
      }
      assertThat(next).isNull()
      assertThat(handle.await()).isEqualTo(OperationState.Cancelled)
      cancelAndIgnoreRemainingEvents()
    }
    assertThat(store.pending.first()).isEmpty()
    data.online.value = true
    assertThat(send.sent).isEmpty()
  }

  @Test
  fun `changes made offline wait and send in order on reconnect`() = runTest {
    val (store, query) = tags(online = false, idempotent = false)
    query.data.test {
      assertThat(awaitItem()?.value).isEqualTo(listOf("a"))
      store.mutate(Add("b"))
      assertThat(awaitItem()?.value).isEqualTo(listOf("a", "b"))
      val last = store.mutate(Add("c"))
      assertThat(awaitItem()?.value).isEqualTo(listOf("a", "b", "c"))
      assertThat(send.sent).isEmpty()
      val waiting = store.pending.first()
      assertThat(waiting.map { it.mutation }).isEqualTo(listOf(Add("b"), Add("c")))

      data.online.value = true
      last.awaitSucceeded()
      expectNoEvents()
      // Each send got its row's id, the one pending showed, for an idempotency key
      assertThat(sentIds).isEqualTo(waiting.map { it.id })
    }
    assertThat(send.sent).containsExactly(Add("b"), Add("c"))
    assertThat(query.stored.first()?.value).isEqualTo(listOf("a", "b", "c"))
    assertOnlyIdempotencyReport()
  }

  @Test
  fun `a value exchange returns becomes fresh truth`() = runTest {
    data = testDataScope(clock = testClock(START))
    val store =
      data.mutableStore<List<String>, Add>(
        name = "tags",
        base = QueryStore.inMemory(Fetched(listOf("a"), START - 1.hours)),
        apply = ::addOnce,
        // The server normalizes what it stores
        exchange = { add, _ -> listOf("a", add.tag.uppercase()) },
      )
    val query = data.query(store, fetch, Freshness.staleAfter(2.hours))
    query.data.test {
      assertThat(awaitItem()?.value).isEqualTo(listOf("a"))
      store.mutate(Add("b")).awaitSucceeded()
      assertThat(awaitItem()?.value).isEqualTo(listOf("a", "b"))
      assertThat(awaitItem()).isEqualTo(Fetched(listOf("a", "B"), START))
      expectNoEvents()
    }
  }

  @Test
  fun `a keyed store builds one base per key and commits through it`() = runTest {
    data = testDataScope(clock = testClock(START))
    val built = mutableListOf<String>()
    val stores =
      data.mutableStores<String, List<String>, Add>(
        name = "tagsByItem",
        // Each instance keeps state of its own, like a store that caches what it loaded
        base = { key ->
          built += key
          QueryStore.inMemory(Fetched(listOf(key), START))
        },
        apply = ::addOnce,
        send = { _, add, id -> send(add, id) },
      )
    val queries =
      data.keyed(
        store = stores,
        fetch = { key -> listOf(key) },
        freshness = Freshness.staleAfter(1.hours),
      )

    queries.data("x").test {
      assertThat(awaitItem()?.value).isEqualTo(listOf("x"))
      stores.mutate("x", Add("b")).awaitSucceeded()
      // The commit wrote the instance the view reads, so the change stays once the row leaves
      assertThat(awaitItem()?.value).isEqualTo(listOf("x", "b"))
      expectNoEvents()
    }
    assertThat(built).containsExactly("x")
  }

  @Test
  fun `a fetch from after a send drops the change's record`() = runTest {
    data = testDataScope(clock = testClock(START))
    val stores =
      data.mutableStores<String, List<String>, Add>(
        name = "tagsByItem",
        base = QueryStore.inMemoryByKey(),
        apply = ::addOnce,
        send = { _, add, id -> send(add, id) },
      )
    val queries =
      data.keyed(
        store = stores,
        fetch = { key -> listOf(key) },
        freshness = Freshness.staleAfter(1.hours),
      )
    queries.data("x").first { it != null }

    stores.mutate("x", Add("b")).awaitSucceeded()
    // Until a fetch from after the send confirms the change, its record stays
    assertThat(stores.committedRecords).isEqualTo(1)

    data.clock.shift(1.minutes)
    queries.requestFetch("x", force = true).awaitSucceeded()
    assertThat(stores.committedRecords).isEqualTo(0)
  }

  @Test
  fun `a key keeps one value on record however many changes it sends`() = runTest {
    data = testDataScope(clock = testClock(START))
    val stores =
      data.mutableStores<String, List<String>, Add>(
        name = "tagsByItem",
        base = QueryStore.inMemoryByKey(),
        apply = ::addOnce,
        send = { _, add, id -> send(add, id) },
      )
    val queries =
      data.keyed(
        store = stores,
        fetch = { _ -> fetch() },
        freshness = Freshness.staleAfter(1.hours),
      )
    queries.requestFetch("x", force = false).awaitSucceeded()
    // A refresh that reads the server before the changes reach it, and lands after them
    fetch.respond = { listOf("a", "z") }
    fetch.hold()
    val refresh = queries.requestFetch("x", force = true)
    advanceTimeBy(1.seconds)

    for (tag in listOf("b", "c", "d")) {
      stores.mutate("x", Add(tag)).awaitSucceeded()
    }
    // Each send stays on record for the refresh, but only the newest keeps a copy of the value
    assertThat(stores.committedRecords).isEqualTo(3)
    assertThat(stores.committedValues).isEqualTo(1)

    fetch.release()
    refresh.awaitSucceeded()
    assertThat(stores("x").data.first()?.value).isEqualTo(listOf("a", "z", "b", "c", "d"))
    data.assertNoFailures()
  }

  @Test
  fun `a record that no fetch confirms is dropped minutes after its send`() = runTest {
    data = testDataScope(clock = testClock(START))
    val stores =
      data.mutableStores<String, List<String>, Add>(
        name = "tagsByItem",
        base = QueryStore.inMemoryByKey(),
        apply = ::addOnce,
        send = { _, add, id -> send(add, id) },
      )
    val queries =
      data.keyed(
        store = stores,
        fetch = { key -> listOf(key) },
        freshness = Freshness.staleAfter(1.hours),
      )
    queries.requestFetch("x", force = false).awaitSucceeded()
    queries.requestFetch("y", force = false).awaitSucceeded()
    stores.mutate("x", Add("b")).awaitSucceeded()

    // x is never fetched again, so only time can tell that no fetch still needs its record
    data.clock.shift(10.minutes)
    stores.mutate("y", Add("c")).awaitSucceeded()

    assertThat(stores.committedRecords).isEqualTo(1)
    // The truth holds the change, so x still shows it
    assertThat(stores("x").data.first()?.value).isEqualTo(listOf("x", "b"))
    data.assertNoFailures()
  }

  @Test
  fun `a key the base evicts while a change waits fetches again on screen`() = runTest {
    data = testDataScope(clock = testClock(START))
    val stores =
      data.mutableStores<String, List<String>, Add>(
        name = "tagsByItem",
        base = QueryStore.inMemoryByKey(maxEntries = 1),
        apply = ::addOnce,
        send = { _, add, id -> send(add, id) },
      )
    val fetch = TestKeyedFetch<String, List<String>> { key -> listOf(key) }
    val queries =
      data.keyed(store = stores, fetch = fetch, freshness = Freshness.staleAfter(1.hours))
    queries.requestFetch("x", force = false).awaitSucceeded()
    send.hold(Add("b"))

    queries.data("x").test {
      assertThat(awaitItem()?.value).isEqualTo(listOf("x"))
      stores.mutate("x", Add("b"))
      assertThat(awaitItem()?.value).isEqualTo(listOf("x", "b"))

      // Fetching y evicts x's truth, which leaves only the waiting change
      queries.requestFetch("y", force = false).awaitSucceeded()
      assertThat(awaitItem()?.value).isEqualTo(listOf("b"))
      // x is on screen, so it fetches again, as it would had its value gone away
      assertThat(awaitItem()?.value).isEqualTo(listOf("x", "b"))
      cancelAndIgnoreRemainingEvents()
    }
    assertThat(fetch.callCount("x")).isEqualTo(2)
    data.assertNoFailures()
  }

  @Test
  fun `keyed stores change one key and leave the rest alone`() = runTest {
    data = testDataScope(clock = testClock(START))
    val stores =
      data.mutableStores<String, List<String>, Add>(
        name = "tagsByItem",
        base = QueryStore.inMemoryByKey(),
        apply = ::addOnce,
        send = { _, add, id -> send(add, id) },
      )
    val queries =
      data.keyed(
        store = stores,
        fetch = { key -> listOf(key) },
        freshness = Freshness.staleAfter(1.hours),
      )

    queries.data("x").test {
      assertThat(awaitItem()).isNull()
      assertThat(awaitItem()?.value).isEqualTo(listOf("x"))
      stores.mutate("x", Add("b")).awaitSucceeded()
      assertThat(awaitItem()?.value).isEqualTo(listOf("x", "b"))
      expectNoEvents()
    }
    assertThat(queries.data("y").first { it != null }?.value).isEqualTo(listOf("y"))
    assertThat(stores.pendingCount.first()).isEqualTo(0)
    assertThat(send.sent).containsExactly(Add("b"))
    data.assertNoFailures()
  }

  @Test
  fun `a fetch that read the server after the change lands during the send without doubling it`() =
    runTest {
      send.hold(Add("b"))
      val (store, query) = tags()
      query.data.test {
        assertThat(awaitItem()?.value).isEqualTo(listOf("a"))
        val handle = store.mutate(Add("b"))
        assertThat(awaitItem()?.value).isEqualTo(listOf("a", "b"))

        // The server took the change before it answered a refresh, which lands while the send
        // still waits. The store cannot tell that from a refresh that read the server first.
        fetch.respond = { listOf("a", "z", "b") }
        query.requestFetch(force = true).awaitSucceeded()
        assertThat(awaitItem()?.value).isEqualTo(listOf("a", "z", "b"))

        advanceTimeBy(1.seconds)
        send.release(Add("b"))
        handle.awaitSucceeded()
        expectNoEvents()
      }
      // An idempotent apply folds the change into a truth that already holds it without a trace
      assertThat(truth.data.value?.value).isEqualTo(listOf("a", "z", "b"))
      data.assertNoFailures()
    }

  @Test
  fun `a fetch that read the server after the change lands after the commit without doubling it`() =
    runTest {
      val (store, query) = tags()
      query.data.test {
        assertThat(awaitItem()?.value).isEqualTo(listOf("a"))
        // The refresh starts before the send and answers after it, with the change already in it
        fetch.respond = { listOf("a", "z", "b") }
        fetch.hold()
        val refresh = query.requestFetch(force = true)
        advanceTimeBy(1.seconds)
        store.mutate(Add("b")).awaitSucceeded()
        assertThat(awaitItem()?.value).isEqualTo(listOf("a", "b"))

        fetch.release()
        refresh.awaitSucceeded()
        // The truth is the server's answer, with the change applied once more on top to no effect
        assertThat(awaitItem()?.value).isEqualTo(listOf("a", "z", "b"))
        assertThat(truth.data.value?.value).isEqualTo(listOf("a", "z", "b"))
        expectNoEvents()
      }
      data.assertNoFailures()
    }

  @Test
  fun `an apply that is not idempotent is reported once and its changes still land`() = runTest {
    val (store, query) = tags(idempotent = false)
    store.mutate(Add("b")).awaitSucceeded()
    store.mutate(Add("c")).awaitSucceeded()

    assertThat(query.stored.first()?.value).isEqualTo(listOf("a", "b", "c"))
    assertOnlyIdempotencyReport()
    // The first commit told the owner which change to look at
    assertThat(data.actionFailures.all.single()).messageContains("Add(tag=b)")
  }

  @Test
  fun `a change sent again after a restart leaves a truth that already holds it alone`() = runTest {
    data = testDataScope(clock = testClock(START))
    // What a process that died between the truth write and the row's status leaves behind: the
    // change in the truth, and its row still Sending
    truth = QueryStore.inMemory(Fetched(listOf("a", "b"), START))
    val rows = data.outboxStore<KeyedMutation<Unit, Add>>("tags")
    rows.insert(key = Unit.toString(), payload = KeyedMutation(Unit, Add("b")))
    val row = rows.entries().first().single()
    rows.update(row.copy(status = OutboxStatus.Sending), expected = OutboxStatus.Queued)

    val store =
      data.mutableStore<List<String>, Add>(
        name = "tags",
        base = truth,
        apply = ::addOnce,
        send = { add, id ->
          sentIds += id
          send(add, id)
        },
      )
    store.data.test {
      assertThat(awaitItem()?.value).isEqualTo(listOf("a", "b"))
      store.pending.first { it.isEmpty() }
      expectNoEvents()
    }
    // The next owner sent the row again with its id, and the truth took the change once
    assertThat(send.sent).containsExactly(Add("b"))
    assertThat(sentIds).containsExactly(row.id)
    assertThat(truth.data.value).isEqualTo(Fetched(listOf("a", "b"), START))
    data.assertNoFailures()
  }

  @Test
  fun `the stored milestone settles once the row is kept while the send still runs`() = runTest {
    send.hold(Add("b"))
    val (store, query) = tags()

    val handle = store.mutate(Add("b"))
    handle.stored.awaitSucceeded()

    // The change is kept and shows, and the server has not answered yet
    assertThat(query.data.first()?.value).isEqualTo(listOf("a", "b"))
    assertThat(handle.state.value is OperationState.Terminal).isFalse()
    assertThat(send.sent).isEmpty()
    send.release(Add("b"))
    handle.awaitSucceeded()
  }

  @Test
  fun `a refresh publishes no value that lacks a change requested before it`() = runTest {
    val inserts = HoldingOutboxStores()
    val failures = TestFailures()
    val scope =
      DataScope.Factory(
          onFailure = failures,
          onActionFailure = failures,
          online = MutableStateFlow(false),
          stores = inserts,
          clock = testClock(START),
        )
        .create(backgroundScope)
    truth = QueryStore.inMemory(Fetched(listOf("old"), START))
    val store =
      scope.mutableStore<List<String>, Add>(
        name = "tags",
        base = truth,
        apply = ::addOnce,
        send = { _, _ -> },
      )
    fetch.respond = { listOf("new") }
    // Never stale, so collecting the seeded value starts no fetch of its own
    val query = scope.query(store, fetch, Freshness.NeverStale)
    val seen = mutableListOf<List<String>?>()
    backgroundScope.launch { query.data.collect { seen += it?.value } }
    runCurrent()
    assertThat(seen).containsExactly(listOf("old"))

    // The first row is still being written when the refresh's fetch returns
    inserts.hold()
    store.mutate(Add("first"))
    runCurrent()
    store.mutate(Add("second"))
    val refresh = query.refresh()
    runCurrent()
    assertThat(fetch.callCount).isEqualTo(1)
    assertThat(seen).containsExactly(listOf("old"))

    inserts.release()
    refresh.awaitSucceeded()
    runCurrent()

    // The fetched value waited for the rows, so it never showed without the changes
    assertThat(seen.last()).isEqualTo(listOf("new", "first", "second"))
    for (value in seen) {
      if ("new" in value.orEmpty()) {
        assertThat(value).isEqualTo(listOf("new", "first", "second"))
      }
    }
    failures.assertNone()
  }

  @Test
  fun `a fetch write that lands after a commit from a later time is dropped`() = runTest {
    data = testDataScope(clock = testClock(START))
    truth = QueryStore.inMemory(Fetched(listOf("a"), START - 2.hours))
    val gate = CompletableDeferred<Unit>()
    // A base whose write of the fetched value stalls, like a slow file
    val base =
      QueryStore.of(truth.data, clear = { truth.clear() }, invalidate = { truth.invalidate() }) {
        value,
        fetchedAt ->
        if (value == listOf("old")) {
          gate.await()
        }
        truth.write(value, fetchedAt)
      }
    val store =
      data.mutableStore<List<String>, Add>(
        name = "tags",
        base = base,
        apply = ::addOnce,
        exchange = { _, _ -> listOf("confirmed") },
      )
    fetch.respond = { listOf("old") }
    val query = data.query(store, fetch, Freshness.staleAfter(1.hours))

    val refresh = query.refresh()
    runCurrent()
    assertThat(fetch.callCount).isEqualTo(1)
    advanceTimeBy(1.seconds)
    val change = store.mutate(Add("b"))
    runCurrent()
    // The commit takes its turn after the write in progress
    assertThat(truth.data.value?.value).isEqualTo(listOf("a"))

    gate.complete(Unit)
    refresh.awaitSucceeded()
    change.awaitSucceeded()

    // The older fetch landed first, and the commit from after it replaced it
    assertThat(truth.data.value).isEqualTo(Fetched(listOf("confirmed"), START + 1.seconds))
    assertThat(query.stored.first()?.value).isEqualTo(listOf("confirmed"))
    data.assertNoFailures()
  }

  @Test
  fun `an older truth shows a server answer as that answer`() = runTest {
    data = testDataScope(clock = testClock(START))
    truth = QueryStore.inMemory(Fetched(listOf("a"), START - 1.hours))
    val store =
      data.mutableStore<List<String>, Add>(
        name = "tags",
        base = truth,
        apply = ::addOnce,
        // The server normalizes what it stores
        exchange = { add, _ -> listOf("a", add.tag.uppercase()) },
      )
    val query = data.query(store, fetch, Freshness.staleAfter(2.hours))
    store.mutate(Add("b")).awaitSucceeded()
    assertThat(query.stored.first()).isEqualTo(Fetched(listOf("a", "B"), START))

    // A fetch from before the send that lands through the store is dropped
    store.write(listOf("a"), START - 1.minutes)
    assertThat(truth.data.value).isEqualTo(Fetched(listOf("a", "B"), START))
    assertThat(query.stored.first()?.value).isEqualTo(listOf("a", "B"))

    // One that reaches the truth itself, as the store holds it between a commit's record and its
    // write, shows the answer put back, which applying the change again could not rebuild
    truth.write(listOf("a"), START - 1.minutes)
    assertThat(query.stored.first()?.value).isEqualTo(listOf("a", "B"))
    data.assertNoFailures()
  }

  @Test
  fun `clear drops a change left sending by an owner that stopped`() = runTest {
    val scopes = testDataScopeFactory(clock = testClock(START))
    val owner =
      CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext.job))
    truth = QueryStore.inMemory(Fetched(listOf("a"), START))
    fun DataScope.tags() =
      mutableStore<List<String>, Add>(name = "tags", base = truth, apply = ::addOnce, send = send)
    val before = scopes.create(owner).tags()
    send.hold(Add("b"))
    before.mutate(Add("b"))
    before.pending.first { it.singleOrNull()?.status == OutboxStatus.Sending }

    // The owner dies with the send in flight, so the row stays Sending
    owner.cancel()
    owner.coroutineContext.job.join()
    before.clear()

    assertThat(before.data.first()).isNull()
    assertThat(before.pending.first()).isEmpty()

    // The next owner over the same rows finds nothing to send
    val after = scopes.create(backgroundScope).tags()
    runCurrent()
    assertThat(after.data.first()).isNull()
    assertThat(send.attempts).containsExactly(Add("b"))
    scopes.assertNoFailures()
  }

  @Test
  fun `an invalidate during a commit still leaves the truth stale`() = runTest {
    data = testDataScope(clock = testClock(START))
    val state = MutableStateFlow<Fetched<List<String>>?>(Fetched(listOf("a"), START))
    val commitWriting = CompletableDeferred<Unit>()
    val finishCommit = CompletableDeferred<Unit>()
    val base =
      QueryStore.of(
        state,
        clear = { state.value = null },
        invalidate = { state.update { it?.copy(fetchedAt = QueryStore.INVALIDATED) } },
      ) { value, fetchedAt ->
        // Pauses the commit's write, which comes after its read of the truth
        if (commitWriting.complete(Unit)) {
          finishCommit.await()
        }
        state.value = Fetched(value, fetchedAt)
      }
    val store =
      data.mutableStore<List<String>, Add>(
        name = "tags",
        base = base,
        apply = ::addOnce,
        send = { add, id -> send(add, id) },
      )

    val handle = store.mutate(Add("b"))
    commitWriting.await()
    val invalidated = async { store.invalidate() }
    runCurrent()
    finishCommit.complete(Unit)
    invalidated.await()
    handle.awaitSucceeded()

    assertThat(state.value).isEqualTo(Fetched(listOf("a", "b"), QueryStore.INVALIDATED))
    data.assertNoFailures()
  }

  @Test
  fun `clear drops a change the drainer claims while clear runs`() = runTest {
    val online = MutableStateFlow(false)
    val failures = TestFailures()
    val rows = InMemoryOutboxStores()
    // Lets the drainer claim the row between clear's read of it and clear's write
    val claimedDuringClear =
      object : OutboxStores {
        override fun <E> open(name: String, serializer: KSerializer<E>): OutboxStore<E> {
          val opened = rows.open(name, serializer)
          return object : OutboxStore<E> by opened {
            override suspend fun update(entry: OutboxEntry<E>, expected: OutboxStatus): Boolean {
              if (entry.status == OutboxStatus.Discarded && expected == OutboxStatus.Queued) {
                online.value = true
                opened.entry(entry.id).first { it?.status == OutboxStatus.Sending }
              }
              return opened.update(entry, expected)
            }
          }
        }

        override suspend fun clearAll() = rows.clearAll()
      }
    val scope =
      DataScope.Factory(
          onFailure = failures,
          online = online,
          stores = claimedDuringClear,
          clock = testClock(START),
        )
        .create(backgroundScope)
    truth = QueryStore.inMemory(Fetched(listOf("a"), START))
    val store =
      scope.mutableStore<List<String>, Add>(
        name = "tags",
        base = truth,
        apply = ::addOnce,
        send = { add, id -> send(add, id) },
      )
    send.hold(Add("b"))
    val handle = store.mutate(Add("b"))
    handle.stored.awaitSucceeded()

    store.clear()
    send.release(Add("b"))
    runCurrent()

    // The send was already out, but its result lands nowhere
    assertThat(truth.data.value).isNull()
    handle.assertCancelled()
    assertThat(store.pending.first()).isEmpty()
    failures.assertNone()
  }

  @Test
  fun `a commit that fails locally is reported and never sent again`() = runTest {
    data = testDataScope(clock = testClock(START))
    val backing = QueryStore.inMemory(Fetched(listOf("a"), START))
    val diskFull = IllegalStateException("Disk full")
    val failingWrites =
      QueryStore.of(backing.data, clear = backing::clear) { _, _ -> throw diskFull }
    val store =
      data.mutableStore<List<String>, Add>(
        name = "tags",
        base = failingWrites,
        apply = ::addOnce,
        send = { add, id -> send(add, id) },
      )

    // The server took the change, so its handle succeeds and its retries are not spent
    store.mutate(Add("b")).awaitSucceeded()

    assertThat(send.sent).containsExactly(Add("b"))
    assertThat(data.actionFailures.assertSingle<IllegalStateException>()).isSameInstanceAs(diskFull)
    data.fetchFailures.assertNone()
  }
}

/** Outbox stores whose inserts wait while held, like a database that is slow to write. */
private class HoldingOutboxStores : OutboxStores {
  private val delegate = InMemoryOutboxStores()
  private val held = MutableStateFlow(false)

  fun hold() {
    held.value = true
  }

  fun release() {
    held.value = false
  }

  override fun <E> open(name: String, serializer: KSerializer<E>): OutboxStore<E> {
    val rows = delegate.open(name, serializer)
    return object : OutboxStore<E> by rows {
      override suspend fun insert(key: String, payload: E): EntryId {
        held.first { !it }
        return rows.insert(key, payload)
      }
    }
  }

  override suspend fun clearAll() = delegate.clearAll()
}

private val START = Instant.fromEpochSeconds(1_700_000_000)

/** Adds the tag once, as the store requires: a tag already there leaves the list as it is. */
private fun addOnce(current: List<String>?, add: MutableQueryStoreTest.Add): List<String> {
  val tags = current.orEmpty()
  return if (add.tag in tags) {
    tags
  } else {
    tags + add.tag
  }
}

/**
 * Appends the tag, which is not idempotent, so a change the overlay applied twice would show. The
 * store reports it once per set, which the tests that use it expect.
 */
private fun append(current: List<String>?, add: MutableQueryStoreTest.Add): List<String> =
  current.orEmpty() + add.tag
