// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasMessage
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import assertk.assertions.messageContains
import dev.zacsweers.fieldspottr.data.core.impl.IDEMPOTENCE_CHECKS
import dev.zacsweers.fieldspottr.data.core.impl.committedRecords
import dev.zacsweers.fieldspottr.data.core.impl.committedValues
import dev.zacsweers.fieldspottr.data.core.impl.knownChanges
import dev.zacsweers.fieldspottr.data.core.testing.Add
import dev.zacsweers.fieldspottr.data.core.testing.LaggingOutboxStores
import dev.zacsweers.fieldspottr.data.core.testing.TestDataScope
import dev.zacsweers.fieldspottr.data.core.testing.TestDataScopeFactory
import dev.zacsweers.fieldspottr.data.core.testing.TestFailures
import dev.zacsweers.fieldspottr.data.core.testing.TestFetch
import dev.zacsweers.fieldspottr.data.core.testing.TestKeyedFetch
import dev.zacsweers.fieldspottr.data.core.testing.TestSend
import dev.zacsweers.fieldspottr.data.core.testing.addOnce
import dev.zacsweers.fieldspottr.data.core.testing.append
import dev.zacsweers.fieldspottr.data.core.testing.assertCancelled
import dev.zacsweers.fieldspottr.data.core.testing.assertPending
import dev.zacsweers.fieldspottr.data.core.testing.awaitFailed
import dev.zacsweers.fieldspottr.data.core.testing.awaitSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.clearFetched
import dev.zacsweers.fieldspottr.data.core.testing.insertNew
import dev.zacsweers.fieldspottr.data.core.testing.testClock
import dev.zacsweers.fieldspottr.data.core.testing.testDataScope
import dev.zacsweers.fieldspottr.data.core.testing.testDataScopeFactory
import dev.zacsweers.fieldspottr.data.core.testing.writeFetched
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer

class OptimisticQueryStoreTest {
  /** Sets a note's text, or deletes the note when [text] is null. */
  @Serializable data class SetText(val text: String?)

  /** Adds [delta] to a count once, with an [id] of its own that the server records. */
  @Serializable data class Increment(val id: String, val delta: Int)

  /** A count and the ids of the increments it holds, as the server reports them. */
  @Serializable data class Tally(val count: Int, val applied: Set<String> = emptySet())

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
  ): Pair<OptimisticQueryStore<List<String>, Add>, DataQuery<List<String>>> {
    val sender = Tags.sender { add, id ->
      sentIds += id
      send(add, id)
    }
    data = testDataScope(clock = testClock(START), online = online, senders = setOf(sender))
    truth = QueryStore.inMemory(stored)
    val store =
      data.optimisticStore<List<String>, Add>(
        Tags,
        base = truth,
        apply =
          if (idempotent) {
            ::addOnce
          } else {
            ::append
          },
      )
    val query = data.query(store, Freshness.staleAfter(1.hours), fetch = fetch)
    return store to query
  }

  /** Sends each change of [TagsByItem] through [send]. */
  private fun tagsByItemSender() = TagsByItem.sender { change, id -> send(change.mutation, id) }

  /** Passes when the only failure is the store's one report that [append] is not idempotent. */
  private fun assertOnlyIdempotencyReport() {
    data.fetchFailures.assertNone()
    assertThat(data.actionFailures.assertSingle<IllegalStateException>())
      .messageContains("not idempotent")
  }

  @Test
  fun `a change shows at once and stays once sent`() = runTest {
    val (store, query) = tags()
    query.dataAutoFetch.test {
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
    assertThat(query.data.first()).isEqualTo(Fetched(listOf("a", "b"), START))
    data.assertNoFailures()
  }

  @Test
  fun `a fetch that lands while a change waits keeps the change on top`() = runTest {
    send.hold(Add("b"))
    val (store, query) = tags(idempotent = false)
    query.dataAutoFetch.test {
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
    assertThat(query.data.first()?.value).isEqualTo(listOf("a", "z", "b"))
    assertOnlyIdempotencyReport()
  }

  @Test
  fun `a fetch that read the server before a send finished does not hide the change`() = runTest {
    val (store, query) = tags(idempotent = false)
    query.dataAutoFetch.test {
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
    assertThat(query.data.first()?.value).isEqualTo(listOf("a", "z", "b"))

    store.mutate(Add("c")).awaitSucceeded()

    // The commit folded onto the view, so the truth now holds both changes
    assertThat(truth.data.value?.value).isEqualTo(listOf("a", "z", "b", "c"))
    assertThat(query.data.first()?.value).isEqualTo(listOf("a", "z", "b", "c"))
    assertOnlyIdempotencyReport()
  }

  @Test
  fun `a rejected change drops out and comes back on retry`() = runTest {
    send.failPermanently()
    val (store, query) = tags()
    query.dataAutoFetch.test {
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
    query.dataAutoFetch.test {
      assertThat(awaitItem()?.value).isEqualTo(listOf("a"))
      val handle = store.mutate(Add("b"))
      assertThat(awaitItem()?.value).isEqualTo(listOf("a", "b"))
      store.discard(store.pending.first().single().id).awaitSucceeded()
      assertThat(awaitItem()?.value).isEqualTo(listOf("a"))
      assertThat(handle.state.value).isEqualTo(OperationState.Cancelled)
    }
    data.online.value = true
    // The reconnect watcher drains whatever is left before the check
    runCurrent()
    assertThat(send.sent).isEmpty()
    assertThat(store.pending.first()).isEmpty()
  }

  @Test
  fun `clear drops the waiting changes with the truth`() = runTest {
    val (store, query) = tags(online = false)
    query.dataAutoFetch.test {
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
    // The reconnect watcher drains whatever is left before the check
    runCurrent()
    assertThat(send.sent).isEmpty()
  }

  @Test
  fun `changes made offline wait and send in order on reconnect`() = runTest {
    val (store, query) = tags(online = false, idempotent = false)
    query.dataAutoFetch.test {
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
    assertThat(query.data.first()?.value).isEqualTo(listOf("a", "b", "c"))
    assertOnlyIdempotencyReport()
  }

  @Test
  fun `a value exchange returns becomes fresh truth`() = runTest {
    data =
      testDataScope(
        clock = testClock(START),
        senders =
          setOf(
            // The server normalizes what it stores
            ReplyingTags.exchangingSender { add, _ -> listOf("a", add.tag.uppercase()) }
          ),
      )
    val store =
      data.optimisticStore(
        ReplyingTags,
        base = QueryStore.inMemory(Fetched(listOf("a"), START - 1.hours)),
        apply = ::addOnce,
      )
    val query = data.query(store, Freshness.staleAfter(2.hours), fetch = fetch)
    query.dataAutoFetch.test {
      assertThat(awaitItem()?.value).isEqualTo(listOf("a"))
      store.mutate(Add("b")).awaitSucceeded()
      assertThat(awaitItem()?.value).isEqualTo(listOf("a", "b"))
      assertThat(awaitItem()).isEqualTo(Fetched(listOf("a", "B"), START))
      expectNoEvents()
    }
  }

  @Test
  fun `a keyed store builds one base per key and commits through it`() = runTest {
    data = testDataScope(clock = testClock(START), senders = setOf(tagsByItemSender()))
    val built = mutableListOf<String>()
    val stores =
      data.optimisticStores<String, List<String>, Add>(
        TagsByItem,
        // Each instance keeps state of its own, like a store that caches what it loaded
        base = { key ->
          built += key
          QueryStore.inMemory(Fetched(listOf(key), START))
        },
        apply = ::addOnce,
      )
    val queries =
      data.keyed(store = stores, freshness = Freshness.staleAfter(1.hours)) { key -> listOf(key) }

    queries.dataAutoFetch("x").test {
      assertThat(awaitItem()?.value).isEqualTo(listOf("x"))
      stores.mutate("x", Add("b")).awaitSucceeded()
      // The commit wrote the instance the view reads, so the change stays once the row leaves
      assertThat(awaitItem()?.value).isEqualTo(listOf("x", "b"))
      expectNoEvents()
    }
    assertThat(built).containsExactly("x")
  }

  @Test
  fun `a fetch from after a send leaves the change's record without a value until it expires`() =
    runTest {
      data = testDataScope(clock = testClock(START), senders = setOf(tagsByItemSender()))
      val stores =
        data.optimisticStores<String, List<String>, Add>(
          TagsByItem,
          base = QueryStore.inMemoryByKey(),
          apply = ::addOnce,
        )
      val queries =
        data.keyed(store = stores, freshness = Freshness.staleAfter(1.hours)) { key -> listOf(key) }
      queries.dataAutoFetch("x").first { it != null }

      stores.mutate("x", Add("b")).awaitSucceeded()
      // Until a fetch from after the send confirms the change, its record stays
      assertThat(stores.committedRecords).isEqualTo(1)

      data.clock.shift(1.minutes)
      queries.requestFetch("x", force = true).awaitSucceeded()
      // A reader of the base that runs late may still need the change, but no longer its value
      assertThat(stores.committedRecords).isEqualTo(1)
      assertThat(stores.committedValues).isEqualTo(0)

      data.clock.shift(10.minutes)
      queries.requestFetch("x", force = true).awaitSucceeded()
      assertThat(stores.committedRecords).isEqualTo(0)
    }

  @Test
  fun `a key keeps one value on record however many changes it sends`() = runTest {
    data = testDataScope(clock = testClock(START), senders = setOf(tagsByItemSender()))
    val stores =
      data.optimisticStores<String, List<String>, Add>(
        TagsByItem,
        base = QueryStore.inMemoryByKey(),
        apply = ::addOnce,
      )
    val queries =
      data.keyed(store = stores, freshness = Freshness.staleAfter(1.hours)) { _ -> fetch() }
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
  fun `a key keeps one server answer on record however many exchanges it sends`() = runTest {
    // The server stores tags in upper case, so its answer differs from what apply builds
    val server = mutableListOf("a", "z")
    val sender = ReplyingTagsByItem.exchangingSender { change, _ ->
      server += change.mutation.tag.uppercase()
      server.toList()
    }
    data = testDataScope(clock = testClock(START), senders = setOf(sender))
    val stores =
      data.optimisticStores<String, List<String>, Add>(
        ReplyingTagsByItem,
        base = QueryStore.inMemoryByKey(),
        apply = ::addOnce,
      )
    val queries =
      data.keyed(store = stores, freshness = Freshness.staleAfter(1.hours)) { _ -> fetch() }
    queries.requestFetch("x", force = false).awaitSucceeded()
    // A refresh that reads the server before the changes reach it, and lands after them. It starts
    // at the same instant as the sends, so its write lands and the records put the answers back.
    fetch.respond = { listOf("a", "z") }
    fetch.hold()
    val refresh = queries.requestFetch("x", force = true)
    runCurrent()

    for (tag in listOf("b", "c", "d")) {
      stores.mutate("x", Add(tag)).awaitSucceeded()
    }
    // The newest answer holds every change before it, so only it keeps a copy
    assertThat(stores.committedRecords).isEqualTo(3)
    assertThat(stores.committedValues).isEqualTo(1)

    fetch.release()
    refresh.awaitSucceeded()
    assertThat(stores("x").data.first()?.value).isEqualTo(listOf("a", "z", "B", "C", "D"))
    data.assertNoFailures()
  }

  @Test
  fun `a send after an exchange keeps the server answer on record`() = runTest {
    // The server answers only the first change, with its tag in upper case
    val sender = ReplyingTagsByItem.deliveringSender { change, _ ->
      if (change.mutation.tag == "b") {
        Delivery.Replaced(listOf("a", "z", "B"))
      } else {
        Delivery.Acknowledged
      }
    }
    data = testDataScope(clock = testClock(START), senders = setOf(sender))
    val stores =
      data.optimisticStores(
        ReplyingTagsByItem,
        base = QueryStore.inMemoryByKey<String, List<String>>(),
        keySerializer = String.serializer(),
        apply = ::addOnce,
      )
    val queries =
      data.keyed(store = stores, freshness = Freshness.staleAfter(1.hours)) { _ -> fetch() }
    queries.requestFetch("x", force = false).awaitSucceeded()
    fetch.respond = { listOf("a", "z") }
    fetch.hold()
    val refresh = queries.requestFetch("x", force = true)
    runCurrent()

    for (tag in listOf("b", "c", "d")) {
      stores.mutate("x", Add(tag)).awaitSucceeded()
    }
    // A refresh that lacks the answer gets it back with c and d applied on top, so the answer
    // keeps its copy beside the newest record's
    assertThat(stores.committedRecords).isEqualTo(3)
    assertThat(stores.committedValues).isEqualTo(2)

    fetch.release()
    refresh.awaitSucceeded()
    assertThat(stores("x").data.first()?.value).isEqualTo(listOf("a", "z", "B", "c", "d"))
    data.assertNoFailures()
  }

  @Test
  fun `a record that no fetch confirms is dropped minutes after its send`() = runTest {
    data = testDataScope(clock = testClock(START), senders = setOf(tagsByItemSender()))
    val stores =
      data.optimisticStores<String, List<String>, Add>(
        TagsByItem,
        base = QueryStore.inMemoryByKey(),
        apply = ::addOnce,
      )
    val queries =
      data.keyed(store = stores, freshness = Freshness.staleAfter(1.hours)) { key -> listOf(key) }
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
    data = testDataScope(clock = testClock(START), senders = setOf(tagsByItemSender()))
    val base = QueryStore.inMemoryByKey<String, List<String>>()
    val stores =
      data.optimisticStores<String, List<String>, Add>(
        TagsByItem,
        base = base,
        apply = ::addOnce,
      )
    val fetch = TestKeyedFetch<String, List<String>> { key -> listOf(key) }
    val queries =
      data.keyed(store = stores, freshness = Freshness.staleAfter(1.hours), fetch = fetch)
    queries.requestFetch("x", force = false).awaitSucceeded()
    send.hold(Add("b"))

    queries.dataAutoFetch("x").test {
      assertThat(awaitItem()?.value).isEqualTo(listOf("x"))
      stores.mutate("x", Add("b"))
      assertThat(awaitItem()?.value).isEqualTo(listOf("x", "b"))

      // The in-memory store never evicts a collected key, so another writer removes x's truth,
      // which
      // leaves only the waiting change
      base("x").clear()
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
    data = testDataScope(clock = testClock(START), senders = setOf(tagsByItemSender()))
    val stores =
      data.optimisticStores<String, List<String>, Add>(
        TagsByItem,
        base = QueryStore.inMemoryByKey(),
        apply = ::addOnce,
      )
    val queries =
      data.keyed(store = stores, freshness = Freshness.staleAfter(1.hours)) { key -> listOf(key) }

    queries.dataAutoFetch("x").test {
      assertThat(awaitItem()).isNull()
      assertThat(awaitItem()?.value).isEqualTo(listOf("x"))
      stores.mutate("x", Add("b")).awaitSucceeded()
      assertThat(awaitItem()?.value).isEqualTo(listOf("x", "b"))
      expectNoEvents()
    }
    assertThat(queries.dataAutoFetch("y").first { it != null }?.value).isEqualTo(listOf("y"))
    assertThat(stores.pendingCount.first()).isEqualTo(0)
    assertThat(send.sent).containsExactly(Add("b"))
    data.assertNoFailures()
  }

  @Test
  fun `a fetch that read the server after the change lands during the send without doubling it`() =
    runTest {
      send.hold(Add("b"))
      val (store, query) = tags()
      query.dataAutoFetch.test {
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
      query.dataAutoFetch.test {
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
    val first = store.mutate(Add("b"))
    first.awaitSucceeded()
    store.mutate(Add("c")).awaitSucceeded()

    assertThat(query.data.first()?.value).isEqualTo(listOf("a", "b", "c"))
    assertOnlyIdempotencyReport()
    // The first commit told the owner which change to look at, by its row id
    assertThat(data.actionFailures.all.single()).messageContains(first.id.value)
  }

  @Test
  fun `a change sent again after a restart leaves a truth that already holds it alone`() = runTest {
    val scopes = testDataScopeFactory(clock = testClock(START))
    // What a process that died between the truth write and the row's status leaves behind: the
    // change in the truth, and its row still Sending
    truth = QueryStore.inMemory(Fetched(listOf("a", "b"), START))
    val rows = scopes.outboxStore<Add>("tags")
    rows.insertNew(key = "", payload = Add("b"))
    val row = rows.entries().first().single()
    rows.update(row.copy(status = OutboxStatus.Sending), expected = OutboxStatus.Queued)

    data =
      scopes.create(
        backgroundScope,
        setOf(
          Tags.sender { add, id ->
            sentIds += id
            send(add, id)
          }
        ),
      )
    val store = data.optimisticStore(Tags, base = truth, apply = ::addOnce)
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
    assertThat(query.dataAutoFetch.first()?.value).isEqualTo(listOf("a", "b"))
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
          online = MutableStateFlow(false),
          stores = inserts,
          clock = testClock(START),
        )
        .create(backgroundScope, setOf(Tags.sender { _, _ -> }))
    truth = QueryStore.inMemory(Fetched(listOf("old"), START))
    val store = scope.optimisticStore(Tags, base = truth, apply = ::addOnce)
    fetch.respond = { listOf("new") }
    // Never stale, so collecting the seeded value starts no fetch of its own
    val query = scope.query(store, Freshness.NeverStale, fetch = fetch)
    val seen = mutableListOf<List<String>?>()
    backgroundScope.launch { query.dataAutoFetch.collect { seen += it?.value } }
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
    // The changes show before their rows are stored, and the fetched value waits for the rows
    assertThat(seen.last()).isEqualTo(listOf("old", "first", "second"))
    assertThat(seen.none { "new" in it.orEmpty() }).isTrue()

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
    data =
      testDataScope(
        clock = testClock(START),
        senders = setOf(ReplyingTags.exchangingSender { _, _ -> listOf("confirmed") }),
      )
    truth = QueryStore.inMemory(Fetched(listOf("a"), START - 2.hours))
    val gate = CompletableDeferred<Unit>()
    // A base whose write of the fetched value stalls, like a slow file
    val base =
      QueryStore.of(
        truth.data,
        clear = truth::clearFetched,
        invalidate = { truth.invalidate() },
        write = { fetched, keepsStored ->
          if (fetched.value == listOf("old")) {
            gate.await()
          }
          truth.writeFetched(fetched, keepsStored)
        },
      )
    val store = data.optimisticStore(ReplyingTags, base = base, apply = ::addOnce)
    fetch.respond = { listOf("old") }
    val query = data.query(store, Freshness.staleAfter(1.hours), fetch = fetch)

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
    assertThat(query.data.first()?.value).isEqualTo(listOf("confirmed"))
    data.assertNoFailures()
  }

  @Test
  fun `an older truth shows a server answer as that answer`() = runTest {
    data =
      testDataScope(
        clock = testClock(START),
        senders =
          setOf(
            // The server normalizes what it stores
            ReplyingTags.exchangingSender { add, _ -> listOf("a", add.tag.uppercase()) }
          ),
      )
    truth = QueryStore.inMemory(Fetched(listOf("a"), START - 1.hours))
    val store = data.optimisticStore(ReplyingTags, base = truth, apply = ::addOnce)
    val query = data.query(store, Freshness.staleAfter(2.hours), fetch = fetch)
    store.mutate(Add("b")).awaitSucceeded()
    assertThat(query.data.first()).isEqualTo(Fetched(listOf("a", "B"), START))

    // A fetch from before the send that lands through the store is dropped
    store.write(listOf("a"), START - 1.minutes)
    assertThat(truth.data.value).isEqualTo(Fetched(listOf("a", "B"), START))
    assertThat(query.data.first()?.value).isEqualTo(listOf("a", "B"))

    // One that reaches the truth itself, as the store holds it between a commit's record and its
    // write, shows the answer put back, which applying the change again could not rebuild
    truth.write(listOf("a"), START - 1.minutes)
    assertThat(query.data.first()?.value).isEqualTo(listOf("a", "B"))
    data.assertNoFailures()
  }

  @Test
  fun `clear drops a change left sending by an owner that stopped`() = runTest {
    val scopes = testDataScopeFactory(clock = testClock(START))
    val owner =
      CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext.job))
    truth = QueryStore.inMemory(Fetched(listOf("a"), START))
    fun build(scope: CoroutineScope) =
      scopes
        .create(scope, setOf(Tags.sender(send = send)))
        .optimisticStore(Tags, base = truth, apply = ::addOnce)
    val before = build(owner)
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
    val after = build(backgroundScope)
    runCurrent()
    assertThat(after.data.first()).isNull()
    assertThat(send.attempts).containsExactly(Add("b"))
    scopes.assertNoFailures()
  }

  @Test
  fun `an optimistic store rebuilt for the same name and types opens the same rows`() = runTest {
    val scopes = testDataScopeFactory(clock = testClock(START), online = false)
    val owner =
      CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext.job))
    // Each build opens the name with a new key, and so a new KeyedMutation serializer object
    fun build(scope: CoroutineScope): OptimisticQueryStores<Item, List<String>, Add> {
      val key = itemTags()
      return scopes
        .create(scope, setOf(key.sender { change, id -> send(change.mutation, id) }))
        .optimisticStores<Item, List<String>, Add>(
          key,
          base = QueryStore.inMemoryByKey(),
          apply = ::addOnce,
        )
    }
    build(owner).mutate(Item("x"), Add("b")).stored.awaitSucceeded()
    owner.cancel()
    owner.coroutineContext.job.join()

    val after = build(backgroundScope)

    assertThat(after.pending(Item("x")).first { it.isNotEmpty() }.map { it.mutation })
      .containsExactly(Add("b"))
    scopes.assertNoFailures()
  }

  /**
   * Two edits of one note wait offline, and the store reopens over their rows, so the new set knows
   * neither change. A retry of the first row puts a provisional mark on it, and the set learns of
   * that change. The view still applies the rows in queue order, so the newer edit stays on top.
   */
  @Test
  fun `a retried edit keeps its queue position after a reopen`() = runTest {
    val scopes = testDataScopeFactory(clock = testClock(START), online = false)
    val owner =
      CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext.job))
    val note = QueryStore.inMemory(Fetched("draft", START))
    fun build(scope: CoroutineScope) =
      scopes
        .create(scope, setOf(Note.sender(send = { _, _ -> })))
        .optimisticStore<String, SetText>(Note, base = note, apply = { _, edit -> edit.text })
    val before = build(owner)
    before.mutate(SetText("first")).stored.awaitSucceeded()
    before.mutate(SetText("second")).stored.awaitSucceeded()
    owner.cancel()
    owner.coroutineContext.job.join()

    val after = build(backgroundScope)
    after.data.test {
      assertThat(awaitItem()?.value).isEqualTo("second")
      val first = after.pending.first().first()
      assertThat(first.mutation).isEqualTo(SetText("first"))
      after.retry(first.id)
      runCurrent()
      expectNoEvents()

      // Both rows send in order, and the view never leaves the newer edit
      scopes.online.value = true
      after.pending.first { it.isEmpty() }
      runCurrent()
      expectNoEvents()
    }
    assertThat(note.data.value?.value).isEqualTo("second")
    scopes.assertNoFailures()
  }

  /**
   * Two edits wait offline in one scope. A scope from another factory over the same rows discards
   * the second, so the first stays the key's head, then clears the store, which discards the first.
   */
  @Test
  fun `changes another writer discards and clears settle their handles cancelled`() = runTest {
    val scopes = testDataScopeFactory(clock = testClock(START), online = false)
    val others = testDataScopeFactory(online = false, stores = scopes.outboxStores)
    val note = QueryStore.inMemory(Fetched("draft", START))
    fun build(factory: TestDataScopeFactory) =
      factory
        .create(backgroundScope, setOf(Note.sender(send = { _, _ -> })))
        .optimisticStore<String, SetText>(Note, base = note, apply = { _, edit -> edit.text })
    val mine = build(scopes)
    val first = mine.mutate(SetText("first"))
    val second = mine.mutate(SetText("second"))
    second.stored.awaitSucceeded()
    val other = build(others)

    other.discard(second.id).awaitSucceeded()
    runCurrent()
    second.assertCancelled()
    first.assertPending()

    other.clear()
    runCurrent()
    first.assertCancelled()
    scopes.assertNoFailures()
    others.assertNoFailures()
  }

  @Test
  fun `an invalidate during a commit still leaves the truth stale`() = runTest {
    data = testDataScope(clock = testClock(START), senders = setOf(Tags.sender(send = send)))
    val state = MutableStateFlow<Fetched<List<String>>?>(Fetched(listOf("a"), START))
    val commitWriting = CompletableDeferred<Unit>()
    val finishCommit = CompletableDeferred<Unit>()
    val base =
      QueryStore.of(
        state,
        clear = state::clearFetched,
        invalidate = { state.update { it?.copy(invalidated = true) } },
        write = { fetched, keepsStored ->
          // Pauses the commit's write, which comes after its read of the truth
          if (commitWriting.complete(Unit)) {
            finishCommit.await()
          }
          state.writeFetched(fetched, keepsStored)
        },
      )
    val store = data.optimisticStore(Tags, base = base, apply = ::addOnce)

    val handle = store.mutate(Add("b"))
    commitWriting.await()
    val invalidated = async { store.invalidate() }
    runCurrent()
    finishCommit.complete(Unit)
    invalidated.await()
    handle.awaitSucceeded()

    assertThat(state.value).isEqualTo(Fetched(listOf("a", "b"), START, invalidated = true))
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
        .create(backgroundScope, setOf(Tags.sender(send = send)))
    truth = QueryStore.inMemory(Fetched(listOf("a"), START))
    val store = scope.optimisticStore(Tags, base = truth, apply = ::addOnce)
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
    data = testDataScope(clock = testClock(START), senders = setOf(Tags.sender(send = send)))
    val backing = QueryStore.inMemory(Fetched(listOf("a"), START))
    val diskFull = IllegalStateException("Disk full")
    val failingWrites =
      QueryStore.of(backing.data, clear = backing::clearFetched, write = { _, _ -> throw diskFull })
    val store = data.optimisticStore(Tags, base = failingWrites, apply = ::addOnce)

    // The server took the change, so its handle succeeds and its retries are not spent
    store.mutate(Add("b")).awaitSucceeded()

    assertThat(send.sent).containsExactly(Add("b"))
    assertThat(data.actionFailures.assertSingle<IllegalStateException>()).isSameInstanceAs(diskFull)
    data.fetchFailures.assertNone()
  }

  @Test
  fun `a counter refreshed while its exchange runs shows the change twice until the answer`() =
    runTest {
      val server = CounterServer(answer = CompletableDeferred())
      data = testDataScope(clock = testClock(START), senders = setOf(counterSender(server)))
      val store = data.counterStore(QueryStore.inMemory(Fetched(0, START)))
      val query = data.query(store, Freshness.staleAfter(1.hours)) { server.count }

      query.dataAutoFetch.test {
        assertThat(awaitItem()?.value).isEqualTo(0)
        val handle = store.mutate(1)
        assertThat(awaitItem()?.value).isEqualTo(1)
        store.pending.first { it.singleOrNull()?.status == OutboxStatus.Sending }

        // The server took the change and holds its answer. A refresh reads its count of 1, and the
        // store cannot tell that the count holds the waiting change, so it applies it on top.
        query.requestFetch(force = true).awaitSucceeded()
        assertThat(awaitItem()?.value).isEqualTo(2)

        server.answer.complete(Unit)
        handle.awaitSucceeded()
        assertThat(awaitItem()?.value).isEqualTo(1)
        cancelAndIgnoreRemainingEvents()
      }
      assertThat(store.data.first()?.value).isEqualTo(1)
      assertThat(server.count).isEqualTo(1)
      data.assertNoFailures()
    }

  @Test
  fun `a counter left sending at a restart shows the change twice until the resend answers`() =
    runTest {
      val scopes = testDataScopeFactory(clock = testClock(START), online = false)
      // What an owner that stopped after it stored the server's answer leaves behind: the count
      // holds the change, and its row still says Sending
      val rows = scopes.outboxStore<Int>("count")
      rows.insertNew(key = "", payload = 1)
      val row = rows.entries().first().single()
      rows.update(row.copy(status = OutboxStatus.Sending), expected = OutboxStatus.Queued)
      val server = CounterServer(count = 1, applied = setOf(row.id))

      data = scopes.create(backgroundScope, setOf(counterSender(server)))
      val store = data.counterStore(QueryStore.inMemory(Fetched(1, START)))
      assertThat(store.data.first()?.value).isEqualTo(2)

      // The resend carries the row's id, so the server answers without counting the change again
      data.online.value = true
      store.pending.first { it.isEmpty() }
      assertThat(store.data.first()?.value).isEqualTo(1)
      assertThat(server.count).isEqualTo(1)
      data.assertNoFailures()
    }

  @Test
  fun `a tally stays exact through a refresh before the server takes the change`() = runTest {
    val server = TallyServer(accept = CompletableDeferred())
    data = testDataScope(clock = testClock(START), senders = setOf(tallySender(server)))
    val store = data.tallyStore(QueryStore.inMemory(Fetched(server.read(), START)))
    val query = data.query(store, Freshness.staleAfter(1.hours)) { server.read() }

    val handle = store.mutate(Increment("e1", 1))
    store.pending.first { it.singleOrNull()?.status == OutboxStatus.Sending }
    assertThat(store.data.first()?.value?.count).isEqualTo(1)

    // The refresh reads a count without the change, so the change goes on top
    query.requestFetch(force = true).awaitSucceeded()
    assertThat(store.data.first()?.value?.count).isEqualTo(1)

    server.accept.complete(Unit)
    handle.awaitSucceeded()
    assertThat(store.data.first()?.value).isEqualTo(Tally(1, setOf("e1")))
    assertThat(server.count).isEqualTo(1)
    data.assertNoFailures()
  }

  @Test
  fun `a tally stays exact through a refresh after the server takes the change`() = runTest {
    val server = TallyServer(answer = CompletableDeferred())
    data = testDataScope(clock = testClock(START), senders = setOf(tallySender(server)))
    val store = data.tallyStore(QueryStore.inMemory(Fetched(server.read(), START)))
    val query = data.query(store, Freshness.staleAfter(1.hours)) { server.read() }

    val handle = store.mutate(Increment("e1", 1))
    store.pending.first { it.singleOrNull()?.status == OutboxStatus.Sending }

    // The server counted the change and has not answered. The refresh reads a count that lists
    // the change, so apply skips it, where a plain counter would show 2.
    query.requestFetch(force = true).awaitSucceeded()
    assertThat(store.data.first()?.value).isEqualTo(Tally(1, setOf("e1")))

    server.answer.complete(Unit)
    handle.awaitSucceeded()
    assertThat(store.data.first()?.value).isEqualTo(Tally(1, setOf("e1")))
    assertThat(server.count).isEqualTo(1)
    data.assertNoFailures()
  }

  @Test
  fun `a tally stays exact when it reopens offline and sends the change again`() = runTest {
    val scopes = testDataScopeFactory(clock = testClock(START))
    val owner =
      CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext.job))
    val server = TallyServer(answer = CompletableDeferred())
    val base = QueryStore.inMemory(Fetched(server.read(), START))
    val before = scopes.create(owner, setOf(tallySender(server))).tallyStore(base)

    // The server counts the change, and the owner stops before the answer arrives
    before.mutate(Increment("e1", 1))
    before.pending.first { it.singleOrNull()?.status == OutboxStatus.Sending }
    assertThat(server.count).isEqualTo(1)
    owner.cancel()
    owner.coroutineContext.job.join()

    // Reopened offline, the change shows once on top of the truth that lacks it
    scopes.online.value = false
    val after = scopes.create(backgroundScope, setOf(tallySender(server))).tallyStore(base)
    assertThat(after.data.first()?.value).isEqualTo(Tally(1, setOf("e1")))

    // The change sends again with its row id, so the server answers without counting it twice
    server.answer.complete(Unit)
    scopes.online.value = true
    after.pending.first { it.isEmpty() }
    assertThat(after.data.first()?.value).isEqualTo(Tally(1, setOf("e1")))
    assertThat(server.count).isEqualTo(1)
    scopes.assertNoFailures()
  }

  @Test
  fun `a fetch that folds in a delete keeps a row another writer recreated meanwhile`() = runTest {
    data = testDataScope(clock = testClock(START), senders = setOf(Note.sender { _, _ -> }))
    // One row that another writer shares, like a table a second query also fills
    val row = MutableStateFlow<Fetched<String>?>(Fetched("draft", START))
    val removing = CompletableDeferred<Unit>()
    val finishRemove = CompletableDeferred<Unit>()
    var removals = 0
    val base =
      QueryStore.of(
        row,
        clear = { keepsStored ->
          // The commit removes the row first, and the fetch that folds the delete in removes it
          // again
          removals++
          if (removals == 2) {
            removing.complete(Unit)
            finishRemove.await()
          }
          row.clearFetched(keepsStored)
        },
        write = { fetched, keepsStored ->
          row.writeFetched(fetched, keepsStored)
        },
      )
    val store =
      data.optimisticStore<String, SetText>(Note, base = base, apply = { _, edit -> edit.text })
    val fetch = TestFetch { "draft" }
    val query = data.query(store, Freshness.staleAfter(1.hours), fetch = fetch)

    // The refresh reads the server before the delete reaches it
    fetch.hold()
    val refresh = query.requestFetch(force = true)
    runCurrent()
    advanceTimeBy(1.seconds)
    store.mutate(SetText(null)).awaitSucceeded()
    assertThat(row.value).isNull()

    fetch.release()
    removing.await()
    advanceTimeBy(1.seconds)
    row.value = Fetched("recreated", START + 2.seconds)
    finishRemove.complete(Unit)
    refresh.awaitSucceeded()

    assertThat(row.value).isEqualTo(Fetched("recreated", START + 2.seconds))
    assertThat(store.data.first()?.value).isEqualTo("recreated")
    data.assertNoFailures()
  }

  @Test
  fun `an exchange answer keeps a newer value another writer stored during its commit`() = runTest {
    data =
      testDataScope(
        clock = testClock(START),
        senders =
          setOf(
            // The server normalizes what it stores
            ReplyingNote.exchangingSender { edit, _ -> checkNotNull(edit.text).uppercase() }
          ),
      )
    val row = MutableStateFlow<Fetched<String>?>(Fetched("draft", START))
    val commitWriting = CompletableDeferred<Unit>()
    val finishCommit = CompletableDeferred<Unit>()
    val base =
      QueryStore.of(
        row,
        clear = row::clearFetched,
        write = { fetched, keepsStored ->
          // Pauses the commit's write, which comes after its read of the truth
          if (fetched.value == "SAVED") {
            commitWriting.complete(Unit)
            finishCommit.await()
          }
          row.writeFetched(fetched, keepsStored)
        },
      )
    val store =
      data.optimisticStore<String, SetText>(
        ReplyingNote,
        base = base,
        apply = { _, edit -> edit.text },
      )

    advanceTimeBy(1.seconds)
    val handle = store.mutate(SetText("saved"))
    commitWriting.await()
    advanceTimeBy(1.seconds)
    row.value = Fetched("newer", START + 2.seconds)
    finishCommit.complete(Unit)
    handle.awaitSucceeded()

    assertThat(row.value).isEqualTo(Fetched("newer", START + 2.seconds))
    assertThat(store.data.first()?.value).isEqualTo("newer")
    data.assertNoFailures()
  }

  @Test
  fun `a write through the store still replaces a newer value`() = runTest {
    data = testDataScope(clock = testClock(START), senders = setOf(Tags.sender(send = send)))
    truth = QueryStore.inMemory(Fetched(listOf("a"), START + 1.hours))
    val store = data.optimisticStore(Tags, base = truth, apply = ::addOnce)

    store.write(listOf("seed"), START)
    assertThat(truth.data.value).isEqualTo(Fetched(listOf("seed"), START))
    data.assertNoFailures()
  }

  @Test
  fun `an apply that throws leaves its change out and data keeps flowing`() = runTest {
    data =
      testDataScope(
        clock = testClock(START),
        online = false,
        senders = setOf(Tags.sender(send = send)),
      )
    truth = QueryStore.inMemory(Fetched(listOf("a"), START))
    val boom = IllegalArgumentException("Cannot apply boom")
    val store =
      data.optimisticStore(
        Tags,
        base = truth,
        apply = { current, add ->
          if (add.tag == "boom") {
            throw boom
          }
          addOnce(current, add)
        },
      )
    val query = data.query(store, Freshness.staleAfter(1.hours), fetch = fetch)
    query.dataAutoFetch.test {
      assertThat(awaitItem()?.value).isEqualTo(listOf("a"))
      store.mutate(Add("boom"))
      store.pending.first { it.isNotEmpty() }
      store.mutate(Add("c"))
      assertThat(awaitItem()?.value).isEqualTo(listOf("a", "c"))
      store.pending.first { it.size == 2 }
      expectNoEvents()
    }
    // Once for the change, however many times the views applied it
    assertThat(data.actionFailures.assertSingle<IllegalStateException>().cause)
      .isSameInstanceAs(boom)
    data.fetchFailures.assertNone()
  }

  @Test
  fun `an apply that throws at commit leaves the truth stale and the send succeeds`() = runTest {
    data =
      testDataScope(
        clock = testClock(START),
        online = false,
        senders = setOf(Tags.sender(send = send)),
      )
    truth = QueryStore.inMemory(Fetched(listOf("a"), START))
    val broken = IllegalStateException("Broken apply")
    var breaks = false
    val store =
      data.optimisticStore(
        Tags,
        base = truth,
        apply = { current, add ->
          if (breaks) {
            throw broken
          }
          addOnce(current, add)
        },
      )
    val handle = store.mutate(Add("b"))
    assertThat(store.data.first()?.value).isEqualTo(listOf("a", "b"))

    breaks = true
    data.online.value = true
    handle.awaitSucceeded()

    // The server has the change, and the next fetch brings it
    assertThat(send.sent).containsExactly(Add("b"))
    assertThat(truth.data.value).isEqualTo(Fetched(listOf("a"), START, invalidated = true))
    assertThat(store.data.first()?.value).isEqualTo(listOf("a"))
    assertThat(store.pending.first()).isEmpty()
    // The view and the commit both met the throw, and it is reported once for the change
    assertThat(data.actionFailures.assertSingle<IllegalStateException>().cause)
      .isSameInstanceAs(broken)
  }

  @Test
  fun `the idempotence check runs on the last of the first commits of a set`() = runTest {
    val store = appendsDuplicates()
    // Each of these commits passes the check
    repeat(IDEMPOTENCE_CHECKS - 1) { store.mutate(Add("t$it")).awaitSucceeded() }
    data.actionFailures.assertNone()

    store.mutate(Add("dup")).awaitSucceeded()
    assertOnlyIdempotencyReport()
  }

  @Test
  fun `the idempotence check stops after the first commits of a set`() = runTest {
    val store = appendsDuplicates()
    repeat(IDEMPOTENCE_CHECKS) { store.mutate(Add("t$it")).awaitSucceeded() }

    // Not checked any more, so the apply that is not idempotent goes unreported
    store.mutate(Add("dup")).awaitSucceeded()
    assertThat(truth.data.value?.value?.last()).isEqualTo("dup")
    data.assertNoFailures()
  }

  /**
   * A tag store whose apply is idempotent for every tag but `dup`, which it appends again each
   * time, so only a commit of `dup` can fail the idempotence check.
   */
  private fun TestScope.appendsDuplicates(): OptimisticQueryStore<List<String>, Add> {
    data = testDataScope(clock = testClock(START), senders = setOf(Tags.sender(send = send)))
    truth = QueryStore.inMemory(Fetched(listOf("a"), START))
    return data.optimisticStore(
      Tags,
      base = truth,
      apply = { current, add ->
        if (add.tag == "dup") {
          append(current, add)
        } else {
          addOnce(current, add)
        }
      },
    )
  }

  @Test
  fun `a change shows before its row is stored and stays once stored`() = runTest {
    val inserts = HoldingOutboxStores()
    val failures = TestFailures()
    val scope =
      DataScope.Factory(
          onFailure = failures,
          online = MutableStateFlow(false),
          stores = inserts,
          clock = testClock(START),
        )
        .create(backgroundScope, setOf(Tags.sender { _, _ -> }))
    truth = QueryStore.inMemory(Fetched(listOf("a"), START))
    val store = scope.optimisticStore(Tags, base = truth, apply = ::append)
    store.data.test {
      assertThat(awaitItem()?.value).isEqualTo(listOf("a"))
      inserts.hold()
      val handle = store.mutate(Add("b"))
      // The slow insert has not stored the row, and the change shows already
      assertThat(awaitItem()?.value).isEqualTo(listOf("a", "b"))
      assertThat(handle.stored.state.value).isEqualTo(OperationState.Queued)
      assertThat(store.pending.first()).isEmpty()

      inserts.release()
      handle.stored.awaitSucceeded()
      store.pending.first { it.isNotEmpty() }
      // The stored row took over from the local change without a gap, and append, which is not
      // idempotent, shows that the change applied once
      expectNoEvents()
      assertThat(store.data.first()?.value).isEqualTo(listOf("a", "b"))
    }
    failures.assertNone()
  }

  @Test
  fun `a change whose row cannot be stored shows and then goes away`() = runTest {
    val diskFull = IllegalStateException("Disk full")
    val inserts = HoldingOutboxStores(failure = diskFull)
    val failures = TestFailures()
    val scope =
      DataScope.Factory(
          onFailure = failures,
          online = MutableStateFlow(true),
          stores = inserts,
          clock = testClock(START),
        )
        .create(backgroundScope, setOf(Tags.sender(send = send)))
    truth = QueryStore.inMemory(Fetched(listOf("a"), START))
    val store = scope.optimisticStore(Tags, base = truth, apply = ::addOnce)
    store.data.test {
      assertThat(awaitItem()?.value).isEqualTo(listOf("a"))
      inserts.hold()
      val handle = store.mutate(Add("b"))
      assertThat(awaitItem()?.value).isEqualTo(listOf("a", "b"))

      inserts.release()
      assertThat(handle.awaitFailed<IllegalStateException>()).hasMessage("Disk full")
      assertThat(handle.stored.await()).isInstanceOf<OperationState.Failed>()
      assertThat(awaitItem()?.value).isEqualTo(listOf("a"))
      expectNoEvents()
    }
    assertThat(send.attempts).isEmpty()
    assertThat(failures.assertSingle<IllegalStateException>()).hasMessage("Disk full")
  }

  @Test
  fun `keys order their rows by their serialized form`() = runTest {
    val itemTags = itemTags()
    data =
      testDataScope(
        clock = testClock(START),
        online = false,
        senders =
          setOf(
            itemTags.sender { change, id -> send(change.mutation, id) },
            TagsByName.sender { change, id -> send(change.mutation, id) },
            Tags.sender(send = send),
          ),
      )
    val stores =
      data.optimisticStores<Item, List<String>, Add>(
        itemTags,
        base = QueryStore.inMemoryByKey(),
        apply = ::addOnce,
      )
    // Equal keys whose toString differs share one order
    stores.mutate(Item("x"), Add("b")).stored.awaitSucceeded()
    stores.mutate(Item("x"), Add("c")).stored.awaitSucceeded()
    val strings =
      data.optimisticStores<String, List<String>, Add>(
        TagsByName,
        base = QueryStore.inMemoryByKey(),
        apply = ::addOnce,
      )
    strings.mutate("x", Add("b")).stored.awaitSucceeded()
    val single =
      data.optimisticStore(Tags, base = QueryStore.inMemory<List<String>>(null), apply = ::addOnce)
    single.mutate(Add("b")).stored.awaitSucceeded()

    assertThat(
        data.outboxStore<KeyedMutation<Item, Add>>("tagsByItem").entries().first().map { it.key }
      )
      .containsExactly("""{"id":"x"}""", """{"id":"x"}""")
    assertThat(
        data.outboxStore<KeyedMutation<String, Add>>("tagsByName").entries().first().map { it.key }
      )
      .containsExactly("\"x\"")
    // A single store's rows hold the change itself, all on one key
    assertThat(data.outboxStore<Add>("tags").entries().first().map { it.key }).containsExactly("")
    data.assertNoFailures()
  }

  @Test
  fun `a keyed change stores its line within its key's line`() = runTest {
    data =
      testDataScope(clock = testClock(START), online = false, senders = setOf(tagsByItemSender()))
    val stores =
      data.optimisticStores<String, List<String>, Add>(
        TagsByItem,
        base = QueryStore.inMemoryByKey(),
        apply = ::addOnce,
      )

    stores.mutate("x", Add("b"), Ordering.Keyed("y")).stored.awaitSucceeded()
    stores.mutate("x", Add("c")).stored.awaitSucceeded()

    assertThat(
        data.outboxStore<KeyedMutation<String, Add>>("tagsByItem").entries().first().map { it.key }
      )
      .containsExactly("\"x\"/y", "\"x\"")
    data.assertNoFailures()
  }

  @Test
  fun `a change on another line of one value sends and commits while a head backs off`() = runTest {
    send.failure = { add, _ -> IllegalStateException("Transient").takeIf { add == Add("b") } }
    val sender = Tags.sender(retry = RetryPolicy { 1.hours }, send = send)
    data = testDataScope(clock = testClock(START), senders = setOf(sender))
    truth = QueryStore.inMemory(Fetched(listOf("a"), START))
    val store = data.optimisticStore(Tags, base = truth, apply = ::addOnce)
    val seen = mutableListOf<List<String>?>()
    backgroundScope.launch { store.data.collect { seen += it?.value } }
    runCurrent()

    val withB = seen.size
    val b = store.mutate(Add("b"), Ordering.Keyed("x"))
    store.pending.first { changes -> changes.any { it.retryAt != null } }
    val withC = seen.size
    val c = store.mutate(Add("c"), Ordering.Keyed("y"))
    c.awaitSucceeded()
    runCurrent()

    b.assertPending()
    assertThat(send.sent).containsExactly(Add("c"))
    assertThat(truth.data.value?.value).isEqualTo(listOf("a", "c"))
    assertThat(seen.drop(withB).all { "b" in it.orEmpty() }).isTrue()
    assertThat(seen.drop(withC).all { "c" in it.orEmpty() }).isTrue()
    assertThat(seen.last()?.toSet()).isEqualTo(setOf("a", "b", "c"))
    data.assertNoFailures()
  }

  @Test
  fun `a commit that lands after another line's commit and an older fetch keeps both changes`() =
    runTest {
      data = testDataScope(clock = testClock(START), senders = setOf(Tags.sender(send = send)))
      truth = QueryStore.inMemory(Fetched(listOf("a"), START))
      var failNextWrite = false
      val base =
        QueryStore.of(
          truth.data,
          write = { fetched, keepsStored ->
            if (failNextWrite) {
              failNextWrite = false
              error("Disk full")
            }
            truth.writeFetched(fetched, keepsStored)
          },
          clear = truth::clearFetched,
        )
      val store = data.optimisticStore(Tags, base = base, apply = ::addOnce)
      val query = data.query(store, Freshness.staleAfter(1.hours), fetch = fetch)
      val seen = mutableListOf<List<String>?>()
      backgroundScope.launch { store.data.collect { seen += it?.value } }
      runCurrent()
      // The refresh reads the server before either send, and lands between the two commits
      fetch.respond = { listOf("a", "z") }
      fetch.hold()
      val refresh = query.requestFetch(force = true)
      advanceTimeBy(1.seconds)

      // The first line's commit fails once, so it lands after the second line's commit
      failNextWrite = true
      val withB = seen.size
      val b = store.mutate(Add("b"), Ordering.Keyed("x"))
      b.awaitSucceeded()
      assertThat(failNextWrite).isFalse()
      advanceTimeBy(100.milliseconds)
      val withC = seen.size
      store.mutate(Add("c"), Ordering.Keyed("y")).awaitSucceeded()
      // The second line's commit landed first, and took the first line's sent change along
      assertThat(truth.data.value?.value).isEqualTo(listOf("a", "b", "c"))
      assertOwed(b.id)
      fetch.release()
      refresh.awaitSucceeded()
      assertOwed(b.id)
      advanceTimeBy(2.seconds)
      runCurrent()

      assertThat(data.outboxStore<Add>("tags").entries().first()).isEmpty()
      assertThat(truth.data.value?.value?.toSet()).isEqualTo(setOf("a", "z", "b", "c"))
      assertThat(seen.drop(withB).all { "b" in it.orEmpty() }).isTrue()
      assertThat(seen.drop(withC).all { "c" in it.orEmpty() }).isTrue()
      assertThat(data.actionFailures.assertSingle<IllegalStateException>()).hasMessage("Disk full")
      data.fetchFailures.assertNone()
    }

  /** Passes when row [id] of [Tags] is sent and its commit has not landed. */
  private suspend fun assertOwed(id: EntryId) {
    val row = checkNotNull(data.outboxStore<Add>("tags").entry(id).first())
    assertThat(row.status).isEqualTo(OutboxStatus.Sent)
    assertThat(row.committed).isFalse()
  }

  @Test
  fun `an ordering an entry cannot take throws from mutate and leaves data as it was`() = runTest {
    val (store, _) = tags(online = false)
    val before = store.data.first()

    for (ordering in
      listOf(
        Ordering.Keyed("x", OnPrevious.Cancel),
        Ordering.Keyed("x", OnPrevious.Supersede),
        Ordering.Keyed(42),
      )) {
      assertFailsWith<IllegalArgumentException> { store.mutate(Add("b"), ordering) }
    }
    runCurrent()

    assertThat(store.data.first()).isEqualTo(before)
    assertThat(data.outboxStore<Add>("tags").entries().first()).isEmpty()
    data.assertNoFailures()
  }

  @Test
  fun `a refused ordering never shows its change to a live collector`() = runTest {
    data =
      testDataScope(
        eager = true,
        clock = testClock(START),
        senders = setOf(Tags.sender(send = send)),
      )
    truth = QueryStore.inMemory(Fetched(listOf("a"), START))
    val store = data.optimisticStore(Tags, base = truth, apply = ::addOnce)
    val seen = mutableListOf<Set<String>?>()
    backgroundScope.launch { store.data.collect { seen += it?.value?.toSet() } }
    runCurrent()

    for (ordering in
      listOf(
        Ordering.Keyed(42),
        Ordering.Keyed("x", OnPrevious.Cancel),
        Ordering.Keyed("x", OnPrevious.Supersede),
      )) {
      assertFailsWith<IllegalArgumentException> { store.mutate(Add("b"), ordering) }
    }
    runCurrent()

    assertThat(seen.filter { "b" in it.orEmpty() }).isEmpty()
    assertThat(send.attempts).isEmpty()
  }

  @Test
  fun `a refused reject key never shows its change to a live collector`() = runTest {
    data =
      testDataScope(
        eager = true,
        clock = testClock(START),
        senders = setOf(Tags.sender(send = send)),
      )
    truth = QueryStore.inMemory(Fetched(listOf("a"), START))
    val store = data.optimisticStore(Tags, base = truth, apply = ::addOnce)
    val seen = mutableListOf<Set<String>?>()
    backgroundScope.launch { store.data.collect { seen += it?.value?.toSet() } }
    runCurrent()

    assertFailsWith<IllegalArgumentException> {
      store.mutate(Add("b"), admission = Admission.Reject(42))
    }
    runCurrent()

    assertThat(seen.filter { "b" in it.orEmpty() }).isEmpty()
    assertThat(send.attempts).isEmpty()
  }

  @Test
  fun `a rejected change leaves data and pending as they were and fails its handle`() = runTest {
    val (store, _) = tags(online = false)
    store.mutate(Add("b"), admission = Admission.Reject("k")).stored.awaitSucceeded()
    runCurrent()
    val before = store.data.first()
    val pendingBefore = store.pending.first()

    val rejected = store.mutate(Add("c"), admission = Admission.Reject("k"))

    assertThat(rejected.awaitFailed<CommandRejectedException>().key).isEqualTo("k")
    runCurrent()
    assertThat(store.data.first()).isEqualTo(before)
    assertThat(store.pending.first()).isEqualTo(pendingBefore)
    data.assertNoFailures()
  }

  @Test
  fun `a sent change whose commit is owed frees its reject key`() = runTest {
    data = testDataScope(clock = testClock(START), senders = setOf(Tags.sender(send = send)))
    truth = QueryStore.inMemory(Fetched(listOf("a"), START))
    val base =
      QueryStore.of(truth.data, write = { _, _ -> error("Disk full") }, clear = truth::clearFetched)
    val store = data.optimisticStore(Tags, base = base, apply = ::addOnce)
    val sent = store.mutate(Add("b"), admission = Admission.Reject("k"))
    sent.awaitSucceeded()
    assertOwed(sent.id)

    store.mutate(Add("c"), admission = Admission.Reject("k")).stored.awaitSucceeded()

    val failures = data.actionFailures.take()
    assertThat(failures.map { it.message }.distinct()).containsExactly("Disk full")
    data.fetchFailures.assertNone()
  }

  @Test
  fun `a keyed change rejects only within its key and stores its key's reject key`() = runTest {
    data =
      testDataScope(clock = testClock(START), online = false, senders = setOf(tagsByItemSender()))
    val stores =
      data.optimisticStores<String, List<String>, Add>(
        TagsByItem,
        base = QueryStore.inMemoryByKey(),
        apply = ::addOnce,
      )

    stores.mutate("x", Add("b"), admission = Admission.Reject("k")).stored.awaitSucceeded()
    stores.mutate("y", Add("c"), admission = Admission.Reject("k")).stored.awaitSucceeded()

    assertThat(
        data.outboxStore<KeyedMutation<String, Add>>("tagsByItem").entries().first().map {
          it.rejectKey
        }
      )
      .containsExactly("\"x\"/k", "\"y\"/k")
    data.assertNoFailures()
  }

  /** Adds [by] to a count, which is not idempotent. */
  @Serializable data class Bump(val by: Int)

  /** A change with no equals of its own, as a plain serializable class has. */
  @Serializable class Op(val tag: String, val boom: Boolean)

  /**
   * The outbox's heads watcher sees the row once the store shows it, and drains it while the insert
   * has not returned. The commit then finds no local change with the row's id, so the record and
   * the local change both apply.
   */
  @Test
  fun `a change sent before its insert returns shows once`() = runTest {
    val failures = TestFailures()
    var server = 0
    val data =
      DataScope.Factory(
          onFailure = failures,
          online = MutableStateFlow(true),
          stores = LaggingOutboxStores(insertReturnsAfter = 1.seconds),
          clock = testClock(START),
        )
        .create(
          backgroundScope,
          setOf(
            Bumps.exchangingSender { bump, _ ->
              server += bump.by
              server
            }
          ),
        )
    val count =
      data.optimisticStore<Int, Bump>(
        Bumps,
        base = QueryStore.inMemory(Fetched(0, START)),
        apply = { current, bump -> (current ?: 0) + bump.by },
      )
    val seen = mutableListOf<Int?>()
    backgroundScope.launch { count.data.collect { seen += it?.value } }
    runCurrent()
    val handle = count.mutate(Bump(1))
    advanceTimeBy(500.milliseconds)
    runCurrent()
    // The server has the bump while the insert has not returned
    assertThat(server).isEqualTo(1)
    assertThat(handle.stored.state.value).isNotEqualTo(OperationState.Succeeded)

    advanceTimeBy(5.seconds)
    runCurrent()
    handle.awaitSucceeded()
    // One bump was made, so the view counts it, and no value may count two
    assertThat(seen.last()).isEqualTo(1)
    assertThat(seen.filter { it != null && it > 1 }).isEmpty()
    failures.assertNone()
  }

  /**
   * The rows list lags the store. The first change's insert returned, so its local change waits for
   * the rows to show it. The second change's insert starts, and the rows list that first shows the
   * first row arrives meanwhile. That row is the newest unshown row on the key, so it is held, and
   * its local change goes in the same update. The first change leaves the view until the second
   * insert returns.
   */
  @Test
  fun `an earlier change stays in view while a later insert runs`() = runTest {
    val failures = TestFailures()
    val data =
      DataScope.Factory(
          onFailure = failures,
          // Offline, so rows stay and only the local change logic decides the view
          online = MutableStateFlow(false),
          stores =
            LaggingOutboxStores(insertStartsAfter = 500.milliseconds, rowsLag = 100.milliseconds),
          clock = testClock(START),
        )
        .create(backgroundScope, setOf(Tags.sender { _, _ -> }))
    val tags =
      data.optimisticStore<List<String>, Add>(
        Tags,
        base = QueryStore.inMemory(Fetched(listOf("a"), START)),
        apply = ::addOnce,
      )
    val seen = mutableListOf<List<String>?>()
    backgroundScope.launch { tags.data.collect { seen += it?.value } }
    runCurrent()
    tags.mutate(Add("b"))
    tags.mutate(Add("c"))
    advanceTimeBy(5.seconds)
    runCurrent()
    // Once "b" showed, every later value holds it
    val firstB = seen.indexOfFirst { it != null && "b" in it }
    assertThat(seen.drop(firstB).filter { it == null || "b" !in it }).isEmpty()
  }

  /**
   * A fetch from after the send prunes the change's record, since the store reads its row as Sent.
   * The shared rows still list the row as Sending, so the view applies the change again on top of a
   * truth that holds it.
   */
  @Test
  fun `a pruned record never lets a lagging row apply again`() = runTest {
    val failures = TestFailures()
    val clock = testClock(START)
    var server = 0
    val data =
      DataScope.Factory(
          onFailure = failures,
          online = MutableStateFlow(true),
          stores = LaggingOutboxStores(rowsLag = 1.seconds),
          clock = clock,
        )
        .create(
          backgroundScope,
          setOf(
            Bumps.exchangingSender { bump, _ ->
              delay(2.seconds)
              server += bump.by
              server
            }
          ),
        )
    val count =
      data.optimisticStore<Int, Bump>(
        Bumps,
        base = QueryStore.inMemory(Fetched(0, START)),
        apply = { current, bump -> (current ?: 0) + bump.by },
      )
    val seen = mutableListOf<Int?>()
    backgroundScope.launch { count.data.collect { seen += it?.value } }
    advanceTimeBy(2.seconds)
    runCurrent()
    val handle = count.mutate(Bump(1))
    // The send ends at +2s, and the rows list shows that at +3s
    advanceTimeBy(2.seconds + 100.milliseconds)
    runCurrent()
    // Sound so far: the value counts the one change
    assertThat(seen.last()).isEqualTo(1)
    assertThat(seen.filter { it != null && it > 1 }).isEmpty()
    // A fetch from after the send, with the server's value
    count.write(server, clock.now())
    runCurrent()
    advanceTimeBy(5.seconds)
    runCurrent()
    handle.awaitSucceeded()
    assertThat(server).isEqualTo(1)
    // The view still counts the one change, and never two
    assertThat(seen.last()).isEqualTo(1)
    assertThat(seen.filter { it != null && it > 1 }).isEmpty()
    failures.assertNone()
  }

  /**
   * A durable store decodes a new payload object on each read. A mutation class without equals then
   * never matches the reported set, so one throwing change is reported on each row list, and the
   * set grows by one entry each time.
   */
  @Test
  fun `a throwing apply is reported once per change with a decoding store`() = runTest {
    val failures = TestFailures()
    val data =
      DataScope.Factory(
          onFailure = failures,
          online = MutableStateFlow(false),
          stores = LaggingOutboxStores(decodeEachRead = true),
          clock = testClock(START),
        )
        .create(backgroundScope, setOf(Ops.sender { _, _ -> }))
    val tags =
      data.optimisticStore<List<String>, Op>(
        Ops,
        base = QueryStore.inMemory(Fetched(listOf("a"), START)),
        apply = { current, op ->
          check(!op.boom) { "boom" }
          current.orEmpty() + op.tag
        },
      )
    backgroundScope.launch { tags.data.collect {} }
    runCurrent()
    tags.mutate(Op("x", boom = true))
    runCurrent()
    // Each later change lists the rows again, which decodes the throwing change again
    repeat(5) {
      tags.mutate(Op("ok$it", boom = false)).stored.await()
      runCurrent()
    }
    val reports = failures.take().filter { it.message.orEmpty().startsWith("apply threw") }
    assertThat(reports.size).isEqualTo(1)
  }

  /**
   * A change whose apply throws waits as a row, offline. Collectors come and go, which stops and
   * starts the shared subscription, and the set still knows the change, so it reports it once.
   */
  @Test
  fun `a waiting change is reported once across collectors`() = runTest {
    val failures = TestFailures()
    val tags =
      DataScope.Factory(
          onFailure = failures,
          online = MutableStateFlow(false),
          clock = testClock(START),
        )
        .create(backgroundScope, setOf(Ops.sender { _, _ -> }))
        .optimisticStore<List<String>, Op>(
          Ops,
          base = QueryStore.inMemory(Fetched(listOf("a"), START)),
          apply = { current, op ->
            check(!op.boom) { "boom" }
            current.orEmpty() + op.tag
          },
        )
    repeat(3) { round ->
      val collector = backgroundScope.launch { tags.data.collect {} }
      runCurrent()
      if (round == 0) {
        tags.mutate(Op("x", boom = true)).stored.await()
        runCurrent()
      }
      collector.cancel()
      advanceTimeBy(1.seconds)
      runCurrent()
    }
    val reports = failures.take().filter { it.message.orEmpty().startsWith("apply threw") }
    assertThat(reports.size).isEqualTo(1)
  }

  /**
   * Two retries of one failed row, back to back, whose requeues both fail on a store error. Each
   * undo removes only its own call's mark, so the change falls back to Failed and stays out of
   * view.
   */
  @Test
  fun `two failed retries leave a failed change out of the view`() = runTest {
    val stores = LaggingOutboxStores()
    val tags = factoryTags(stores, online = true, send = { _, _ -> throw PermanentFailure("No") })
    backgroundScope.launch { tags.data.collect {} }
    runCurrent()
    tags.mutate(Add("b")).await()
    runCurrent()
    val id = tags.pending.first().single().id
    assertThat(tags.data.first()?.value).isEqualTo(listOf("a"))
    // Each requeue reads its row first, which fails here before anything changes
    stores.failEntryReads = true
    tags.retry(id)
    tags.retry(id)
    runCurrent()
    stores.failEntryReads = false
    advanceTimeBy(11.minutes)
    runCurrent()
    assertThat(tags.pending.first().single().status).isInstanceOf<OutboxStatus.Failed>()
    assertThat(tags.data.first()?.value).isEqualTo(listOf("a"))
  }

  /**
   * clear() reads the rows, then discards them one status write at a time, each taking a second. A
   * change stored meanwhile keeps its row, which clear never read, so it stays in view.
   */
  @Test
  fun `a change stored during a clear and not discarded by it stays in view`() = runTest {
    val stores = LaggingOutboxStores(updateTakes = 1.seconds)
    val tags = factoryTags(stores, online = false, send = { _, _ -> })
    backgroundScope.launch { tags.data.collect {} }
    runCurrent()
    tags.mutate(Add("b")).stored.await()
    runCurrent()
    val clearing = backgroundScope.launch { tags.clear() }
    runCurrent()
    tags.mutate(Add("c")).stored.await()
    runCurrent()
    advanceTimeBy(5.seconds)
    runCurrent()
    clearing.join()
    val rows = stores.delegate.open("tags", Add.serializer()).entries().first()
    assertThat(rows.map { it.payload }).containsExactly(Add("c"))
    assertThat(tags.data.first()?.value).isEqualTo(listOf("c"))
  }

  /**
   * A change handed off by a stopped owner, whose row a list that never catches up never shows. On
   * an idle set, its timer still ends it when its lifetime runs out.
   */
  @Test
  fun `a handed off change the list never shows expires on an idle set`() = runTest {
    val owner = Job(backgroundScope.coroutineContext.job)
    val stores = LaggingOutboxStores(firstListOnly = true)
    val sets =
      DataScope.Factory(
          onFailure = TestFailures(),
          online = MutableStateFlow(false),
          stores = stores,
          clock = testClock(START),
        )
        .create(
          CoroutineScope(backgroundScope.coroutineContext + owner),
          setOf(UnitTags.sender { _, _ -> }),
        )
        .optimisticStores<Unit, List<String>, Add>(
          UnitTags,
          base = { QueryStore.inMemory(Fetched(listOf("a"), START)) },
          apply = ::addOnce,
        )
    val tags = sets(Unit)
    val seen = mutableListOf<List<String>?>()
    backgroundScope.launch { tags.data.collect { seen += it?.value } }
    runCurrent()
    tags.mutate(Add("b")).stored.await()
    owner.cancel()
    runCurrent()
    assertThat(seen.last()).isEqualTo(listOf("a", "b"))
    assertThat(sets.knownChanges).isEqualTo(1)

    advanceTimeBy(9.minutes)
    runCurrent()
    assertThat(seen.last()).isEqualTo(listOf("a", "b"))
    advanceTimeBy(2.minutes)
    runCurrent()
    assertThat(seen.last()).isEqualTo(listOf("a"))
    assertThat(sets.knownChanges).isEqualTo(0)
  }

  /**
   * A change fails, is retried six minutes later and fails again, while the list stays frozen on
   * the row as Queued. Its expiry counts from the second failure, so a fetch write eight minutes
   * after it, which checks expiry, still finds it Failed and the stale list does not show it.
   */
  @Test
  fun `a change that fails again starts its expiry over`() = runTest {
    val stores = LaggingOutboxStores(firstListOnly = true)
    val online = MutableStateFlow(false)
    val tags =
      DataScope.Factory(
          onFailure = TestFailures(),
          online = online,
          stores = stores,
          clock = testClock(START),
        )
        .create(backgroundScope, setOf(Tags.sender { _, _ -> throw PermanentFailure("No") }))
        .optimisticStore(
          Tags,
          base = QueryStore.inMemory(Fetched(listOf("a"), START)),
          apply = ::addOnce,
        )
    val handle = tags.mutate(Add("b"))
    handle.stored.await()
    // The only list the views ever get shows the row as Queued
    backgroundScope.launch { tags.data.collect {} }
    runCurrent()
    val id = tags.pending.first().single().id
    online.value = true
    handle.await()
    runCurrent()
    assertThat(tags.data.first()?.value).isEqualTo(listOf("a"))

    advanceTimeBy(6.minutes)
    tags.retry(id).await()
    runCurrent()
    advanceTimeBy(8.minutes)
    tags.write(listOf("a"), testClock(START).now())
    runCurrent()
    assertThat(tags.data.first()?.value).isEqualTo(listOf("a"))
  }

  /**
   * A discarded change is Dropped for good. A retry of its row, while a lagging list still shows
   * the row, leaves no mark, so the change never shows again.
   */
  @Test
  fun `a retry of a discarded change does not show it`() = runTest {
    val tags = factoryTags(LaggingOutboxStores(rowsLag = 1.seconds), online = false) { _, _ -> }
    val seen = mutableListOf<List<String>?>()
    backgroundScope.launch { tags.data.collect { seen += it?.value } }
    advanceTimeBy(2.seconds)
    runCurrent()
    tags.mutate(Add("b")).stored.await()
    advanceTimeBy(2.seconds)
    runCurrent()
    val id = tags.pending.first().single().id
    tags.discard(id).awaitSucceeded()
    runCurrent()
    val discardedAt = seen.size
    tags.retry(id).await()
    runCurrent()
    advanceTimeBy(5.seconds)
    runCurrent()
    assertThat(seen.drop(discardedAt).filter { it != null && "b" in it }).isEmpty()
  }

  /**
   * A discard of a failed row runs slowly, since each status write takes a second. A retry of the
   * row meanwhile adds no mark over the discard's, so the change never shows while the discard
   * settles.
   */
  @Test
  fun `a retry while a discard runs does not show the change`() = runTest {
    val stores = LaggingOutboxStores(updateTakes = 1.seconds)
    val tags = factoryTags(stores, online = true, send = { _, _ -> throw PermanentFailure("No") })
    val seen = mutableListOf<List<String>?>()
    backgroundScope.launch { tags.data.collect { seen += it?.value } }
    runCurrent()
    tags.mutate(Add("b")).await()
    advanceTimeBy(5.seconds)
    runCurrent()
    val id = tags.pending.first().single().id
    assertThat(seen.last()).isEqualTo(listOf("a"))
    val discarding = tags.discard(id)
    runCurrent()
    val discardedAt = seen.size
    tags.retry(id)
    runCurrent()
    advanceTimeBy(5.seconds)
    runCurrent()
    discarding.awaitSucceeded()
    advanceTimeBy(5.seconds)
    runCurrent()
    assertThat(seen.drop(discardedAt).filter { it != null && "b" in it }).isEmpty()
    assertThat(tags.data.first()?.value).isEqualTo(listOf("a"))
  }

  /**
   * Another writer stores a change, and this store lists it. A discard hides it from the moment the
   * call returns, while the status write still takes a second.
   */
  @Test
  fun `a discard hides a listed row of another writer before its write lands`() = runTest {
    val stores = LaggingOutboxStores(updateTakes = 1.seconds)
    val tags = factoryTags(stores, online = false) { _, _ -> }
    val seen = mutableListOf<List<String>?>()
    backgroundScope.launch { tags.data.collect { seen += it?.value } }
    val id = stores.delegate.open(Tags.name, Tags.serializer).insertNew("", Add("b"))
    runCurrent()
    assertThat(seen.last()).isEqualTo(listOf("a", "b"))

    tags.discard(id)
    runCurrent()

    assertThat(seen.last()).isEqualTo(listOf("a"))
    assertThat(tags.data.first()?.value).isEqualTo(listOf("a"))
  }

  /**
   * A collector holds the only row list, which shows the row as Queued. A discard hides the row
   * from the pending rows and the count from the moment the call returns, while its status write
   * takes a second. The row starts to send meanwhile, so the discard fails and the row comes back.
   */
  @Test
  fun `a discard hides a row from pending until the discard fails`() = runTest {
    val stores = LaggingOutboxStores(updateTakes = 1.seconds, firstListOnly = true)
    val sets =
      DataScope.Factory(
          onFailure = TestFailures(),
          online = MutableStateFlow(false),
          stores = stores,
          clock = testClock(START),
        )
        .create(backgroundScope, setOf(UnitTags.sender { _, _ -> }))
        .optimisticStores<Unit, List<String>, Add>(
          UnitTags,
          base = { QueryStore.inMemory(Fetched(listOf("a"), START)) },
          apply = ::addOnce,
        )
    val tags = sets(Unit)
    tags.mutate(Add("b")).stored.await()
    backgroundScope.launch { tags.data.collect {} }
    runCurrent()
    val id = tags.pending.first().single().id
    assertThat(sets.pendingCount.first()).isEqualTo(1)

    val discarding = tags.discard(id)
    assertThat(tags.pending.first()).isEmpty()
    assertThat(sets.pendingCount.first()).isEqualTo(0)

    val rows = stores.delegate.open(UnitTags.name, UnitTags.serializer)
    val row = checkNotNull(rows.entry(id).first())
    check(rows.update(row.copy(status = OutboxStatus.Sending), OutboxStatus.Queued))
    discarding.awaitFailed<IllegalStateException>()
    runCurrent()
    assertThat(tags.pending.first().map { it.id }).containsExactly(id)
    assertThat(sets.pendingCount.first()).isEqualTo(1)
  }

  /**
   * Another writer stores a change that this store's lagging list has not shown yet. A discard
   * cannot hide a row the store has neither made nor listed, so the change shows once the list
   * does, until the discard's write lands.
   */
  @Test
  fun `a discard does not hide a row this store has neither made nor listed`() = runTest {
    val stores = LaggingOutboxStores(updateTakes = 2.seconds, rowsLag = 1.seconds)
    val tags = factoryTags(stores, online = false) { _, _ -> }
    val seen = mutableListOf<List<String>?>()
    backgroundScope.launch { tags.data.collect { seen += it?.value } }
    advanceTimeBy(2.seconds)
    runCurrent()
    val id = stores.delegate.open(Tags.name, Tags.serializer).insertNew("", Add("b"))

    val discarding = tags.discard(id)
    runCurrent()
    assertThat(seen.last()).isEqualTo(listOf("a"))
    advanceTimeBy(1.5.seconds)

    // The list shows the row before the discard's write lands
    assertThat(seen.last()).isEqualTo(listOf("a", "b"))
    discarding.awaitSucceeded()
    advanceTimeBy(2.seconds)
    assertThat(seen.last()).isEqualTo(listOf("a"))
  }

  /**
   * Two retries of one failed row. The first sends and commits while a list a second behind still
   * shows the row, and the second retry's requeue reads the row slowly. The second retry finds the
   * change on record and adds no mark, so nothing stays known on an idle set.
   */
  @Test
  fun `a retry after the change committed leaves nothing known on an idle set`() = runTest {
    val stores = SlowEntryOutboxStores()
    val rejected = MutableStateFlow(true)
    val sets =
      DataScope.Factory(
          onFailure = TestFailures(),
          online = MutableStateFlow(true),
          stores = stores,
          clock = testClock(START),
        )
        .create(
          backgroundScope,
          setOf(
            UnitTags.sender { change, _ ->
              if (rejected.value) {
                throw PermanentFailure("Rejected ${change.mutation}")
              }
            }
          ),
        )
        .optimisticStores<Unit, List<String>, Add>(
          UnitTags,
          base = { QueryStore.inMemory(Fetched(emptyList(), START)) },
          apply = ::addOnce,
        )
    val tags = sets(Unit)
    backgroundScope.launch { tags.data.collect {} }
    runCurrent()
    tags.mutate(Add("b")).await()
    advanceTimeBy(5.seconds)
    runCurrent()
    val id = tags.pending.first().single().id
    assertThat(sets.knownChanges).isEqualTo(0)
    assertThat(tags.data.first()?.value).isEqualTo(emptyList<String>())
    rejected.value = false
    tags.retry(id).await()
    runCurrent()
    stores.slowEntryReads = true
    val second = tags.retry(id)
    advanceTimeBy(20.seconds)
    runCurrent()
    stores.slowEntryReads = false
    advanceTimeBy(5.seconds)
    runCurrent()
    assertThat(second.state.value).isEqualTo(OperationState.Succeeded)
    // Idle past the expiry, so only a forget rule can have removed the change
    advanceTimeBy(15.minutes)
    runCurrent()
    assertThat(sets.knownChanges).isEqualTo(0)
  }

  /** Tags over [stores] in a data scope of their own, with [send]. */
  private fun TestScope.factoryTags(
    stores: OutboxStores,
    online: Boolean,
    send: suspend (Add, EntryId) -> Unit,
  ): OptimisticQueryStore<List<String>, Add> =
    DataScope.Factory(
        onFailure = TestFailures(),
        online = MutableStateFlow(online),
        stores = stores,
        clock = testClock(START),
      )
      .create(backgroundScope, setOf(Tags.sender(send = send)))
      .optimisticStore(
        Tags,
        base = QueryStore.inMemory(Fetched(listOf("a"), START)),
        apply = ::addOnce,
      )

  /**
   * A key whose `toString` differs on every call, so only its serialized form can order its rows.
   */
  @Serializable
  class Item(val id: String) {
    override fun equals(other: Any?): Boolean = other is Item && other.id == id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "Item(id=$id)#${itemToStrings++}"
  }
}

/** How many times [OptimisticQueryStoreTest.Item.toString] ran, which makes each result differ. */
private var itemToStrings = 0

/**
 * A counter server that counts each change once by its row id, as an idempotency key makes a real
 * one do. Each answer waits for [answer], after the change is counted.
 */
private class CounterServer(
  var count: Int = 0,
  applied: Set<EntryId> = emptySet(),
  val answer: CompletableDeferred<Unit> = CompletableDeferred(Unit),
) {
  private val applied = applied.toMutableSet()

  suspend fun exchange(delta: Int, id: EntryId): Int {
    if (applied.add(id)) {
      count += delta
    }
    answer.await()
    return count
  }
}

/** Sends each change of a count to [server], which answers with its count. */
private fun counterSender(server: CounterServer) =
  Counter.exchangingSender(exchange = server::exchange)

/** A count over [base], in a data scope created with [counterSender]. */
private fun DataScope.counterStore(base: QueryStore<Int>) =
  optimisticStore(Counter, base = base, apply = { value, delta -> (value ?: 0) + delta })

/**
 * A counter server that reports the ids of the increments its count holds. It counts each delivery
 * once by its row id, as an idempotency key makes a real one do. Each change waits for [accept]
 * before it is counted, and each answer waits for [answer] after.
 */
private class TallyServer(
  val accept: CompletableDeferred<Unit> = CompletableDeferred(Unit),
  val answer: CompletableDeferred<Unit> = CompletableDeferred(Unit),
) {
  var count = 0
    private set

  private val applied = mutableSetOf<String>()
  private val delivered = mutableSetOf<EntryId>()

  /** What a fetch reads. */
  fun read() = OptimisticQueryStoreTest.Tally(count, applied.toSet())

  suspend fun exchange(
    edit: OptimisticQueryStoreTest.Increment,
    id: EntryId,
  ): OptimisticQueryStoreTest.Tally {
    accept.await()
    if (delivered.add(id)) {
      count += edit.delta
      applied += edit.id
    }
    answer.await()
    return read()
  }
}

/** Sends each change of a tally to [server], which answers with its tally. */
private fun tallySender(server: TallyServer) = Tallies.exchangingSender(exchange = server::exchange)

/**
 * A tally over [base] with an apply that skips repeats, in a data scope created with [tallySender].
 */
private fun DataScope.tallyStore(base: QueryStore<OptimisticQueryStoreTest.Tally>) =
  optimisticStore(Tallies, base = base, apply = ::countOnce)

/**
 * Adds the increment unless the tally already lists it, so a value fetched after the server took
 * the change is left as it is.
 */
private fun countOnce(
  current: OptimisticQueryStoreTest.Tally?,
  edit: OptimisticQueryStoreTest.Increment,
): OptimisticQueryStoreTest.Tally {
  val tally = current ?: OptimisticQueryStoreTest.Tally(0)
  return if (edit.id in tally.applied) {
    tally
  } else {
    OptimisticQueryStoreTest.Tally(tally.count + edit.delta, tally.applied + edit.id)
  }
}

/**
 * Outbox stores whose inserts wait while held, like a database that is slow to write. With a
 * [failure], each insert throws it once it may go on, and stores nothing.
 */
private class HoldingOutboxStores(private val failure: Throwable? = null) : OutboxStores {
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
      override suspend fun insert(id: EntryId, key: String, payload: E) {
        held.first { !it }
        failure?.let { throw it }
        rows.insert(id, key, payload)
      }
    }
  }

  override suspend fun clearAll() = delegate.clearAll()
}

/**
 * In-memory outbox stores whose row list arrives a second late. While [slowEntryReads] is set, each
 * read of a single row starts three seconds late.
 */
private class SlowEntryOutboxStores : OutboxStores {
  private val delegate = InMemoryOutboxStores()
  var slowEntryReads = false

  override fun <E> open(name: String, serializer: KSerializer<E>): OutboxStore<E> {
    val rows = delegate.open(name, serializer)
    return object : OutboxStore<E> by rows {
      override fun entries(): Flow<List<OutboxEntry<E>>> =
        rows.entries().onEach { delay(1.seconds) }

      override fun entry(id: EntryId): Flow<OutboxEntry<E>?> {
        val wait =
          if (slowEntryReads) {
            3.seconds
          } else {
            Duration.ZERO
          }
        return rows.entry(id).onStart { delay(wait) }
      }
    }
  }

  override suspend fun clearAll() = delegate.clearAll()
}

private val START = Instant.fromEpochSeconds(1_700_000_000)

/** The tag list's outbox, whose server answers nothing. */
private val Tags = OutboxKey<Add>("tags")

/** The tag list's outbox, whose server answers with the list it holds. */
private val ReplyingTags = OutboxKey.replying<Add, List<String>>("tags")

/** The tag lists' outbox, one list per item name. */
private val TagsByItem = OutboxKey<KeyedMutation<String, Add>>("tagsByItem")

/** [TagsByItem] whose server answers with the list it holds. */
private val ReplyingTagsByItem =
  OutboxKey.replying<KeyedMutation<String, Add>, List<String>>("tagsByItem")

/** The tag lists' outbox, one list per name, beside [TagsByItem]. */
private val TagsByName = OutboxKey<KeyedMutation<String, Add>>("tagsByName")

/**
 * A new key for the tag lists' outbox, one list per [OptimisticQueryStoreTest.Item]. Each call
 * builds a new key with a new serializer object.
 */
private fun itemTags() = OutboxKey<KeyedMutation<OptimisticQueryStoreTest.Item, Add>>("tagsByItem")

/** A tag list set keyed by [Unit], so its one key still orders its rows by a serialized form. */
private val UnitTags = OutboxKey<KeyedMutation<Unit, Add>>("tags")

/** The note's outbox, whose server answers nothing. */
private val Note = OutboxKey<OptimisticQueryStoreTest.SetText>("note")

/** The note's outbox, whose server answers with the text it holds. */
private val ReplyingNote = OutboxKey.replying<OptimisticQueryStoreTest.SetText, String>("note")

/** The bump counter's outbox, whose server answers with its count. */
private val Bumps = OutboxKey.replying<OptimisticQueryStoreTest.Bump, Int>("count")

/** The plain counter's outbox, whose server answers with its count. */
private val Counter = OutboxKey.replying<Int, Int>("count")

/** The tally's outbox, whose server answers with its tally. */
private val Tallies =
  OutboxKey.replying<OptimisticQueryStoreTest.Increment, OptimisticQueryStoreTest.Tally>("tally")

/** The ops' outbox, whose server answers nothing. */
private val Ops = OutboxKey<OptimisticQueryStoreTest.Op>("ops")
