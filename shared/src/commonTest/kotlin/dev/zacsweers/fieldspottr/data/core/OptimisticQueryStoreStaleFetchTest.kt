// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import dev.zacsweers.fieldspottr.data.core.impl.committedRecords
import dev.zacsweers.fieldspottr.data.core.testing.Add
import dev.zacsweers.fieldspottr.data.core.testing.addOnce
import dev.zacsweers.fieldspottr.data.core.testing.awaitSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.testClock
import dev.zacsweers.fieldspottr.data.core.testing.testDataScope
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * A fetch write marked stale, like one from a fetch that started before the newest invalidate,
 * lands in an optimistic store's truth still marked stale, whatever the sent changes on record do
 * to its value.
 */
class OptimisticQueryStoreStaleFetchTest {
  @Test
  fun `a stale fetch that a server answer replaces lands stale`() = runTest {
    val data =
      testDataScope(
        clock = testClock(START),
        senders =
          setOf(
            ReplyingTags.exchangingSender { change, _ ->
              listOf("a", change.mutation.tag.uppercase())
            }
          ),
      )
    val truth = QueryStore.inMemory(Fetched(listOf("a"), START - 1.hours))
    val stores =
      data.optimisticStores(ReplyingTags, base = { _: String -> truth }, apply = ::addOnce)
    val store = stores("k")
    store.mutate(Add("b")).awaitSucceeded()
    assertThat(stores.committedRecords).isEqualTo(1)

    // Read before the send, so the answer on record replaces its value
    store.writeFetched(Fetched(listOf("a"), START - 1.minutes, invalidated = true)) { _, _ ->
      false
    }

    assertThat(truth.data.value).isEqualTo(Fetched(listOf("a", "B"), START, invalidated = true))
    data.assertNoFailures()
  }

  @Test
  fun `a stale fetch that a sent change is applied to lands stale`() = runTest {
    val data = testDataScope(clock = testClock(START), senders = setOf(Tags.sender { _, _ -> }))
    val truth = QueryStore.inMemory(Fetched(listOf("a"), START - 1.hours))
    val stores = data.optimisticStores(Tags, base = { _: String -> truth }, apply = ::addOnce)
    val store = stores("k")
    store.mutate(Add("b")).awaitSucceeded()
    assertThat(stores.committedRecords).isEqualTo(1)

    store.writeFetched(Fetched(listOf("a"), START - 1.minutes, invalidated = true)) { _, _ ->
      false
    }

    assertThat(truth.data.value)
      .isEqualTo(Fetched(listOf("a", "b"), START - 1.minutes, invalidated = true))
    data.assertNoFailures()
  }

  /** A fold that removes the value leaves no value to hold as fresh. */
  @Test
  fun `a stale fetch whose sent changes remove the value leaves no value`() = runTest {
    val data = testDataScope(clock = testClock(START), senders = setOf(Tags.sender { _, _ -> }))
    val truth = QueryStore.inMemory(Fetched(listOf("a"), START - 1.hours))
    val stores = data.optimisticStores(Tags, base = { _: String -> truth }, apply = ::addOrRemove)
    val store = stores("k")
    store.mutate(Add(REMOVE)).awaitSucceeded()
    assertThat(stores.committedRecords).isEqualTo(1)
    // The commit removed the value, so an old one is stored again for the fetch write to remove
    truth.write(Fetched(listOf("a"), START - 1.hours))
    assertThat(stores.committedRecords).isEqualTo(1)

    store.writeFetched(Fetched(listOf("a"), START - 1.minutes, invalidated = true)) { _, _ ->
      false
    }

    assertThat(truth.data.value).isNull()
    data.assertNoFailures()
  }
}

/** The value [apply] removes. */
private const val REMOVE = "remove"

/** [addOnce], except that [REMOVE] removes the value. */
private fun addOrRemove(
  current: List<String>?,
  add: Add,
): List<String>? =
  if (add.tag == REMOVE) {
    null
  } else {
    addOnce(current, add)
  }

private val START = Instant.fromEpochSeconds(1_700_000_000)

/** The tag lists' outbox, one list per key, whose server answers nothing. */
private val Tags = OutboxKey<KeyedMutation<String, Add>>("staleTags")

/** The tag lists' outbox, whose server answers with the list it holds. */
private val ReplyingTags =
  OutboxKey.replying<KeyedMutation<String, Add>, List<String>>("staleReplyingTags")
