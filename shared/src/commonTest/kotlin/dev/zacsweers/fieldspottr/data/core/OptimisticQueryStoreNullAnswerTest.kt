// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import dev.zacsweers.fieldspottr.data.core.testing.TestClock
import dev.zacsweers.fieldspottr.data.core.testing.TestFailures
import dev.zacsweers.fieldspottr.data.core.testing.testClock
import kotlin.concurrent.Volatile
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer

/**
 * A server that answers a change with null on a store whose value type is nullable. A stored null
 * is a value, so the view tells it apart from nothing stored.
 */
class OptimisticQueryStoreNullAnswerTest {
  private val failures = TestFailures()
  private val base = HeldStore(QueryStore.inMemory<String?>())
  private lateinit var clock: TestClock
  private lateinit var store: OptimisticQueryStore<String?, String>
  private val seen = mutableListOf<Fetched<String?>?>()

  /** An empty nullable value, collected, whose server answers each change with null. */
  private fun TestScope.nullAnswers() {
    clock = testClock(START)
    val data =
      DataScope.Factory(onFailure = failures, online = MutableStateFlow(true), clock = clock)
        .create(
          backgroundScope,
          setOf(Nullable.deliveringSender { _, _ -> Delivery.Replaced(null) }),
        )
    store = data.optimisticStore(Nullable, base = base, apply = { _, tag -> tag })
    backgroundScope.launch { store.data.collect { seen += it } }
    runCurrent()
    advanceTimeBy(1.seconds)
  }

  @Test
  fun `a null answer shows as a stored null while its base write is held`() = runTest {
    nullAnswers()
    val gate = CompletableDeferred<Unit>()
    base.gate = gate
    val sentAt = clock.now()
    store.mutate("b")
    runCurrent()

    assertThat(base.delegate.data.value).isEqualTo(null)
    assertThat(seen.last()).isEqualTo(Fetched(null, sentAt))

    gate.complete(Unit)
    runCurrent()
    assertThat(base.delegate.data.value).isEqualTo(Fetched(null, sentAt))
    assertThat(seen.last()).isEqualTo(Fetched(null, sentAt))
    failures.assertNone()
  }

  /**
   * Sends "b", whose commit fails to write the null answer, so the base still holds nothing. Then a
   * fetch that read the server before the send lands and folds the answer in. Returns the send
   * time.
   */
  private suspend fun TestScope.foldAfterFailedCommit(
    fetchWrite: suspend (sentAt: Instant) -> Unit
  ): Instant {
    nullAnswers()
    base.failWrites = true
    val sentAt = clock.now()
    store.mutate("b")
    runCurrent()
    assertThat(base.delegate.data.value).isEqualTo(null)
    assertThat(seen.last()).isEqualTo(Fetched(null, sentAt))
    base.failWrites = false
    fetchWrite(sentAt)
    return sentAt
  }

  @Test
  fun `a fetch folds a null answer in as a stored null`() = runTest {
    val sentAt = foldAfterFailedCommit { sent ->
      val gate = CompletableDeferred<Unit>()
      base.gate = gate
      val fetch = launch { store.writeFetched(Fetched("x", START)) { _, _ -> false } }
      runCurrent()

      // The record holds the fold, and the store still holds nothing
      assertThat(base.delegate.data.value).isEqualTo(null)
      assertThat(seen.last()).isEqualTo(Fetched(null, sent))

      gate.complete(Unit)
      fetch.join()
      runCurrent()
    }

    assertThat(base.delegate.data.value).isEqualTo(Fetched(null, sentAt))
    assertThat(seen.last()).isEqualTo(Fetched(null, sentAt))
  }

  @Test
  fun `a fold of a null answer whose write fails still shows the answer`() = runTest {
    val sentAt = foldAfterFailedCommit {
      base.failWrites = true
      assertFailure { store.writeFetched(Fetched("x", START)) { _, _ -> false } }
      runCurrent()
    }

    assertThat(base.delegate.data.value).isEqualTo(null)
    assertThat(seen.last()).isEqualTo(Fetched(null, sentAt))
  }

  /**
   * A store whose writes and clears wait on [gate] while it is set, and throw while [failWrites] is
   * set, before they reach [delegate].
   */
  private class HeldStore<T>(val delegate: InMemoryQueryStore<T>) : QueryStore<T> {
    @Volatile var gate: CompletableDeferred<Unit>? = null

    @Volatile var failWrites = false

    override val data: Flow<Fetched<T>?> = delegate.data

    override suspend fun writeFetched(fetched: Fetched<T>, keepsStored: KeepsStored) {
      gate?.await()
      check(!failWrites) { "The disk is full" }
      delegate.writeFetched(fetched, keepsStored)
    }

    override suspend fun clearFetched(keepsStored: KeepsStored) {
      gate?.await()
      check(!failWrites) { "The disk is full" }
      delegate.clearFetched(keepsStored)
    }

    override suspend fun invalidate() {
      delegate.invalidate()
    }
  }

  private companion object {
    val START: Instant = Instant.fromEpochSeconds(1_700_000_000)

    /** A plain string change whose server answers with a nullable string. */
    val Nullable =
      OutboxKey.replying("nullAnswer", String.serializer(), String.serializer().nullable)
  }
}
