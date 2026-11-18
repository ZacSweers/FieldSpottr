// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import dev.zacsweers.fieldspottr.data.core.impl.committedRecords
import dev.zacsweers.fieldspottr.data.core.testing.TestDataScope
import dev.zacsweers.fieldspottr.data.core.testing.awaitSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.testClock
import dev.zacsweers.fieldspottr.data.core.testing.testDataScope
import kotlin.concurrent.Volatile
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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

  /** A count over [base] that never goes stale by age, and a query over it. */
  private fun TestScope.counter(): Pair<OptimisticQueryStore<Int, Int>, DataQuery<Int>> {
    data =
      testDataScope(
        clock = testClock(START),
        senders =
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
