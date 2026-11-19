// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.messageContains
import dev.zacsweers.fieldspottr.data.core.testing.Add
import dev.zacsweers.fieldspottr.data.core.testing.TestFailures
import dev.zacsweers.fieldspottr.data.core.testing.addOnce
import dev.zacsweers.fieldspottr.data.core.testing.testClock
import kotlin.test.Test
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** Retry and discard on a key's store, which act only on that key's rows. */
class OptimisticQueryStoreRowCallsTest {
  /** Fails unless [operation] failed for naming row [id] of another key, with no payload. */
  private fun assertOtherKey(operation: OperationState?, id: EntryId) {
    val failed = operation as? OperationState.Failed
    assertThat(failed?.cause)
      .isNotNull()
      .isInstanceOf<IllegalArgumentException>()
      .messageContains(id.value)
    assertThat(failed?.cause?.message.orEmpty()).doesNotContain("secret")
  }

  @Test
  fun `a key's store rejects a row of another key`() = runTest {
    val failures = TestFailures()
    val stores = InMemoryOutboxStores()
    val sets =
      DataScope.Factory(
          onFailure = failures,
          online = MutableStateFlow(false),
          stores = stores,
          clock = testClock(START),
        )
        .create(backgroundScope, setOf(Tags.sender { _, _ -> }))
        .optimisticStores(
          Tags,
          base = { QueryStore.inMemory(Fetched(listOf("a"), START)) },
          apply = ::addOnce,
        )
    val handle = sets("x").mutate(Add("secret"))
    handle.stored.await()
    val id = handle.id

    assertOtherKey(sets("y").discard(id).state.value, id)
    assertOtherKey(sets("y").retry(id).state.value, id)
    runCurrent()

    val rows = stores.open("rowCallTags", Tags.serializer).entries().first()
    assertThat(rows.single().status).isEqualTo(OutboxStatus.Queued)
    assertThat(sets("x").data.first()?.value).isEqualTo(listOf("a", "secret"))
    // The set's own calls act on any key
    assertThat(sets.discard(id).await()).isEqualTo(OperationState.Succeeded)
    assertThat(sets("x").data.first()?.value).isEqualTo(listOf("a"))
    failures.assertNone()
  }
}

private val START = Instant.fromEpochSeconds(1_700_000_000)

/** The tag lists' outbox, one list per key, whose server answers nothing. */
private val Tags = OutboxKey<KeyedMutation<String, Add>>("rowCallTags")
