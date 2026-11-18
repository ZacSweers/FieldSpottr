// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.isEmpty
import dev.zacsweers.fieldspottr.data.core.testing.LaggingQueryStore
import dev.zacsweers.fieldspottr.data.core.testing.TestFailures
import dev.zacsweers.fieldspottr.data.core.testing.awaitSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.testClock
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable

/** A view whose reader of the base store runs late while a fetch confirms a sent change. */
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

private val START = Instant.fromEpochSeconds(1_700_000_000)

/** The tag list's outbox, keyed by [Unit], whose server answers nothing. */
private val Tags =
  OutboxKey<KeyedMutation<Unit, OptimisticQueryStoreLaggingReaderTest.Add>>("laggingReaderTags")
