// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import dev.zacsweers.fieldspottr.data.core.impl.knownChanges
import dev.zacsweers.fieldspottr.data.core.testing.LaggingOutboxStores
import dev.zacsweers.fieldspottr.data.core.testing.TestFailures
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/**
 * Local changes on a real multithreaded dispatcher, with stores whose insert is slow before or
 * after the row becomes visible, so the shared rows and the inserts race.
 */
class OptimisticQueryStoreLocalChangeTest {
  @Serializable data class Increment(val by: Int)

  @Serializable data class Tag(val tag: String)

  @Test
  fun `a change never shows twice while its row is stored`() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
      val failures = TestFailures()
      val data =
        DataScope.Factory(
            onFailure = failures,
            // Offline, so every change stays a row and the count is the number of changes made
            online = MutableStateFlow(false),
            stores = SlowReturningOutboxStores(),
            clock = Clock.System,
          )
          .create(scope, setOf(Count.exchangingSender { _, _ -> error("Offline") }))
      // Not idempotent, so a change applied twice shows in the count
      val count =
        data.optimisticStore(
          Count,
          base = QueryStore.inMemory(Fetched(0, START)),
          apply = { current, increment -> (current ?: 0) + increment.by },
        )
      val made = AtomicInteger(0)
      // Each value seen, with how many changes had been made when it arrived
      val seen = CopyOnWriteArrayList<Pair<Int, Int>>()
      val collector = scope.launch {
        count.data.collect { value -> seen += (value?.value ?: 0) to made.get() }
      }
      count.data.first { it != null }

      repeat(CHANGES) {
        made.incrementAndGet()
        count.mutate(Increment(1)).stored.await()
      }
      count.pending.first { it.size == CHANGES }
      count.data.first { it?.value == CHANGES }
      collector.cancel()

      // Each value counts at most the changes made before it arrived
      assertThat(seen.filter { (value, madeSoFar) -> value > madeSoFar }).isEmpty()
      assertThat(count.data.first()?.value).isEqualTo(CHANGES)
      failures.assertNone()
    } finally {
      scope.cancel()
    }
  }

  @Test
  fun `a change never leaves the view while a later insert runs`() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
      val dropped = mutableListOf<String>()
      // Each round has its own outbox, and the data scope drains them all from its creation
      val keys = List(TAG_ROUNDS) { OutboxKey<Tag>("tags$it") }
      val data =
        DataScope.Factory(
            onFailure = TestFailures(),
            online = MutableStateFlow(false),
            stores = SlowInsertStores(),
            clock = Clock.System,
          )
          .create(scope, keys.mapTo(mutableSetOf()) { key -> key.sender { _, _ -> } })
      repeat(TAG_ROUNDS) { round ->
        val tags =
          data.optimisticStore<List<String>, Tag>(
            keys[round],
            base = QueryStore.inMemory(Fetched(emptyList(), START)),
            apply = { current, tag ->
              val list = current.orEmpty()
              if (tag.tag in list) {
                list
              } else {
                list + tag.tag
              }
            },
          )
        val seen = CopyOnWriteArrayList<List<String>>()
        val collector = scope.launch { tags.data.collect { seen += it?.value.orEmpty() } }
        tags.data.first { it != null }
        // Fire and forget, as a user tapping fast does
        repeat(TAGS) { tags.mutate(Tag("t$it")) }
        tags.pending.first { it.size == TAGS }
        tags.data.first { it?.value?.size == TAGS }
        collector.cancel()
        // A tag that showed shows in every later value
        for (i in seen.indices) {
          for (tag in seen[i]) {
            if (seen.drop(i + 1).any { tag !in it }) {
              dropped += "round $round: $tag"
            }
          }
        }
      }
      assertThat(dropped.distinct()).isEmpty()
    } finally {
      scope.cancel()
    }
  }

  /** In-memory outbox stores whose insert waits a moment before it stores the row. */
  private class SlowInsertStores : OutboxStores {
    private val delegate = InMemoryOutboxStores()

    override fun <E> open(name: String, serializer: KSerializer<E>): OutboxStore<E> {
      val rows = delegate.open(name, serializer)
      return object : OutboxStore<E> by rows {
        override suspend fun insert(id: EntryId, key: String, payload: E) {
          delay(2.milliseconds)
          rows.insert(id, key, payload)
        }
      }
    }

    override suspend fun clearAll() = delegate.clearAll()
  }

  /** In-memory outbox stores whose insert returns a moment after it stored the row. */
  private class SlowReturningOutboxStores : OutboxStores {
    private val delegate = InMemoryOutboxStores()

    override fun <E> open(name: String, serializer: KSerializer<E>): OutboxStore<E> {
      val rows = delegate.open(name, serializer)
      return object : OutboxStore<E> by rows {
        override suspend fun insert(id: EntryId, key: String, payload: E) {
          rows.insert(id, key, payload)
          delay(2.milliseconds)
        }
      }
    }

    override suspend fun clearAll() = delegate.clearAll()
  }

  @Test
  fun `two retries of one failed row at once show it once`() = runBlocking {
    repeat(CALL_ROUNDS) { round ->
      withTags(round, online = true) { sets, tags, rejected, _ ->
        rejected.value = true
        tags.mutate(Tag("b")).await()
        val id = tags.pending.first { it.singleOrNull()?.status is OutboxStatus.Failed }.single().id
        rejected.value = false
        val calls = List(2) { async(Dispatchers.Default) { tags.retry(id).await() } }
        calls.awaitAll()
        tags.pending.first { it.isEmpty() }
        assertThat(tags.data.first()?.value, "round $round").isEqualTo(listOf("b"))
        // A call's watcher settles its change a moment after its handle settles, and the set
        // forgets the change then, since the list already lacks the row
        withTimeout(5.seconds) {
          while (sets.knownChanges != 0) {
            delay(1.milliseconds)
          }
        }
      }
    }
  }

  @Test
  fun `two retries of one failed row at once that both fail leave it out of view`() = runBlocking {
    repeat(CALL_ROUNDS) { round ->
      withTags(round, online = true) { sets, tags, rejected, stores ->
        rejected.value = true
        tags.mutate(Tag("b")).await()
        val id = tags.pending.first { it.singleOrNull()?.status is OutboxStatus.Failed }.single().id
        // Each requeue reads its row first, which fails here
        stores.failEntryReads = true
        val calls = List(2) { async(Dispatchers.Default) { tags.retry(id).await() } }
        calls.awaitAll()
        stores.failEntryReads = false
        // Each call's watcher removes its mark a moment after its handle settles. Then no mark is
        // left, and the list agrees the row failed, so the set forgets the change.
        withTimeout(5.seconds) {
          while (sets.knownChanges != 0) {
            delay(1.milliseconds)
          }
        }
        assertThat(tags.data.first()?.value, "round $round").isEqualTo(emptyList<String>())
        delay(20.milliseconds)
        assertThat(tags.data.first()?.value, "round $round").isEqualTo(emptyList<String>())
      }
    }
  }

  @Test
  fun `two discards of one row at once leave it out of view`() = runBlocking {
    repeat(CALL_ROUNDS) { round ->
      withTags(round, online = false) { _, tags, _, _ ->
        tags.mutate(Tag("b")).stored.await()
        val id = tags.pending.first { it.isNotEmpty() }.single().id
        val calls = List(2) { async(Dispatchers.Default) { tags.discard(id).await() } }
        val outcomes = calls.awaitAll()
        assertThat(outcomes.count { it == OperationState.Succeeded }, "round $round").isEqualTo(1)
        assertThat(tags.data.first()?.value, "round $round").isEqualTo(emptyList<String>())
        tags.pending.first { it.isEmpty() }
        assertThat(tags.data.first()?.value, "round $round").isEqualTo(emptyList<String>())
      }
    }
  }

  /**
   * Runs [block] with a tag set on `Dispatchers.Default`, its single key's store, a flag that makes
   * sends fail for good, and its stores. A collector keeps the shared row list running.
   */
  private suspend fun withTags(
    round: Int,
    online: Boolean,
    block:
      suspend CoroutineScope.(
        sets: OptimisticQueryStores<Unit, List<String>, Tag>,
        tags: OptimisticQueryStore<List<String>, Tag>,
        rejected: MutableStateFlow<Boolean>,
        stores: LaggingOutboxStores,
      ) -> Unit,
  ) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
      val stores = LaggingOutboxStores()
      val rejected = MutableStateFlow(false)
      val key = OutboxKey<KeyedMutation<Unit, Tag>>("tags$round")
      val sender = key.sender { change, _ ->
        if (rejected.value) {
          throw PermanentFailure("Rejected ${change.mutation}")
        }
      }
      val sets =
        DataScope.Factory(
            onFailure = {},
            online = MutableStateFlow(online),
            stores = stores,
            clock = Clock.System,
          )
          .create(scope, setOf(sender))
          .optimisticStores<Unit, List<String>, Tag>(
            key,
            base = { QueryStore.inMemory(Fetched(emptyList(), START)) },
            apply = { current, tag ->
              val list = current.orEmpty()
              if (tag.tag in list) {
                list
              } else {
                list + tag.tag
              }
            },
          )
      val tags = sets(Unit)
      val collector = scope.launch { tags.data.collect {} }
      tags.data.first { it != null }
      coroutineScope { block(sets, tags, rejected, stores) }
      collector.cancel()
    } finally {
      scope.cancel()
    }
  }

  private companion object {
    const val CALL_ROUNDS = 20
    const val CHANGES = 50
    const val TAGS = 50
    const val TAG_ROUNDS = 20
    val START: Instant = Instant.fromEpochSeconds(1_700_000_000)
    val Count = OutboxKey.replying<Increment, Int>("count")
  }
}
