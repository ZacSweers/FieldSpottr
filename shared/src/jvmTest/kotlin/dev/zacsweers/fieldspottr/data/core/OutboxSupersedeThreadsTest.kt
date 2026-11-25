// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import dev.zacsweers.fieldspottr.data.core.impl.keysSuperseding
import dev.zacsweers.fieldspottr.data.core.impl.rowsFollowed
import dev.zacsweers.fieldspottr.data.core.impl.timersHeld
import dev.zacsweers.fieldspottr.data.core.testing.Add
import dev.zacsweers.fieldspottr.data.core.testing.TestFailures
import dev.zacsweers.fieldspottr.data.core.testing.TestThread
import dev.zacsweers.fieldspottr.data.core.testing.addOnce
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Entries with [OnPrevious.Supersede] on one line while real threads enqueue, the network flaps and
 * a caller discards earlier entries. Nothing is both sent and reverted, and nothing leaks once the
 * line drains.
 */
class OutboxSupersedeThreadsTest {
  /**
   * Each round, threads enqueue on line k with Supersede while one thread flaps the network and
   * another discards random entries of the round. Each entry sends or reverts, once. The round's
   * newest entry sends unless the caller discarded it. Once the line drains, the outbox holds no
   * superseding entry, followed row or timer. The engine must supersede an entry in some round,
   * some round must send several entries, and caller discards must both win and lose, so the race
   * ran.
   */
  @Test
  fun `threads superseding on one line send or revert each entry once and leak nothing`() =
    runBlocking {
      val failures = TestFailures()
      val handleFailures = ConcurrentLinkedQueue<Throwable>()
      val online = MutableStateFlow(true)
      val sent = ConcurrentLinkedQueue<String>()
      val reverted = ConcurrentLinkedQueue<String>()
      val applied = ConcurrentLinkedQueue<String>()
      val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val data =
          DataScope.Factory(onFailure = failures, online = online, stores = InMemoryOutboxStores())
            .create(
              owner,
              setOf(
                Notes.sender { payload, _ ->
                  delay(Random.nextLong(0, 2))
                  sent += payload
                }
              ),
            )
        val outbox =
          data.outbox(
            Notes,
            apply = { payload, _ -> applied += payload },
            revert = { payload, _ -> reverted += payload },
            onFailure = { handleFailures += it },
          )
        val callerDiscarded = ConcurrentHashMap.newKeySet<String>()
        val all = ConcurrentHashMap<String, OutboxOperation>()
        // Counts that show the race ran
        var supersededSome = 0
        var sentSeveral = 0
        val discardsWon = AtomicInteger()
        val discardsLost = AtomicInteger()
        repeat(ROUNDS) { round ->
          val barrier = CyclicBarrier(THREADS + 2)
          val handles = ConcurrentLinkedQueue<Pair<String, OutboxOperation>>()
          val threads =
            List(THREADS) { index ->
              TestThread {
                barrier.await(10, SECONDS)
                repeat(PER_THREAD) { n ->
                  val payload = "$round-$index-$n"
                  val handle = outbox.enqueue(payload, Superseding)
                  handles += payload to handle
                  all[payload] = handle
                }
              }
            } +
              TestThread {
                barrier.await(10, SECONDS)
                repeat(4) {
                  online.value = Random.nextBoolean()
                  Thread.sleep(1)
                }
                online.value = false
              } +
              TestThread {
                barrier.await(10, SECONDS)
                runBlocking {
                  repeat(3) { attempt ->
                    // Waits for entries of this round, so the discard races the engine's
                    val until = System.nanoTime() + 5_000_000
                    while (handles.size < 2 + attempt * 4 && System.nanoTime() < until) {
                      Thread.yield()
                    }
                    val (payload, handle) = handles.randomOrNull() ?: return@repeat
                    if (outbox.discard(handle.id).await() == OperationState.Succeeded) {
                      callerDiscarded += payload
                      discardsWon.incrementAndGet()
                    } else {
                      discardsLost.incrementAndGet()
                    }
                  }
                }
              }
          threads.forEach { it.joinOrFail(10_000) }
          withTimeout(10_000) { handles.forEach { it.second.stored.await() } }
          online.value = true
          withTimeout(10_000) { handles.forEach { it.second.await() } }
          // Nothing enqueues after the round's newest entry, so only the caller may discard it
          val newest = applied.last()
          if (newest !in callerDiscarded) {
            assertThat(all.getValue(newest).state.value, "round $round newest $newest")
              .isEqualTo(OperationState.Succeeded)
          }
          val superseded = handles.any { (payload, handle) ->
            payload !in callerDiscarded && handle.state.value == OperationState.Cancelled
          }
          if (superseded) {
            supersededSome++
          }
          if (handles.count { it.second.state.value == OperationState.Succeeded } > 1) {
            sentSeveral++
          }
        }
        // A discarded handle settles before its revert runs
        withTimeout(10_000) {
          while (sent.size + reverted.size < all.size) {
            delay(5)
          }
        }
        val sentSet = sent.toSet()
        assertThat(sent.size, "sent twice").isEqualTo(sentSet.size)
        assertThat(sentSet.intersect(reverted.toSet()), "sent and reverted").isEmpty()
        assertThat(reverted.size, "reverted twice").isEqualTo(reverted.toSet().size)
        for ((payload, handle) in all) {
          when (val state = handle.state.value) {
            OperationState.Succeeded -> assertThat(payload in sentSet, payload).isTrue()
            OperationState.Cancelled -> assertThat(payload in reverted, payload).isTrue()
            else -> throw AssertionError("$payload settled $state")
          }
        }
        assertThat(sentSet + reverted.toSet()).isEqualTo(all.keys.toSet())
        assertThat(supersededSome, "rounds where the engine superseded").isGreaterThan(0)
        assertThat(sentSeveral, "rounds that sent several entries").isGreaterThan(0)
        assertThat(discardsWon.get(), "caller discards that won").isGreaterThan(0)
        assertThat(discardsLost.get(), "caller discards that lost").isGreaterThan(0)
        // The drain that sent the last row reads the empty line once more
        withTimeout(10_000) {
          while (outbox.keysSuperseding != 0 || outbox.rowsFollowed != 0) {
            delay(5)
          }
        }
        assertThat(outbox.timersHeld).isEqualTo(0)
        for (failure in handleFailures) {
          assertThat(failure, "handle failure").isInstanceOf<IllegalStateException>()
        }
        failures.assertNone()
      } finally {
        owner.cancel()
      }
    }

  /**
   * Each round, threads mutate one optimistic value with Supersede on line x, and the network goes
   * on or off between rounds. Once the line drains, pending is empty and the value holds exactly
   * the sent changes.
   */
  @Test
  fun `threads superseding one optimistic value end with the sent changes only`() = runBlocking {
    val failures = TestFailures()
    val online = MutableStateFlow(false)
    val sent = ConcurrentLinkedQueue<String>()
    val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
      val sets =
        DataScope.Factory(onFailure = failures, online = online, stores = InMemoryOutboxStores())
          .create(owner, setOf(TagsByItem.sender { change, _ -> sent += change.mutation.tag }))
          .optimisticStores<String, List<String>, Add>(
            TagsByItem,
            base = { QueryStore.inMemory(Fetched(emptyList(), Clock.System.now())) },
            apply = ::addOnce,
          )
      val store = sets("v")
      repeat(ROUNDS) { round ->
        val barrier = CyclicBarrier(THREADS)
        val handles = ConcurrentLinkedQueue<OutboxOperation>()
        val threads =
          List(THREADS) { index ->
            TestThread {
              barrier.await(10, SECONDS)
              repeat(PER_THREAD) { n ->
                handles += store.mutate(Add("$round-$index-$n"), SupersedingX)
              }
            }
          }
        threads.forEach { it.joinOrFail(10_000) }
        withTimeout(10_000) { handles.forEach { it.stored.await() } }
        online.value = round % 2 == 0
      }
      online.value = true
      withTimeout(10_000) { store.pending.first { it.isEmpty() } }
      // The value may show the last sent change after pending is empty
      val value =
        withTimeout(10_000) { store.data.first { it?.value?.size == sent.size } }?.value.orEmpty()
      assertThat(value).containsExactlyInAnyOrder(*sent.toTypedArray())
      failures.assertNone()
    } finally {
      owner.cancel()
    }
  }

  private companion object {
    const val ROUNDS = 100
    const val THREADS = 4
    const val PER_THREAD = 5
    val Notes = OutboxKey<String>("notes")
    val TagsByItem = OutboxKey<KeyedMutation<String, Add>>("tagsByItem")
    val Superseding = Ordering.Keyed("k", OnPrevious.Supersede)
    val SupersedingX = Ordering.Keyed("x", OnPrevious.Supersede)
  }
}
