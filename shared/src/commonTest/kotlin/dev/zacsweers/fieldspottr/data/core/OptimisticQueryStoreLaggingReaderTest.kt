// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import dev.zacsweers.fieldspottr.data.core.testing.LaggingOutboxStores
import dev.zacsweers.fieldspottr.data.core.testing.LaggingQueryStore
import dev.zacsweers.fieldspottr.data.core.testing.TestClock
import dev.zacsweers.fieldspottr.data.core.testing.TestFailures
import dev.zacsweers.fieldspottr.data.core.testing.awaitSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.testClock
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable

/**
 * A view whose reader of the base store runs late, or whose truth lags a write the set already
 * recorded, while sent changes commit and fetches land.
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
