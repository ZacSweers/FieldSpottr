// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import dev.zacsweers.fieldspottr.data.core.impl.committedRecords
import dev.zacsweers.fieldspottr.data.core.impl.knownChanges
import dev.zacsweers.fieldspottr.data.core.testing.TestDataScope
import dev.zacsweers.fieldspottr.data.core.testing.TestDataScopeFactory
import dev.zacsweers.fieldspottr.data.core.testing.awaitSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.insertNew
import dev.zacsweers.fieldspottr.data.core.testing.testClock
import dev.zacsweers.fieldspottr.data.core.testing.testDataScopeFactory
import kotlin.concurrent.Volatile
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** A server answer on an optimistic store whose commit has not written it to the base store yet. */
class OptimisticQueryStoreReplyTest {
  /** The count the server holds. Each change adds to it, and the answer is the new count. */
  private var server = 0
  private var fetches = 0
  private lateinit var data: TestDataScope
  private lateinit var sets: OptimisticQueryStores<Unit, Int, Int>
  private val base = FailingStore(QueryStore.inMemory(Fetched(0, START)))

  /**
   * A count over [base] that never goes stale by age, and a query over it. [factory] holds the
   * outbox rows, so a test can store rows before the count opens.
   */
  private fun TestScope.counter(
    factory: TestDataScopeFactory = testDataScopeFactory(clock = testClock(START))
  ): Pair<OptimisticQueryStore<Int, Int>, DataQuery<Int>> {
    data =
      factory.create(
        backgroundScope,
        setOf(
          Counter.exchangingSender { change, _ ->
            server += change.mutation
            server
          }
        ),
      )
    sets =
      data.optimisticStores(
        Counter,
        base = { base },
        apply = { value, delta -> (value ?: 0) + delta },
      )
    val store = sets(Unit)
    val query =
      data.query(store, Freshness.NeverStale) {
        fetches++
        server
      }
    return store to query
  }

  /**
   * Base 0 and a pending +1, which the server answers with 1. Only the +1 applied again on top of
   * the answer, once it is on record, could show 2.
   */
  @Test
  fun `a server answer on record never shows the change again on top`() = runTest {
    val (store, _) = counter()
    val seen = mutableListOf<Int?>()
    backgroundScope.launch { store.data.collect { seen += it?.value } }
    runCurrent()
    store.mutate(1).awaitSucceeded()
    runCurrent()

    assertThat(sets.committedRecords).isEqualTo(1)
    assertThat(seen).isEqualTo(listOf(0, 1))
  }

  @OptIn(DelicateDataLayerApi::class)
  @Test
  fun `a store whose sender answers with a value takes only the serial ordering`() = runTest {
    val (store, _) = counter()
    assertThat(store.data.first()?.value).isEqualTo(0)

    for (ordering in listOf(Ordering.Keyed("x"), Ordering.Concurrent)) {
      assertFailsWith<IllegalArgumentException> { store.mutate(1, ordering) }
    }
    runCurrent()

    assertThat(store.data.first()?.value).isEqualTo(0)
    assertThat(sets.knownChanges).isEqualTo(0)
    assertThat(data.outboxStore<KeyedMutation<Unit, Int>>(Counter.name).entries().first()).isEmpty()
    assertThat(server).isEqualTo(0)
  }

  @Test
  fun `an invalidate covers a server answer whose base write is still owed`() = runTest {
    val (store, query) = counter()
    advanceTimeBy(1.minutes)
    base.failWrites = true
    store.mutate(1).await()
    runCurrent()
    assertThat(sets.committedRecords).isEqualTo(1)
    assertThat(store.data.first()?.value).isEqualTo(1)

    query.invalidate().awaitSucceeded()
    assertThat(store.data.first()?.invalidated).isEqualTo(true)
    // The fetch reads the server, though its own write fails like the commit's
    query.requestFetch(force = false).await()
    assertThat(fetches).isEqualTo(1)

    base.failWrites = false
    advanceTimeBy(5.minutes)
    runCurrent()
    // The commit wrote the answer, still marked stale, so a check fetches again
    assertThat(base.delegate.data.value?.value).isEqualTo(1)
    assertThat(base.delegate.data.value?.invalidated ?: false).isTrue()
    query.requestFetch(force = false).awaitSucceeded()
    assertThat(fetches).isEqualTo(2)
    // A fetch from after the send is the server's word, so the value is fresh again
    assertThat(store.data.first()?.invalidated ?: true).isFalse()
    query.requestFetch(force = false).awaitSucceeded()
    assertThat(fetches).isEqualTo(2)
  }

  /**
   * A +1 sent before the count opened, whose stored answer cannot be decoded, then a fetch that
   * read the server before the send. The folded value is an estimate, so the truth stays stale.
   */
  @Test
  fun `an older fetch that folds in a change with an unknown answer leaves the truth stale`() =
    runTest {
      val factory = testDataScopeFactory(clock = testClock(START))
      val rows = factory.outboxStore<KeyedMutation<Unit, Int>>(Counter.name)
      val id = rows.insertNew("", KeyedMutation(Unit, 1))
      val row = checkNotNull(rows.entry(id).first())
      val sent = row.copy(status = OutboxStatus.Sent, sentAt = START, reply = "not json")
      assertThat(rows.update(sent, expected = OutboxStatus.Queued)).isTrue()
      val (store, query) = counter(factory)
      rows.entry(id).first { it?.committed == true }
      runCurrent()
      assertThat(base.delegate.data.value?.invalidated).isEqualTo(true)

      store.writeFetched(Fetched(0, START - 30.minutes)) { _, _ -> false }
      assertThat(base.delegate.data.value?.value).isEqualTo(1)
      assertThat(base.delegate.data.value?.invalidated).isEqualTo(true)
      // The server took the change, and a fetch from after the send brings its word
      server = 1
      advanceTimeBy(1.minutes)
      query.fetchIfStale().awaitSucceeded()
      assertThat(fetches).isEqualTo(1)
      assertThat(base.delegate.data.value).isEqualTo(Fetched(1, START + 1.minutes))
    }

  /** A store whose writes throw while [failWrites] is set, like a full disk. */
  private class FailingStore<T>(val delegate: InMemoryQueryStore<T>) : QueryStore<T> {
    @Volatile var failWrites = false

    override val data: Flow<Fetched<T>?> = delegate.data

    override suspend fun writeFetched(fetched: Fetched<T>, keepsStored: KeepsStored) {
      check(!failWrites) { "The disk is full" }
      delegate.writeFetched(fetched, keepsStored)
    }

    override suspend fun clearFetched(keepsStored: KeepsStored) {
      check(!failWrites) { "The disk is full" }
      delegate.clearFetched(keepsStored)
    }

    override suspend fun invalidate() {
      delegate.invalidate()
    }
  }
}

private val START = Instant.fromEpochSeconds(1_700_000_000)

/** The counter's outbox, whose server answers with its count. */
private val Counter = OutboxKey.replying<KeyedMutation<Unit, Int>, Int>("replyCount")
