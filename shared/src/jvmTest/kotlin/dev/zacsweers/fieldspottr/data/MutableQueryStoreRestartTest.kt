// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import dev.zacsweers.fieldspottr.data.core.DataScope
import dev.zacsweers.fieldspottr.data.core.EntryId
import dev.zacsweers.fieldspottr.data.core.Fetched
import dev.zacsweers.fieldspottr.data.core.InMemoryQueryStore
import dev.zacsweers.fieldspottr.data.core.MutableQueryStore
import dev.zacsweers.fieldspottr.data.core.OutboxStatus
import dev.zacsweers.fieldspottr.data.core.QueryStore
import dev.zacsweers.fieldspottr.data.core.invoke
import dev.zacsweers.fieldspottr.data.core.mutableStore
import dev.zacsweers.fieldspottr.data.core.testing.TestFailures
import dev.zacsweers.fieldspottr.data.core.testing.TestSend
import dev.zacsweers.fieldspottr.data.core.testing.testClock
import dev.zacsweers.metro.suspendLazy
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.junit.Rule

/**
 * A mutable store whose rows live in the app's SQLite database, across two owners in turn, the way
 * a process restart leaves them. The truth is one in-memory store both owners share, standing in
 * for a durable one, since the rows are what these tests check.
 */
class MutableQueryStoreRestartTest {
  @get:Rule val temporaryDatabase = TemporaryDatabase()

  @Serializable data class Add(val tag: String)

  private val send = TestSend<Add>()
  // Sends run on the owner's dispatcher, so the record is safe to write from any thread
  private val sentIds = CopyOnWriteArrayList<EntryId>()
  // How many send attempts have started, for a test to wait on
  private val attempts = MutableStateFlow(0)
  private val online = MutableStateFlow(false)
  private val truth: InMemoryQueryStore<List<String>> =
    QueryStore.inMemory(Fetched(listOf("a"), START))

  /** A new factory, like a new process: its own name claims, the same rows and network state. */
  private fun TestScope.process(): DataScope.Factory =
    DataScope.Factory(
      onFailure = TestFailures(),
      onActionFailure = TestFailures(),
      online = online,
      stores = SqlDelightOutboxStores(suspendLazy { temporaryDatabase.db() }, Dispatchers.IO),
      clock = testClock(START),
    )

  /** A scope that ends with the test, or earlier when the test cancels it. */
  private fun TestScope.owner(): CoroutineScope =
    CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext.job))

  private fun DataScope.tags(): MutableQueryStore<List<String>, Add> =
    mutableStore<List<String>, Add>(
      name = "tags",
      base = truth,
      apply = { current, add -> current.orEmpty() + add.tag },
      send = { add, id ->
        sentIds += id
        attempts.update { it + 1 }
        send(add, id)
      },
    )

  @Test
  fun `rows outlive their owner and send in order after a restart`() = runTest {
    val first = owner()
    val before = process().create(first).tags()
    before.mutate(Add("b"))
    before.mutate(Add("c"))
    val waiting = before.pending.first { it.size == 2 }
    assertThat(before.data.first()?.value).isEqualTo(listOf("a", "b", "c"))

    first.cancel()

    val after = process().create(owner()).tags()
    // The rows are back from the database, so the changes still show before anything sends
    assertThat(after.data.first()?.value).isEqualTo(listOf("a", "b", "c"))
    online.value = true
    after.pending.first { it.isEmpty() }

    assertThat(send.sent).containsExactly(Add("b"), Add("c"))
    assertThat(sentIds).isEqualTo(waiting.map { it.id })
    assertThat(truth.data.value).isEqualTo(Fetched(listOf("a", "b", "c"), START))
    assertThat(after.data.first()?.value).isEqualTo(listOf("a", "b", "c"))
  }

  @Test
  fun `a change left sending is sent again with the same id`() = runTest {
    online.value = true
    send.hold(Add("b"))
    val first = owner()
    val before = process().create(first).tags()
    before.mutate(Add("b"))
    // The send has started and is waiting on the held gate
    attempts.first { it == 1 }
    val sending =
      before.pending.first { it.singleOrNull()?.status == OutboxStatus.Sending }.single()

    // The owner dies with the send in flight, so the row stays Sending
    first.cancel()
    send.release(Add("b"))

    val after = process().create(owner()).tags()
    after.pending.first { it.isEmpty() }

    assertThat(send.attempts).containsExactly(Add("b"), Add("b"))
    assertThat(send.sent).containsExactly(Add("b"))
    // Both attempts carried the row's id, so the server can tell the retry from a new change
    assertThat(sentIds).containsExactly(sending.id, sending.id)
    assertThat(after.data.first()?.value).isEqualTo(listOf("a", "b"))
  }
}

private val START = Instant.fromEpochSeconds(1_700_000_000)
