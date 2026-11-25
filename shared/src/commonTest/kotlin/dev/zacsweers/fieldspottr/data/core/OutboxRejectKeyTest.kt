// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isSameInstanceAs
import dev.zacsweers.fieldspottr.data.core.testing.Add
import dev.zacsweers.fieldspottr.data.core.testing.TestSend
import dev.zacsweers.fieldspottr.data.core.testing.addOnce
import dev.zacsweers.fieldspottr.data.core.testing.awaitFailed
import dev.zacsweers.fieldspottr.data.core.testing.awaitSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.testClock
import dev.zacsweers.fieldspottr.data.core.testing.testDataScope
import dev.zacsweers.fieldspottr.data.core.testing.testDataScopeFactory
import kotlin.test.Test
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** Reject keys on outbox entries: which calls a key refuses, and when a key is free again. */
class OutboxRejectKeyTest {
  private val send = TestSend<String>()
  private val tagSend = TestSend<Add>()

  /**
   * An apply on an eager dispatcher throws a [CommandRejectedException] with the call's own reject
   * key, so the command fails before the call returns. The call ran apply and the failure handler
   * hears of it, so the handle and its stored milestone must fail with that same instance.
   */
  @Test
  fun `an apply that throws its own CommandRejectedException keeps it as the handle's cause on an eager dispatcher`() =
    runTest {
      val own = CommandRejectedException("k")
      var applied = 0
      val data =
        testDataScope(
          eager = true,
          clock = testClock(START),
          online = false,
          senders = setOf(Changes.sender(send = send)),
        )
      val outbox =
        data.outbox(
          Changes,
          apply = { _, _ ->
            applied++
            throw own
          },
          revert = send::revert,
        )

      val handle = outbox.enqueue("a", admission = Admission.Reject("k"))
      handle.await()
      handle.stored.await()
      runCurrent()

      assertThat(applied).isEqualTo(1)
      assertThat(data.actionFailures.take()).containsExactly(own)
      assertThat((handle.state.value as? OperationState.Failed)?.cause).isSameInstanceAs(own)
      assertThat((handle.stored.state.value as? OperationState.Failed)?.cause).isSameInstanceAs(own)
    }

  /**
   * The holder's insert throws, and a call on another line with its key arrives while that insert
   * runs. The runner rejects that call. Once the holder fails, its key is free, so the next call
   * with the key is stored.
   */
  @Test
  fun `a holder whose insert throws frees its reject key`() = runTest {
    val rows = InMemoryOutboxStores().open("rows", Rows.serializer)
    var failNext = true
    val failing =
      object : OutboxStore<String> by rows {
        override suspend fun insert(id: EntryId, key: String, payload: String, rejectKey: String) {
          if (failNext) {
            failNext = false
            error("Disk full")
          }
          rows.insert(id, key, payload, rejectKey)
        }
      }
    val data =
      testDataScope(
        clock = testClock(START),
        online = false,
        senders = setOf(Rows.sender(store = failing, send = send)),
      )
    var applied = 0
    val outbox = data.outbox(Rows, apply = { _, _ -> applied++ }, revert = { _, _ -> applied-- })

    val holder = outbox.enqueue("a", admission = Admission.Reject("k"))
    val next = outbox.enqueue("b", Ordering.Keyed("y"), Admission.Reject("k"))
    holder.awaitFailed<IllegalStateException>()

    // b was submitted while a's insert was running, so the runner rejected it
    assertThat(next.awaitFailed<CommandRejectedException>().key).isEqualTo("k")
    outbox.enqueue("c", Ordering.Keyed("y"), Admission.Reject("k")).stored.awaitSucceeded()
    runCurrent()
    // a's apply was reverted and c's stands
    assertThat(applied).isEqualTo(1)
    assertThat(rows.entries().first().map { it.payload }).containsExactly("c")
    assertThat(data.actionFailures.take().map { it.message }).containsExactly("Disk full")
  }

  /**
   * Values x and y of one set each store a holder of key k. A clear of x frees x's key and leaves
   * y's, so x takes a new change with k and y rejects one.
   */
  @Test
  fun `a clear frees its value's reject key and keeps another value's`() = runTest {
    val data =
      testDataScope(
        clock = testClock(START),
        online = false,
        senders = setOf(TagsByItem.sender { change, id -> tagSend(change.mutation, id) }),
      )
    val stores =
      data.optimisticStores<String, List<String>, Add>(
        TagsByItem,
        base = QueryStore.inMemoryByKey(),
        apply = ::addOnce,
      )
    stores.mutate("x", Add("b"), admission = Admission.Reject("k")).stored.awaitSucceeded()
    stores.mutate("y", Add("b"), admission = Admission.Reject("k")).stored.awaitSucceeded()

    stores("x").clear()
    val x = stores.mutate("x", Add("c"), admission = Admission.Reject("k"))
    val y = stores.mutate("y", Add("c"), admission = Admission.Reject("k"))

    x.stored.awaitSucceeded()
    assertThat(y.awaitFailed<CommandRejectedException>().key).isEqualTo("k")
    runCurrent()
    assertThat(stores("x").data.first()?.value).isEqualTo(listOf("c"))
    assertThat(stores("y").data.first()?.value).isEqualTo(listOf("b"))
  }

  /**
   * A row with key k is stored as Sending, as a process that died mid-send leaves it. A new data
   * scope's runner has never seen the key, so its read of the rows must reject a call with k.
   */
  @Test
  fun `a holder left Sending by a dead process still rejects in a new data scope`() = runTest {
    val scopes = testDataScopeFactory(clock = testClock(START), online = false)
    val rows = scopes.outboxStore<String>("notes")
    val id = newId()
    rows.insert(id, "", "a", "k")
    val row = checkNotNull(rows.entry(id).first())
    rows.update(row.copy(status = OutboxStatus.Sending), expected = OutboxStatus.Queued)

    val outbox = scopes.create(backgroundScope, setOf(Notes.sender(send = send))).outbox(Notes)
    val refused = outbox.enqueue("b", admission = Admission.Reject("k"))

    assertThat(refused.awaitFailed<CommandRejectedException>().key).isEqualTo("k")
    runCurrent()
    assertThat(rows.entries().first().map { it.payload }).containsExactly("a")
  }

  @OptIn(ExperimentalUuidApi::class) private fun newId() = EntryId(Uuid.random().toString())

  private companion object {
    val START = Instant.parse("2026-06-10T12:00:00Z")
    val Changes = OutboxKey<String>("changes")
    val Notes = OutboxKey<String>("notes")
    /** The outbox of the test that keeps its rows in a store of its own. */
    val Rows = OutboxKey<String>("rows")
    val TagsByItem = OutboxKey<KeyedMutation<String, Add>>("tagsByItem")
  }
}
