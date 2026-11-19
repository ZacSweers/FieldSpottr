// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isLessThanOrEqualTo
import dev.zacsweers.fieldspottr.data.core.testing.LaggingOutboxStores
import dev.zacsweers.fieldspottr.data.core.testing.LaggingQueryStore
import dev.zacsweers.fieldspottr.data.core.testing.TestClock
import dev.zacsweers.fieldspottr.data.core.testing.TestFailures
import dev.zacsweers.fieldspottr.data.core.testing.awaitSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.testClock
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable

/**
 * A view whose reader of the base store runs late, or whose truth lags a write the set already
 * recorded, while sent changes commit and fetches land, and how often a view reads the store again.
 */
class OptimisticQueryStoreLaggingReaderTest {
  @Serializable data class Add(val tag: String)

  @Test
  fun `a view keeps a sent change until its reader of the base catches up`() = runTest {
    val clock = testClock(START)
    val base =
      LaggingQueryStore(
        lag = 5.seconds,
        delegate = QueryStore.inMemory(Fetched(listOf("a"), START)),
      )
    val tags =
      DataScope.Factory(onFailure = TestFailures(), online = MutableStateFlow(true), clock = clock)
        .create(backgroundScope, setOf(Tags.sender { _, _ -> }))
        .optimisticStores(Tags, base = { _: Unit -> base }, apply = ::addOnce)(Unit)
    val seen = mutableListOf<List<String>?>()
    backgroundScope.launch { tags.data.collect { seen += it?.value } }
    runCurrent()
    tags.mutate(Add("b")).awaitSucceeded()
    runCurrent()
    // A fetch from after the send confirms the change while the reader still lags
    advanceTimeBy(1.seconds)
    tags.writeFetched(Fetched(listOf("a", "b"), clock.now())) { _, _ -> false }
    advanceTimeBy(10.seconds)
    runCurrent()

    val first = seen.indexOfFirst { it != null && "b" in it }
    assertThat(seen.drop(first).filter { it == null || "b" !in it }).isEmpty()
  }

  @Test
  fun `a late value of the base never shows a sent change twice`() = runTest {
    val base =
      LaggingQueryStore(
        lag = 5.seconds,
        delegate = QueryStore.inMemory(Fetched(listOf("a"), START)),
      )
    val tags = appendingTags(base)
    val seen = mutableListOf<List<String>?>()
    backgroundScope.launch { tags.data.collect { seen += it?.value } }
    runCurrent()
    tags.mutate(Add("b")).awaitSucceeded()
    runCurrent()
    // The commit of c trims b's record while the reader still holds back the value b's commit wrote
    tags.mutate(Add("c")).awaitSucceeded()
    runCurrent()
    advanceTimeBy(20.seconds)
    runCurrent()

    assertThat(seen.filter { it != null && it.size != it.toSet().size }).isEmpty()
    assertThat(seen.last()).isEqualTo(listOf("a", "b", "c"))
  }

  @Test
  fun `a view keeps a folded change while a second fetch write waits to land`() = runTest {
    val base = GatedQueryStore(QueryStore.inMemory(Fetched(listOf("a"), START)))
    val tags = appendingTags(base)
    val seen = mutableListOf<List<String>?>()
    backgroundScope.launch { tags.data.collect { seen += it?.value } }
    runCurrent()
    advanceTimeBy(1.seconds)
    tags.mutate(Add("b")).awaitSucceeded()
    runCurrent()
    // Fetches that read the server before the send land with the change folded in
    tags.writeFetched(Fetched(listOf("a", "z"), START)) { _, _ -> false }
    runCurrent()
    val gate = CompletableDeferred<Unit>()
    base.gate = gate
    val second = launch { tags.writeFetched(Fetched(listOf("a", "y"), START)) { _, _ -> false } }
    runCurrent()

    // The record is marked for the new fold, and the store still holds the first one
    assertThat(seen.last()).isEqualTo(listOf("a", "z", "b"))
    assertThat(seen.filter { it != null && it.size != it.toSet().size }).isEmpty()

    gate.complete(Unit)
    second.join()
    runCurrent()
    assertThat(seen.filter { it != null && it.size != it.toSet().size }).isEmpty()
    assertThat(seen.last()).isEqualTo(listOf("a", "y", "b"))
  }

  @Test
  fun `a fetch write that failed leaves the next one able to tell the stored fold`() = runTest {
    val base = GatedQueryStore(QueryStore.inMemory(Fetched(listOf("a"), START)))
    val tags = appendingTags(base)
    val seen = mutableListOf<List<String>?>()
    backgroundScope.launch { tags.data.collect { seen += it?.value } }
    runCurrent()
    advanceTimeBy(1.seconds)
    tags.mutate(Add("b")).awaitSucceeded()
    runCurrent()
    // Fetches that read the server before the send land with the change folded in
    tags.writeFetched(Fetched(listOf("a", "z"), START)) { _, _ -> false }
    runCurrent()
    // The next fetch write marks the record and then fails before the store changes
    base.failNext = true
    assertFailure { tags.writeFetched(Fetched(listOf("a", "y"), START)) { _, _ -> false } }
    runCurrent()
    val gate = CompletableDeferred<Unit>()
    base.gate = gate
    val third = launch { tags.writeFetched(Fetched(listOf("a", "x"), START)) { _, _ -> false } }
    runCurrent()

    // The store still holds the first fold
    assertThat(seen.last()).isEqualTo(listOf("a", "z", "b"))
    assertThat(seen.filter { it != null && it.size != it.toSet().size }).isEmpty()

    gate.complete(Unit)
    third.join()
    runCurrent()
    assertThat(seen.filter { it != null && it.size != it.toSet().size }).isEmpty()
    assertThat(seen.last()).isEqualTo(listOf("a", "x", "b"))
  }

  @Test
  fun `a cleared value keeps a confirmed change once the list passes its row`() = runTest {
    val clock = testClock(START)
    val base = QueryStore.inMemory(Fetched(listOf("a"), START))
    // The send takes longer than the list's lag, so the list shows the row before it passes it
    val tags =
      appendingTags(
        base,
        clock = clock,
        stores = LaggingOutboxStores(rowsLag = 5.seconds),
        send = { delay(6.seconds) },
      )
    val seen = mutableListOf<List<String>?>()
    backgroundScope.launch { tags.data.collect { seen += it?.value } }
    runCurrent()
    advanceTimeBy(1.seconds)
    tags.mutate(Add("b")).awaitSucceeded()
    runCurrent()
    // A fetch from after the send confirms the change, and a clear folds it onto nothing, all
    // before the list passes the row
    advanceTimeBy(1.seconds)
    tags.writeFetched(Fetched(listOf("a", "b"), clock.now())) { _, _ -> false }
    runCurrent()
    tags.clearFetched { _, _ -> false }
    runCurrent()
    advanceTimeBy(30.seconds)
    runCurrent()

    assertThat(seen.filter { it != null && it.size != it.toSet().size }).isEmpty()
    assertThat(seen.last()).isEqualTo(listOf("b"))
  }

  @Test
  fun `a view never goes back to a late value after it read a newer fold`() = runTest {
    val clock = testClock(START)
    val base =
      LaggingQueryStore(
        lag = 5.seconds,
        delegate = QueryStore.inMemory(Fetched(listOf("a"), START)),
      )
    val tags = appendingTags(base, clock = clock)
    val seen = mutableListOf<List<String>?>()
    backgroundScope.launch { tags.data.collect { seen += it?.value } }
    runCurrent()
    advanceTimeBy(1.seconds)
    // The commit writes [a, b], which the lagging reader holds back for five seconds
    tags.mutate(Add("b")).awaitSucceeded()
    runCurrent()
    // A fetch that read the server before the send lands with the same fetch time and folds b in.
    // The view reads the fold from the store while the reader still holds back [a, b].
    tags.writeFetched(Fetched(listOf("a", "z"), START)) { _, _ -> false }
    runCurrent()
    assertThat(seen.last()).isEqualTo(listOf("a", "z", "b"))

    advanceTimeBy(20.seconds)
    runCurrent()

    assertThat(seen).isEqualTo(listOf(listOf("a"), listOf("a", "b"), listOf("a", "z", "b")))
    val fold = seen.indexOf(listOf("a", "z", "b"))
    assertThat(seen.drop(fold)).doesNotContain(listOf("a", "b"))
  }

  @Test
  fun `a view never goes back to a late value after it read a newer fetch`() = runTest {
    val clock = testClock(START)
    val base =
      LaggingQueryStore(
        lag = 5.seconds,
        delegate = QueryStore.inMemory(Fetched(listOf("a"), START)),
      )
    // The row list never passes the row, so the confirmed record keeps its value
    val tags =
      appendingTags(base, clock = clock, stores = LaggingOutboxStores(firstListOnly = true))
    val seen = mutableListOf<List<String>?>()
    backgroundScope.launch { tags.data.collect { seen += it?.value } }
    runCurrent()
    advanceTimeBy(1.seconds)
    // The commit writes [a, b], which the lagging reader holds back for five seconds
    tags.mutate(Add("b")).awaitSucceeded()
    runCurrent()
    // A fetch from after the send lands as it is, inside the reader's lag
    advanceTimeBy(1.seconds)
    tags.writeFetched(Fetched(listOf("a", "b", "y"), clock.now())) { _, _ -> false }
    runCurrent()
    assertThat(seen.last()).isEqualTo(listOf("a", "b", "y"))

    advanceTimeBy(20.seconds)
    runCurrent()

    assertThat(seen).isEqualTo(listOf(listOf("a"), listOf("a", "b"), listOf("a", "b", "y")))
  }

  @Test
  fun `a view reads the base again only while a record may be ahead of it`() = runTest {
    val clock = testClock(START)
    val base = CountingQueryStore(QueryStore.inMemory(Fetched(listOf("a"), START)))
    val tags = appendingTags(base, clock = clock)
    val seen = mutableListOf<List<String>?>()
    backgroundScope.launch { tags.data.collect { seen += it?.value } }
    runCurrent()
    advanceTimeBy(1.seconds)
    tags.mutate(Add("b")).awaitSucceeded()
    runCurrent()
    // A fetch from after the send confirms the change
    advanceTimeBy(1.seconds)
    tags.writeFetched(Fetched(listOf("a", "b"), clock.now())) { _, _ -> false }
    runCurrent()
    for (tag in listOf("c", "d", "e")) {
      advanceTimeBy(1.seconds)
      tags.mutate(Add(tag)).awaitSucceeded()
      runCurrent()
    }

    assertThat(seen.filter { it != null && it.size != it.toSet().size }).isEmpty()
    assertThat(seen.last()).isEqualTo(listOf("a", "b", "c", "d", "e"))
    // The view's own collection, one read per commit and one by the fetch write. A view whose
    // records hold the truth it was given reads nothing more.
    assertThat(base.collections).isLessThanOrEqualTo(6)
  }

  /**
   * A keyed tag store over [base] whose apply appends without a check, so a change applied twice
   * shows. Its sender runs [send] and answers nothing.
   */
  private fun TestScope.appendingTags(
    base: QueryStore<List<String>>,
    clock: TestClock = testClock(START),
    stores: OutboxStores = InMemoryOutboxStores(),
    send: suspend () -> Unit = {},
  ) =
    DataScope.Factory(
        onFailure = TestFailures(),
        online = MutableStateFlow(true),
        stores = stores,
        clock = clock,
      )
      .create(backgroundScope, setOf(Tags.sender { _, _ -> send() }))
      .optimisticStores(Tags, base = { _: Unit -> base }, apply = ::append)(Unit)
}

/** A store that counts how often its [data] is collected, which is once per read of the store. */
private class CountingQueryStore<T>(private val delegate: QueryStore<T>) : QueryStore<T> {
  var collections = 0
    private set

  override val data: Flow<Fetched<T>?> = delegate.data.onStart { collections++ }

  override suspend fun writeFetched(fetched: Fetched<T>, keepsStored: KeepsStored) {
    delegate.writeFetched(fetched, keepsStored)
  }

  override suspend fun clearFetched(keepsStored: KeepsStored) {
    delegate.clearFetched(keepsStored)
  }

  override suspend fun invalidate() {
    delegate.invalidate()
  }
}

/**
 * A store whose writes and clears wait on [gate] while it is set, before they reach [delegate]. So
 * a test can act while a write is held before it changes the stored value. While [failNext] is set,
 * the next write or clear throws before it reaches [delegate], and clears the flag.
 */
private class GatedQueryStore<T>(private val delegate: QueryStore<T>) : QueryStore<T> {
  var gate: CompletableDeferred<Unit>? = null

  var failNext = false

  override val data: Flow<Fetched<T>?>
    get() = delegate.data

  override suspend fun writeFetched(fetched: Fetched<T>, keepsStored: KeepsStored) {
    gate?.await()
    failIfSet()
    delegate.writeFetched(fetched, keepsStored)
  }

  override suspend fun clearFetched(keepsStored: KeepsStored) {
    gate?.await()
    failIfSet()
    delegate.clearFetched(keepsStored)
  }

  private fun failIfSet() {
    if (failNext) {
      failNext = false
      throw IllegalStateException("The store failed to write")
    }
  }

  override suspend fun invalidate() {
    delegate.invalidate()
  }
}

/** Adds the tag once, as the store requires. */
private fun addOnce(
  current: List<String>?,
  add: OptimisticQueryStoreLaggingReaderTest.Add,
): List<String> {
  val tags = current.orEmpty()
  return if (add.tag in tags) {
    tags
  } else {
    tags + add.tag
  }
}

/** Appends the tag, which is not idempotent, so a change a view applied twice shows. */
private fun append(
  current: List<String>?,
  add: OptimisticQueryStoreLaggingReaderTest.Add,
): List<String> = current.orEmpty() + add.tag

private val START = Instant.fromEpochSeconds(1_700_000_000)

/** The tag list's outbox, keyed by [Unit], whose server answers nothing. */
private val Tags =
  OutboxKey<KeyedMutation<Unit, OptimisticQueryStoreLaggingReaderTest.Add>>("laggingReaderTags")
