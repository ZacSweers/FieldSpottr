// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import dev.zacsweers.fieldspottr.data.core.impl.committedRecords
import dev.zacsweers.fieldspottr.data.core.impl.knownChanges
import dev.zacsweers.fieldspottr.data.core.impl.reportedApplies
import dev.zacsweers.fieldspottr.data.core.testing.LaggingOutboxStores
import dev.zacsweers.fieldspottr.data.core.testing.TestFailures
import dev.zacsweers.fieldspottr.data.core.testing.testClock
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable

/**
 * Each move of a change between the states in `RealOptimisticQueryStores`' table, with the shared
 * row list lagging the store in both directions: [Lag.ListLate] shows each list a second after the
 * store changed, and [Lag.ListEarly] shows a row a second before its insert returns. Apply appends,
 * so a change applied twice shows as a repeated tag.
 */
class OptimisticQueryStoreTransitionTest {
  @Serializable data class Tag(val name: String, val boom: Boolean = false)

  /** How the shared row list races the store. */
  enum class Lag {
    ListLate,
    ListEarly;

    fun stores(): LaggingOutboxStores =
      when (this) {
        ListLate -> LaggingOutboxStores(rowsLag = 1.seconds)
        ListEarly -> LaggingOutboxStores(insertReturnsAfter = 1.seconds)
      }
  }

  /**
   * A tag list over [lag]'s stores, in a data scope with its own [owner] job. The server answers
   * each change with its own list, so commits replace the truth. A tag in [rejected] fails for
   * good.
   */
  private inner class Harness(val lag: Lag, online: Boolean, scope: TestScope) {
    val owner = Job(scope.backgroundScope.coroutineContext[Job])
    val failures = TestFailures()
    val clock = scope.testClock(START)
    val network = MutableStateFlow(online)
    val server = mutableListOf("a")
    val rejected = mutableSetOf<String>()
    val sets =
      DataScope.Factory(
          onFailure = failures,
          online = network,
          stores = lag.stores(),
          clock = clock,
        )
        .create(CoroutineScope(scope.backgroundScope.coroutineContext + owner))
        .optimisticStores<Unit, List<String>, Tag>(
          name = "tags",
          base = { QueryStore.inMemory(Fetched(listOf("a"), START)) },
          apply = { current, tag ->
            check(!tag.boom) { "boom" }
            current.orEmpty() + tag.name
          },
          exchange = { _, tag, _ ->
            if (tag.name in rejected) {
              throw PermanentFailure("Rejected ${tag.name}")
            }
            server += tag.name
            server.toList()
          },
        )
    val tags = sets(Unit)
    val seen = mutableListOf<List<String>>()

    fun TestScope.collect(): Job = backgroundScope.launch {
      tags.data.collect { seen += it?.value.orEmpty() }
    }

    /** Fails unless every value seen shows each tag at most once. */
    fun assertNoTwice() {
      assertThat(seen.filter { it.size != it.toSet().size }, "$lag").isEmpty()
    }

    /** Fails unless every value from the first that showed [tag] on shows it. */
    fun assertStays(tag: String) {
      val first = seen.indexOfFirst { tag in it }
      assertThat(first >= 0, "$lag: $tag never showed").isEqualTo(true)
      assertThat(seen.drop(first).filter { tag !in it }, "$lag").isEmpty()
    }

    /** Fails unless no value from [from] on shows [tag]. */
    fun assertGoneFrom(from: Int, tag: String) {
      assertThat(seen.drop(from).filter { tag in it }, "$lag").isEmpty()
    }
  }

  private suspend fun TestScope.settle() {
    runCurrent()
    advanceTimeBy(5.seconds)
    runCurrent()
  }

  @Test
  fun `a pending change shows at once and once until its row is listed`() = runTest {
    for (lag in Lag.entries) {
      val h = Harness(lag, online = false, scope = this)
      with(h) { collect() }
      settle()
      h.tags.mutate(Tag("b"))
      runCurrent()
      assertThat(h.seen.last(), "$lag").isEqualTo(listOf("a", "b"))
      settle()
      h.assertStays("b")
      h.assertNoTwice()
      assertThat(h.tags.pending.first().size, "$lag").isEqualTo(1)
      h.owner.cancel()
    }
  }

  @Test
  fun `a sent change moves to its record and then to the truth without a gap or a repeat`() =
    runTest {
      for (lag in Lag.entries) {
        val h = Harness(lag, online = true, scope = this)
        with(h) { collect() }
        settle()
        h.tags.mutate(Tag("b")).awaitSucceededOrFail()
        settle()
        assertThat(h.sets.committedRecords, "$lag").isEqualTo(1)

        // A fetch from after the send confirms the record, and the list has passed the row
        h.clock.shift(1.minutes)
        h.tags.write(h.server.toList(), h.clock.now())
        settle()
        assertThat(h.sets.committedRecords, "$lag").isEqualTo(0)
        assertThat(h.sets.knownChanges, "$lag").isEqualTo(0)
        h.assertStays("b")
        h.assertNoTwice()
        h.failures.assertNone()
        h.owner.cancel()
      }
    }

  @Test
  fun `a failed change leaves and stays gone until a retry brings it back`() = runTest {
    for (lag in Lag.entries) {
      val h = Harness(lag, online = true, scope = this)
      h.rejected += "b"
      with(h) { collect() }
      settle()
      val handle = h.tags.mutate(Tag("b"))
      handle.await()
      runCurrent()
      val failedAt = h.seen.size
      settle()
      h.assertGoneFrom(failedAt, "b")
      // The list shows the row as Failed, which agrees with the set, so the set forgets it
      assertThat(h.sets.knownChanges, "$lag").isEqualTo(0)

      h.rejected.clear()
      val failed = h.tags.pending.first { it.isNotEmpty() }.single()
      val retry = h.tags.retry(failed.id)
      runCurrent()
      val retriedAt = h.seen.size - 1
      retry.await()
      settle()
      // The retry marked the change Pending, so it showed at once and stays
      assertThat(h.seen.drop(retriedAt).filter { "b" !in it }, "$lag").isEmpty()
      h.assertNoTwice()
      h.owner.cancel()
    }
  }

  @Test
  fun `a discarded change leaves before discard returns and stays gone`() = runTest {
    for (lag in Lag.entries) {
      val h = Harness(lag, online = false, scope = this)
      with(h) { collect() }
      settle()
      h.tags.mutate(Tag("b"))
      settle()
      val row = h.tags.pending.first { it.isNotEmpty() }.single()
      h.tags.discard(row.id)
      runCurrent()
      val discardedAt = h.seen.size - 1
      assertThat(h.tags.data.first()?.value, "$lag").isEqualTo(listOf("a"))
      settle()
      h.assertGoneFrom(discardedAt, "b")
      // The list showed the row and now lacks it, so the set forgets the change
      assertThat(h.sets.knownChanges, "$lag").isEqualTo(0)
      h.owner.cancel()
    }
  }

  @Test
  fun `a cleared change leaves before clear returns and stays gone`() = runTest {
    for (lag in Lag.entries) {
      val h = Harness(lag, online = false, scope = this)
      with(h) { collect() }
      settle()
      h.tags.mutate(Tag("b"))
      settle()
      h.tags.clear()
      val clearedAt = h.seen.size
      assertThat(h.tags.data.first()?.value.orEmpty().filter { it == "b" }, "$lag").isEmpty()
      settle()
      h.assertGoneFrom(clearedAt, "b")
      h.owner.cancel()
    }
  }

  @Test
  fun `a change handed off by a stopped owner keeps showing`() = runTest {
    for (lag in Lag.entries) {
      val h = Harness(lag, online = false, scope = this)
      with(h) { collect() }
      settle()
      h.tags.mutate(Tag("b")).stored.await()
      h.owner.cancel()
      settle()
      h.assertStays("b")
      h.assertNoTwice()
      // The list shows the waiting row, which decides from here
      assertThat(h.sets.knownChanges, "$lag").isEqualTo(0)
    }
  }

  @Test
  fun `a view collected after the data scope stopped shows the waiting rows`() = runTest {
    for (lag in Lag.entries) {
      val h = Harness(lag, online = false, scope = this)
      h.tags.mutate(Tag("b")).stored.await()
      h.owner.cancel()
      runCurrent()
      val value = h.tags.data.first { it != null && "b" in it.value }
      assertThat(value?.value, "$lag").isEqualTo(listOf("a", "b"))
      advanceTimeBy(5.seconds)
      assertThat(h.tags.data.first()?.value, "$lag").isEqualTo(listOf("a", "b"))
    }
  }

  @Test
  fun `a throwing apply is reported once and forgotten once its change leaves`() = runTest {
    for (lag in Lag.entries) {
      val h = Harness(lag, online = false, scope = this)
      repeat(3) { round ->
        val collector = with(h) { collect() }
        settle()
        if (round == 0) {
          h.tags.mutate(Tag("x", boom = true))
          settle()
        }
        collector.cancel()
        settle()
      }
      val reports = h.failures.take().filter { it.message.orEmpty().startsWith("apply threw") }
      assertThat(reports.size, "$lag").isEqualTo(1)

      with(h) { collect() }
      settle()
      h.tags.discard(h.tags.pending.first { it.isNotEmpty() }.single().id)
      settle()
      assertThat(h.sets.reportedApplies, "$lag").isEqualTo(0)
      assertThat(h.sets.knownChanges, "$lag").isEqualTo(0)
      h.owner.cancel()
    }
  }

  private suspend fun Operation.awaitSucceededOrFail() {
    assertThat(await()).isNotNull().isEqualTo(OperationState.Succeeded)
  }

  private companion object {
    val START: Instant = Instant.fromEpochSeconds(1_700_000_000)
  }
}
