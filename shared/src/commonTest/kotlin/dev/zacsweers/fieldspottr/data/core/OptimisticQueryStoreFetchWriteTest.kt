// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.messageContains
import dev.zacsweers.fieldspottr.data.core.impl.committedRecords
import dev.zacsweers.fieldspottr.data.core.testing.LaggingOutboxStores
import dev.zacsweers.fieldspottr.data.core.testing.TestClock
import dev.zacsweers.fieldspottr.data.core.testing.TestFailures
import dev.zacsweers.fieldspottr.data.core.testing.TestFetch
import dev.zacsweers.fieldspottr.data.core.testing.awaitSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.testClock
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable

/** Fetch writes through an optimistic store, and what they leave of the sent changes on record. */
class OptimisticQueryStoreFetchWriteTest {
  @Serializable data class Add(val tag: String)

  private val failures = TestFailures()
  private val stores = LaggingOutboxStores()
  private lateinit var clock: TestClock
  private lateinit var data: DataScope
  private lateinit var sets: OptimisticQueryStores<String, List<String>, Add>
  private val truths = HashMap<String, InMemoryQueryStore<List<String>>>()

  /** A tag list per key, online, whose sends the server takes and answers with nothing. */
  private fun TestScope.tagSets() {
    clock = testClock(START)
    data =
      DataScope.Factory(
          onFailure = failures,
          online = MutableStateFlow(true),
          stores = stores,
          clock = clock,
        )
        .create(backgroundScope, setOf(Tags.sender { _, _ -> }))
    sets =
      data.optimisticStores(
        Tags,
        base = { key ->
          truths.getOrPut(key) { QueryStore.inMemory(Fetched(listOf("a"), START)) }
        },
        apply = ::addOnce,
      )
  }

  /**
   * A fetch from after a send lands while nothing collects the store, so the set reads the row of
   * the confirmed record to prune it. That read fails after the fetch was saved.
   */
  @Test
  fun `a cleanup that fails after a fetch is saved is reported and the fetch succeeds`() = runTest {
    tagSets()
    val tags = sets("x")
    tags.mutate(Add("b")).await()
    runCurrent()
    assertThat(sets.committedRecords).isEqualTo(1)

    advanceTimeBy(1.minutes)
    stores.failEntryReads = true
    tags.writeFetched(Fetched(listOf("a", "b", "c"), clock.now())) { _, _ -> false }

    assertThat(truths.getValue("x").data.value?.value).isEqualTo(listOf("a", "b", "c"))
    assertThat(failures.assertSingle<IllegalStateException>()).messageContains("Disk error")
  }

  /**
   * Starts a fetch of key x that reads the server before it takes "b", and holds its answer. Then
   * sends "b", which the server acknowledges with nothing, so the truth takes the change and keeps
   * its fetch time. Returns the query and the held fetch's handle.
   */
  private suspend fun TestScope.fetchHeldAcrossSend(
    fetch: TestFetch<List<String>>
  ): Pair<DataQuery<List<String>>, Operation> {
    tagSets()
    val query = data.query(sets("x"), Freshness.staleAfter(1.hours), fetch = fetch)
    advanceTimeBy(1.seconds)
    fetch.hold()
    val refresh = query.requestFetch(force = true)
    runCurrent()
    advanceTimeBy(1.seconds)
    sets("x").mutate(Add("b")).awaitSucceeded()
    assertThat(truths.getValue("x").data.value).isEqualTo(Fetched(listOf("a", "b"), START))
    return query to refresh
  }

  /** A fetch write on key y, which prunes the records of every key. */
  private suspend fun writeOtherKey() {
    sets("y").writeFetched(Fetched(listOf("y"), clock.now())) { _, _ -> false }
  }

  @Test
  fun `a fetch older than an expired record keeps the change when nothing pruned it`() = runTest {
    val fetch = TestFetch { listOf("a") }
    val (_, refresh) = fetchHeldAcrossSend(fetch)
    advanceTimeBy(11.minutes)

    fetch.release()
    refresh.awaitSucceeded()

    assertThat(truths.getValue("x").data.value?.value).isEqualTo(listOf("a", "b"))
    failures.assertNone()
  }

  @Test
  fun `a fetch older than a record that is still kept folds the change in`() = runTest {
    val fetch = TestFetch { listOf("a") }
    val (_, refresh) = fetchHeldAcrossSend(fetch)
    advanceTimeBy(5.minutes)
    writeOtherKey()

    fetch.release()
    refresh.awaitSucceeded()

    val truth = truths.getValue("x").data.value
    assertThat(truth?.value).isEqualTo(listOf("a", "b"))
    assertThat(truth?.invalidated).isEqualTo(false)
    failures.assertNone()
  }

  @Test
  fun `a fetch that starts after a pruned record's send is the server's word`() = runTest {
    val fetch = TestFetch { listOf("a") }
    val (query, refresh) = fetchHeldAcrossSend(fetch)
    fetch.release()
    refresh.awaitSucceeded()
    advanceTimeBy(11.minutes)
    writeOtherKey()

    // The server dropped the change since
    fetch.respond = { listOf("a", "z") }
    query.requestFetch(force = true).awaitSucceeded()

    assertThat(truths.getValue("x").data.value).isEqualTo(Fetched(listOf("a", "z"), clock.now()))
    failures.assertNone()
  }
}

/** Adds the tag once, as the store requires. */
private fun addOnce(
  current: List<String>?,
  add: OptimisticQueryStoreFetchWriteTest.Add,
): List<String> {
  val tags = current.orEmpty()
  return if (add.tag in tags) {
    tags
  } else {
    tags + add.tag
  }
}

private val START = Instant.fromEpochSeconds(1_700_000_000)

/** The tag lists' outbox, one list per key, whose server answers nothing. */
private val Tags =
  OutboxKey<KeyedMutation<String, OptimisticQueryStoreFetchWriteTest.Add>>("fetchWriteTags")
