// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEmpty
import dev.zacsweers.fieldspottr.data.core.testing.TestFailures
import dev.zacsweers.fieldspottr.data.core.testing.testClock
import kotlin.test.Test
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable

/**
 * The failures an optimistic store reports name a change by its row id. A change can hold private
 * data, so no message holds its payload.
 */
class OptimisticQueryStoreFailureMessageTest {
  @Serializable data class Add(val tag: String)

  private val failures = TestFailures()

  /** A tag list over [apply], which the server takes and answers with nothing. */
  private fun TestScope.tags(
    online: Boolean,
    apply: (List<String>?, Add) -> List<String>?,
  ): OptimisticQueryStore<List<String>, Add> =
    DataScope.Factory(
        onFailure = failures,
        online = MutableStateFlow(online),
        clock = testClock(START),
      )
      .create(backgroundScope, setOf(Tags.sender { _, _ -> }))
      .optimisticStore(Tags, base = QueryStore.inMemory(Fetched(listOf("a"), START)), apply = apply)

  /** Fails unless something was reported and no message holds the payload. */
  private fun assertNoPayload(id: EntryId) {
    val messages = failures.all.map { it.message.orEmpty() }
    assertThat(messages).isNotEmpty()
    for (message in messages) {
      assertThat(message).doesNotContain("secret")
    }
    assertThat(messages.any { id.value in it }).isEqualTo(true)
  }

  @Test
  fun `an apply that throws in a view is reported without its payload`() = runTest {
    val tags = tags(online = false) { _, _ -> throw IllegalStateException("boom") }
    backgroundScope.launch { tags.data.collect {} }
    val handle = tags.mutate(Add("secret"))
    handle.stored.await()
    runCurrent()
    assertNoPayload(handle.id)
  }

  @Test
  fun `an apply that throws at commit is reported without its payload`() = runTest {
    val tags = tags(online = true) { _, _ -> throw IllegalStateException("boom") }
    val handle = tags.mutate(Add("secret"))
    handle.await()
    runCurrent()
    assertNoPayload(handle.id)
  }

  @Test
  fun `an apply that is not idempotent is reported without its payload`() = runTest {
    val tags = tags(online = true) { current, add -> current.orEmpty() + add.tag }
    val handle = tags.mutate(Add("secret"))
    handle.await()
    runCurrent()
    assertNoPayload(handle.id)
  }

  @Test
  fun `an apply that throws in the idempotence check is reported without its payload`() = runTest {
    val tags =
      tags(online = true) { current, add ->
        check(add.tag !in current.orEmpty()) { "boom" }
        current.orEmpty() + add.tag
      }
    val handle = tags.mutate(Add("secret"))
    handle.await()
    runCurrent()
    assertThat(tags.data.first()?.value).isEqualTo(listOf("a", "secret"))
    assertNoPayload(handle.id)
  }
}

private val START = Instant.fromEpochSeconds(1_700_000_000)

/** The tag list's outbox, whose server answers nothing. */
private val Tags = OutboxKey<OptimisticQueryStoreFailureMessageTest.Add>("messageTags")
