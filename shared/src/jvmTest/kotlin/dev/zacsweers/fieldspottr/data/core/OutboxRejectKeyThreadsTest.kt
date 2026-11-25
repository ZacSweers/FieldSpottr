// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThanOrEqualTo
import dev.zacsweers.fieldspottr.data.core.testing.Add
import dev.zacsweers.fieldspottr.data.core.testing.TestFailures
import dev.zacsweers.fieldspottr.data.core.testing.TestThread
import dev.zacsweers.fieldspottr.data.core.testing.addOnce
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Reject keys on outbox entries while real threads enqueue with one key on two lines. At no point
 * may two unsettled rows hold one key.
 */
class OutboxRejectKeyThreadsTest {
  /**
   * Each round, threads on lines x and y enqueue with key k while another thread discards the
   * holder of k. At most one unsettled row holds k after the round, and every call that stored
   * nothing failed with k. At least one round must admit a thread after the discard frees the key,
   * so the race ran.
   */
  @Test
  fun `threads on two lines with a discarding thread never store two holders of one key`() =
    runBlocking {
      val stores = InMemoryOutboxStores()
      val failures = TestFailures()
      val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val data =
          DataScope.Factory(onFailure = failures, online = MutableStateFlow(false), stores = stores)
            .create(owner, setOf(Notes.sender { _, _ -> }))
        val outbox = data.outbox(Notes)
        val rows = stores.open(Notes.name, Notes.serializer)
        var holder = outbox.enqueue("h", admission = Admission.Reject("k"))
        withTimeout(10_000) { holder.stored.await() }
        // Rounds where a thread was admitted after the discard freed the key, so the race ran
        var admittedAfterDiscard = 0
        repeat(ROUNDS) { round ->
          val barrier = CyclicBarrier(THREADS + 1)
          val handles = ConcurrentLinkedQueue<OutboxOperation>()
          val current = holder
          val threads =
            List(THREADS) { index ->
              TestThread {
                barrier.await(10, SECONDS)
                val line = if (index % 2 == 0) "x" else "y"
                handles +=
                  outbox.enqueue("$round-$index", Ordering.Keyed(line), Admission.Reject("k"))
              }
            } +
              TestThread {
                barrier.await(10, SECONDS)
                runBlocking { outbox.discard(current.id).await() }
              }
          threads.forEach { it.joinOrFail(10_000) }
          withTimeout(10_000) { handles.forEach { it.stored.await() } }
          val unsettled =
            rows.entries().first().filter { it.rejectKey == "k" && !it.status.isSettled }
          assertThat(unsettled.size, "round $round").isLessThanOrEqualTo(1)
          val stored = handles.filter { it.stored.state.value == OperationState.Succeeded }
          if (stored.isNotEmpty()) {
            admittedAfterDiscard++
          }
          for (handle in handles - stored.toSet()) {
            val cause = (handle.stored.state.value as? OperationState.Failed)?.cause
            assertThat(
                (cause as? CommandRejectedException)?.key,
                "round $round: ${handle.stored.state.value}",
              )
              .isEqualTo("k")
          }
          // Next round's holder: the stored one, or a fresh one when the discard won last
          holder =
            stored.singleOrNull()
              ?: (unsettled.singleOrNull()?.let { row -> handles.first { it.id == row.id } }
                ?: outbox.enqueue("h$round", admission = Admission.Reject("k")).also {
                  withTimeout(10_000) { it.stored.await() }
                })
        }
        assertThat(admittedAfterDiscard).isGreaterThan(0)
        failures.assertNone()
      } finally {
        owner.cancel()
      }
    }

  /**
   * Each round, threads on lines x and y mutate values v and w of one set with key k. Each value
   * keeps exactly one unsettled holder of its own key after the round. The round's last step
   * discards both holders, which frees the values for the next round.
   */
  @Test
  fun `threads on two lines of one optimistic value never store two holders of one key`() =
    runBlocking {
      val stores = InMemoryOutboxStores()
      val failures = TestFailures()
      val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val data =
          DataScope.Factory(onFailure = failures, online = MutableStateFlow(false), stores = stores)
            .create(owner, setOf(TagsByItem.sender { _, _ -> }))
        val sets =
          data.optimisticStores<String, List<String>, Add>(
            TagsByItem,
            base = QueryStore.inMemoryByKey(),
            apply = ::addOnce,
          )
        val rows = stores.open(TagsByItem.name, TagsByItem.serializer)
        repeat(ROUNDS) { round ->
          val barrier = CyclicBarrier(THREADS)
          val handles = ConcurrentLinkedQueue<OutboxOperation>()
          val threads =
            List(THREADS) { index ->
              TestThread {
                barrier.await(10, SECONDS)
                val line = if (index % 2 == 0) "x" else "y"
                val value = if (index % 3 == 0) "w" else "v"
                val ordering = Ordering.Keyed(line)
                handles += sets.mutate(value, Add("$round-$index"), ordering, Admission.Reject("k"))
              }
            }
          threads.forEach { it.joinOrFail(10_000) }
          withTimeout(10_000) { handles.forEach { it.stored.await() } }
          val unsettled =
            rows.entries().first().filter { it.rejectKey != null && !it.status.isSettled }
          // One holder per value, so the two values never share a key
          assertThat(unsettled.size, "round $round").isEqualTo(2)
          for ((key, held) in unsettled.groupBy { it.rejectKey }) {
            assertThat(held.size, "round $round $key").isEqualTo(1)
          }
          // Frees both values for the next round
          for (row in unsettled) {
            assertThat(sets(row.payload.key).discard(row.id).await(), "round $round")
              .isEqualTo(OperationState.Succeeded)
          }
        }
        failures.assertNone()
      } finally {
        owner.cancel()
      }
    }

  private companion object {
    const val ROUNDS = 200
    const val THREADS = 6
    val Notes = OutboxKey<String>("notes")
    val TagsByItem = OutboxKey<KeyedMutation<String, Add>>("tagsByItem")
  }
}
