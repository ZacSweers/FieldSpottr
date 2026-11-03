// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.messageContains
import dev.zacsweers.fieldspottr.data.core.impl.committedRecords
import dev.zacsweers.fieldspottr.data.core.testing.TestDataScope
import dev.zacsweers.fieldspottr.data.core.testing.TestFetch
import dev.zacsweers.fieldspottr.data.core.testing.TestSend
import dev.zacsweers.fieldspottr.data.core.testing.awaitFailed
import dev.zacsweers.fieldspottr.data.core.testing.awaitSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.testClock
import dev.zacsweers.fieldspottr.data.core.testing.testDataScope
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
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
      // The truth is the server as it was before the send, so the change rides on top
      assertThat(awaitItem()?.value).isEqualTo(listOf("a", "z", "b"))
      assertThat(truth.data.value?.value).isEqualTo(listOf("a", "z"))

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
