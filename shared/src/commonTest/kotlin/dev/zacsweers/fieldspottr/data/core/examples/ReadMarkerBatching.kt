// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core.examples

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import dev.zacsweers.fieldspottr.data.core.DataScope
import dev.zacsweers.fieldspottr.data.core.OnPrevious
import dev.zacsweers.fieldspottr.data.core.Operation
import dev.zacsweers.fieldspottr.data.core.Ordering
import dev.zacsweers.fieldspottr.data.core.testing.awaitFailed
import dev.zacsweers.fieldspottr.data.core.testing.awaitSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.testDataScope
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

// The guide's read-marker batching example, built and tested.

class ReadMarkerRepository(
  data: DataScope,
  private val db: ChannelQueries,
  private val api: ChatApi,
  private val io: CoroutineDispatcher,
  private val online: StateFlow<Boolean>,
) {
  private val actions = data.newActionRunner()
  // channelId to newest read ts
  private val pending = MutableStateFlow(persistentMapOf<String, Long>())

  private data object Flush

  /** Channels whose newest read marker the server has not acknowledged yet. */
  val unsynced: Flow<Set<String>> = pending.map { it.keys }.distinctUntilChanged()

  fun markRead(channelId: String, readTs: Long): Operation {
    // Replace by id, never regress
    pending.update { it.withNewest(channelId, readTs) }
    // Local and durable, at once. The query keeps the greater time, so it never regresses either.
    actions.run(Ordering.Keyed(channelId)) { withContext(io) { db.setLastRead(channelId, readTs) } }
    // One flush per burst
    return actions.run(Ordering.Keyed(Flush, OnPrevious.Supersede)) {
      delay(1.seconds)
      // Offline: hold the key, let later flushes collapse
      online.first { it }
      // Take everything, atomically
      val batch = pending.getAndUpdate { persistentMapOf() }
      if (batch.isEmpty()) {
        return@run
      }
      try {
        // One request for the whole batch
        api.markRead(batch)
      } catch (t: Throwable) {
        // Put it back. Each channel keeps the newer of its batch time and any mark since.
        pending.update { current ->
          batch.entries.fold(current) { merged, (channelId, readTs) ->
            merged.withNewest(channelId, readTs)
          }
        }
        throw t
      }
    }
  }

  private fun PersistentMap<String, Long>.withNewest(channelId: String, readTs: Long) =
    putting(channelId, maxOf(readTs, get(channelId) ?: 0L))
}

interface ChatApi {
  suspend fun markRead(batch: Map<String, Long>)
}

/** Stands in for the SQLDelight queries. */
class ChannelQueries {
  val lastRead = mutableMapOf<String, Long>()

  /** Like `UPDATE channel SET lastRead = max(lastRead, :readTs) WHERE id = :channelId`. */
  fun setLastRead(channelId: String, readTs: Long) {
    lastRead[channelId] = maxOf(readTs, lastRead[channelId] ?: 0L)
  }
}

/** Records each batch. While [reply] is set, a call waits for it and throws what it fails with. */
class FakeChatApi : ChatApi {
  val batches = mutableListOf<Map<String, Long>>()
  var reply: CompletableDeferred<Unit>? = null

  override suspend fun markRead(batch: Map<String, Long>) {
    batches += batch
    reply?.await()
  }
}

class ReadMarkerBatchingTest {
  private val db = ChannelQueries()
  private val api = FakeChatApi()

  private fun TestScope.repository(): ReadMarkerRepository {
    val data = testDataScope()
    return ReadMarkerRepository(
      data,
      db,
      api,
      StandardTestDispatcher(testScheduler),
      MutableStateFlow(true),
    )
  }

  /**
   * Marks each of [first], starts their flush and holds its request, marks each of [during], then
   * fails the request. Returns the first flush and the retry.
   */
  private fun TestScope.failWhileMarking(
    markers: ReadMarkerRepository,
    first: Map<String, Long>,
    during: Map<String, Long>,
  ): Pair<Operation, Operation> {
    val held = CompletableDeferred<Unit>()
    api.reply = held
    val flush = first.map { (channelId, readTs) -> markers.markRead(channelId, readTs) }.last()
    advanceTimeBy(1.5.seconds)
    assertThat(api.batches).containsExactly(first)

    val retry = during.map { (channelId, readTs) -> markers.markRead(channelId, readTs) }.last()
    runCurrent()
    api.reply = null
    held.completeExceptionally(IllegalStateException("Offline"))
    return flush to retry
  }

  @Test
  fun `an older mark during a failed send keeps the newer time for the retry`() = runTest {
    val markers = repository()

    val (flush, retry) =
      failWhileMarking(markers, first = mapOf("general" to 100L), during = mapOf("general" to 90L))

    flush.awaitFailed<IllegalStateException>()
    retry.awaitSucceeded()
    assertThat(api.batches.last()).isEqualTo(mapOf("general" to 100L))
    assertThat(db.lastRead).isEqualTo(mapOf("general" to 100L))
    assertThat(markers.unsynced.first()).isEmpty()
  }

  @Test
  fun `a newer mark during a failed send is what the retry sends`() = runTest {
    val markers = repository()

    val (flush, retry) =
      failWhileMarking(markers, first = mapOf("general" to 100L), during = mapOf("general" to 110L))

    flush.awaitFailed<IllegalStateException>()
    retry.awaitSucceeded()
    assertThat(api.batches.last()).isEqualTo(mapOf("general" to 110L))
    assertThat(db.lastRead).isEqualTo(mapOf("general" to 110L))
  }

  @Test
  fun `marks for different channels all survive a failed send`() = runTest {
    val markers = repository()

    val (flush, retry) =
      failWhileMarking(
        markers,
        first = mapOf("general" to 100L, "random" to 50L),
        during = mapOf("design" to 70L),
      )

    flush.awaitFailed<IllegalStateException>()
    retry.awaitSucceeded()
    assertThat(api.batches.last())
      .isEqualTo(mapOf("general" to 100L, "random" to 50L, "design" to 70L))
  }
}
