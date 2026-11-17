// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isLessThanOrEqualTo
import dev.zacsweers.fieldspottr.data.core.impl.committedRecords
import dev.zacsweers.fieldspottr.data.core.testing.TestFailures
import dev.zacsweers.fieldspottr.data.core.testing.assertCancelled
import dev.zacsweers.fieldspottr.data.core.testing.awaitSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.testClock
import dev.zacsweers.fieldspottr.data.core.testing.testDataScopeFactory
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/** How much a set of optimistic stores reads from its row list as its keys and collectors grow. */
@OptIn(ExperimentalAtomicApi::class)
class OptimisticQueryStoreScaleTest {
  @Serializable data class Add(val tag: String)

  @Test
  fun `many keys share one subscription to the row list`() = runTest {
    val stores = CountingOutboxStores()
    val failures = TestFailures()
    val data =
      DataScope.Factory(
          onFailure = failures,
          online = MutableStateFlow(true),
          stores = stores,
          clock = testClock(),
        )
        .create(backgroundScope, setOf(TagsByItem.sender { _, _ -> }))
    val tags =
      data.optimisticStores<String, List<String>, Add>(
        TagsByItem,
        base = QueryStore.inMemoryByKey(),
        apply = { current, add ->
          val list = current.orEmpty()
          if (add.tag in list) {
            list
          } else {
            list + add.tag
          }
        },
      )
    val queries =
      data.keyed(store = tags, freshness = Freshness.staleAfter(1.hours)) { key -> listOf(key) }
    val keys = List(KEYS) { "k$it" }
    val collectors = Job(backgroundScope.coroutineContext.job)
    for (key in keys) {
      backgroundScope.launch(collectors) { queries.data(key).collect {} }
    }
    for (key in keys) {
      queries.data(key).filterNotNull().first()
    }
    runCurrent()

    // Every view, query watcher and collector shares one subscription
    assertThat(stores.live.load()).isEqualTo(1)

    stores.reads.store(0)
    tags.mutate("k0", Add("b")).awaitSucceeded()
    runCurrent()

    // The shared subscription reads the list once per row write: insert, Sending and Sent. A
    // commit reads single rows, never the list.
    assertThat(stores.reads.load()).isLessThanOrEqualTo(3)
    assertThat(stores.live.load()).isEqualTo(1)

    // Nothing collects, so the subscription stops
    collectors.cancelAndJoin()
    runCurrent()
    assertThat(stores.live.load()).isEqualTo(0)
    failures.assertNone()
  }

  /**
   * Each other key holds a change whose send never finishes, so its view applies that change on
   * every overlay. A change on one key, through its send and commit, then runs [apply] and emits
   * only on that key.
   */
  @Test
  fun `a change on one key leaves the views of other keys alone`() = runTest {
    val failures = TestFailures()
    val mutated = "k0"
    val data =
      DataScope.Factory(
          onFailure = failures,
          online = MutableStateFlow(true),
          stores = InMemoryOutboxStores(),
          clock = testClock(),
        )
        .create(
          backgroundScope,
          setOf(
            TagsByItem.sender { change, _ ->
              if (change.key != mutated) {
                awaitCancellation()
              }
            }
          ),
        )
    val mutatedApplies = AtomicInt(0)
    val otherApplies = AtomicInt(0)
    val tags =
      data.optimisticStores<String, List<String>, Add>(
        TagsByItem,
        base = QueryStore.inMemoryByKey(),
        apply = { current, add ->
          if (add.tag == mutated) {
            mutatedApplies.update { it + 1 }
          } else {
            otherApplies.update { it + 1 }
          }
          val list = current.orEmpty()
          if (add.tag in list) {
            list
          } else {
            list + add.tag
          }
        },
      )
    val keys = List(VIEWS) { "k$it" }
    val emitted = keys.associateWith { mutableListOf<Fetched<List<String>>?>() }
    for (key in keys) {
      backgroundScope.launch { tags(key).data.collect { emitted.getValue(key) += it } }
    }
    runCurrent()
    for (key in keys - mutated) {
      tags.mutate(key, Add(key))
    }
    runCurrent()
    for (key in keys - mutated) {
      assertThat(emitted.getValue(key).last()?.value).isEqualTo(listOf(key))
    }

    otherApplies.store(0)
    val before = keys.associateWith { emitted.getValue(it).size }
    tags.mutate(mutated, Add(mutated)).awaitSucceeded()
    runCurrent()

    // The other keys' slices stay the same instances, so their views run no overlay
    assertThat(otherApplies.load()).isEqualTo(0)
    for (key in keys - mutated) {
      assertThat(emitted.getValue(key).size).isEqualTo(before.getValue(key))
    }
    assertThat(emitted.getValue(mutated))
      .isEqualTo(listOf(null, Fetched(listOf(mutated), fetchedAt = null)))
    // The change shows, then the commit, and the idempotence check applies it once more
    assertThat(mutatedApplies.load()).isEqualTo(3)
    failures.assertNone()
  }

  /**
   * With eager dispatch each state update reaches the view on its own. A change whose send never
   * finishes takes three: [OptimisticQueryStores.mutate] records it, the list shows its row Queued,
   * and then Sending. Only the first changes what the view applies.
   */
  @Test
  fun `a waiting change runs the overlay once`() = runTest {
    val failures = TestFailures()
    val data =
      testDataScopeFactory(eager = true)
        .create(backgroundScope, setOf(TagsByItem.sender { _, _ -> awaitCancellation() }))
    val applies = AtomicInt(0)
    val tags =
      data.optimisticStores<String, List<String>, Add>(
        TagsByItem,
        base = QueryStore.inMemoryByKey(),
        onFailure = failures,
        apply = { current, add ->
          applies.update { it + 1 }
          current.orEmpty() + add.tag
        },
      )
    val emitted = mutableListOf<Fetched<List<String>>?>()
    backgroundScope.launch { tags("k").data.collect { emitted += it } }
    runCurrent()
    assertThat(emitted).isEqualTo(listOf(null))

    tags.mutate("k", Add("b"))
    runCurrent()

    assertThat(emitted).isEqualTo(listOf(null, Fetched(listOf("b"), fetchedAt = null)))
    assertThat(applies.load()).isEqualTo(1)
    failures.assertNone()
  }

  /** A change handed off by an owner that stopped still shows, so its view has nothing to redo. */
  @Test
  fun `a change handed off to the next owner runs no overlay`() = runTest {
    val failures = TestFailures()
    val owner = Job(backgroundScope.coroutineContext.job)
    val data =
      testDataScopeFactory(eager = true)
        .create(
          CoroutineScope(backgroundScope.coroutineContext + owner),
          setOf(TagsByItem.sender { _, _ -> awaitCancellation() }),
        )
    val applies = AtomicInt(0)
    val tags =
      data.optimisticStores<String, List<String>, Add>(
        TagsByItem,
        base = QueryStore.inMemoryByKey(),
        onFailure = failures,
        apply = { current, add ->
          applies.update { it + 1 }
          current.orEmpty() + add.tag
        },
      )
    val emitted = mutableListOf<Fetched<List<String>>?>()
    backgroundScope.launch { tags("k").data.collect { emitted += it } }
    runCurrent()
    val handle = tags.mutate("k", Add("b"))
    runCurrent()
    applies.store(0)

    owner.cancel()
    runCurrent()

    handle.assertCancelled()
    assertThat(emitted).isEqualTo(listOf(null, Fetched(listOf("b"), fetchedAt = null)))
    assertThat(applies.load()).isEqualTo(0)
    failures.assertNone()
  }

  /** A send that fails and waits to try again leaves the change showing as it was. */
  @Test
  fun `a send that waits to try again runs no overlay`() = runTest {
    val failures = TestFailures()
    var attempts = 0
    val data =
      testDataScopeFactory(eager = true, retry = RetryPolicy.exponential(initial = 1.seconds))
        .create(
          backgroundScope,
          setOf(
            TagsByItem.sender { _, _ ->
              if (attempts++ == 0) {
                throw IllegalStateException("offline")
              }
              awaitCancellation()
            }
          ),
        )
    val applies = AtomicInt(0)
    val tags =
      data.optimisticStores<String, List<String>, Add>(
        TagsByItem,
        base = QueryStore.inMemoryByKey(),
        onFailure = failures,
        apply = { current, add ->
          applies.update { it + 1 }
          current.orEmpty() + add.tag
        },
      )
    val emitted = mutableListOf<Fetched<List<String>>?>()
    backgroundScope.launch { tags("k").data.collect { emitted += it } }
    runCurrent()
    tags.mutate("k", Add("b"))
    runCurrent()
    assertThat(tags.pending("k").first().single().attempts).isEqualTo(1)
    applies.store(0)

    advanceTimeBy(2.seconds)
    runCurrent()

    assertThat(attempts).isEqualTo(2)
    assertThat(emitted).isEqualTo(listOf(null, Fetched(listOf("b"), fetchedAt = null)))
    assertThat(applies.load()).isEqualTo(0)
    failures.assertNone()
  }

  /**
   * A fetch from after a send confirms its record. The fetch changes the truth, so the view applies
   * the change that still waits once. Marking the record confirmed changes nothing a view computes.
   */
  @Test
  fun `a fetch that confirms a record runs the overlay once`() = runTest {
    val failures = TestFailures()
    val data =
      testDataScopeFactory(eager = true)
        .create(
          backgroundScope,
          setOf(
            TagsByItem.sender { change, _ ->
              if (change.mutation.tag == "c") {
                awaitCancellation()
              }
            }
          ),
        )
    val applies = AtomicInt(0)
    val tags =
      data.optimisticStores<String, List<String>, Add>(
        TagsByItem,
        base = QueryStore.inMemoryByKey(),
        onFailure = failures,
        apply = { current, add ->
          applies.update { it + 1 }
          val list = current.orEmpty()
          if (add.tag in list) {
            list
          } else {
            list + add.tag
          }
        },
      )
    val store = tags("k")
    val emitted = mutableListOf<Fetched<List<String>>?>()
    backgroundScope.launch { store.data.collect { emitted += it } }
    runCurrent()
    tags.mutate("k", Add("b")).awaitSucceeded()
    tags.mutate("k", Add("c"))
    runCurrent()
    assertThat(tags.committedRecords).isEqualTo(1)
    advanceTimeBy(1.seconds)
    applies.store(0)

    val fetchedAt = data.clock.now()
    store.writeFetched(Fetched(listOf("a", "b"), fetchedAt)) { _, _ -> false }
    runCurrent()

    assertThat(emitted.last()).isEqualTo(Fetched(listOf("a", "b", "c"), fetchedAt))
    assertThat(applies.load()).isEqualTo(1)
    failures.assertNone()
  }

  /**
   * Outbox stores that count reads of the row list: [live] is how many subscriptions to it are
   * collected now, and [reads] is how many lists they delivered. Narrow reads are not counted.
   */
  private class CountingOutboxStores : OutboxStores {
    private val delegate = InMemoryOutboxStores()
    val live = AtomicInt(0)
    val reads = AtomicInt(0)

    override fun <E> open(name: String, serializer: KSerializer<E>): OutboxStore<E> {
      val rows = delegate.open(name, serializer)
      return object : OutboxStore<E> by rows {
        override fun entries(): Flow<List<OutboxEntry<E>>> =
          rows
            .entries()
            .onStart { live.update { it + 1 } }
            .onEach { reads.update { it + 1 } }
            .onCompletion { live.update { it - 1 } }
      }
    }

    override suspend fun clearAll() = delegate.clearAll()
  }

  private companion object {
    const val KEYS = 20
    const val VIEWS = 100
    val TagsByItem = OutboxKey<KeyedMutation<String, Add>>("tagsByItem")
  }
}
