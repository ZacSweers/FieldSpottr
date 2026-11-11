// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isLessThanOrEqualTo
import dev.zacsweers.fieldspottr.data.core.testing.TestFailures
import dev.zacsweers.fieldspottr.data.core.testing.awaitSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.testClock
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.concurrent.atomics.update
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
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
        .create(backgroundScope)
    val tags =
      data.optimisticStores<String, List<String>, Add>(
        name = "tagsByItem",
        base = QueryStore.inMemoryByKey(),
        apply = { current, add ->
          val list = current.orEmpty()
          if (add.tag in list) {
            list
          } else {
            list + add.tag
          }
        },
        send = { _, _, _ -> },
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
    val data =
      DataScope.Factory(
          onFailure = failures,
          online = MutableStateFlow(true),
          stores = InMemoryOutboxStores(),
          clock = testClock(),
        )
        .create(backgroundScope)
    val mutated = "k0"
    val mutatedApplies = AtomicInt(0)
    val otherApplies = AtomicInt(0)
    val tags =
      data.optimisticStores<String, List<String>, Add>(
        name = "tagsByItem",
        base = QueryStore.inMemoryByKey(),
        apply = { current, add ->
          if (add.tag == mutated) {
            mutatedApplies.incrementAndFetch()
          } else {
            otherApplies.incrementAndFetch()
          }
          val list = current.orEmpty()
          if (add.tag in list) {
            list
          } else {
            list + add.tag
          }
        },
        send = { key, _, _ ->
          if (key != mutated) {
            awaitCancellation()
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
      .isEqualTo(listOf(null, Fetched(listOf(mutated), QueryStore.UNFETCHED)))
    // The change shows, then the commit, and the idempotence check applies it once more
    assertThat(mutatedApplies.load()).isEqualTo(3)
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
            .onStart { live.incrementAndFetch() }
            .onEach { reads.update { it + 1 } }
            .onCompletion { live.decrementAndFetch() }
      }
    }

    override suspend fun clearAll() = delegate.clearAll()
  }

  private companion object {
    const val KEYS = 20
    const val VIEWS = 100
  }
}
