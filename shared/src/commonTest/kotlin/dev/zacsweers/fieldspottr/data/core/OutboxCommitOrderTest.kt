// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import dev.zacsweers.fieldspottr.data.core.testing.TestFailures
import dev.zacsweers.fieldspottr.data.core.testing.awaitFailed
import dev.zacsweers.fieldspottr.data.core.testing.awaitSucceeded
import dev.zacsweers.fieldspottr.data.core.testing.testClock
import dev.zacsweers.fieldspottr.data.core.testing.testDataScope
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable

/**
 * A retried row keeps its old place in its key's queue, so an older change can reach the server
 * after a newer one. These tests check that the truth then ends with the value the server holds.
 */
class OutboxCommitOrderTest {
  @Serializable data class Flag(val on: Boolean)

  @Test
  fun `owed rows commit in the order they sent`() = runTest {
    val flags = FlagStore(this)
    runCurrent()

    val older = flags.store.mutate(Flag(true))
    older.awaitFailed<PermanentFailure>()
    flags.store.mutate(Flag(false)).awaitSucceeded()
    // The older change sends after the newer one, while the newer one's commit waits
    advanceTimeBy(500.milliseconds)
    flags.store.retry(older.id).awaitSucceeded()
    advanceTimeBy(2.seconds)
    runCurrent()

    flags.assertEndsOn()
  }

  @Test
  fun `owed rows that sent at the same clock time commit in the order they sent`() = runTest {
    val flags = FlagStore(this)
    runCurrent()

    val older = flags.store.mutate(Flag(true))
    older.awaitFailed<PermanentFailure>()
    val newer = flags.store.mutate(Flag(false))
    newer.awaitSucceeded()
    // The older change sends after the newer one at the same clock time
    flags.store.retry(older.id).awaitSucceeded()

    val rows = flags.data.outboxStore<Flag>(Flags.name).entries().first().associateBy { it.id }
    val olderRow = rows.getValue(older.id)
    val newerRow = rows.getValue(newer.id)
    for (row in listOf(olderRow, newerRow)) {
      assertThat(row.status).isEqualTo(OutboxStatus.Sent)
      assertThat(row.committed).isFalse()
    }
    assertThat(newerRow.sentAt).isEqualTo(START)
    // Recorded after the owed newer row, so it commits last
    assertThat(olderRow.sentAt).isEqualTo(START + 1.milliseconds)

    advanceTimeBy(2.seconds)
    runCurrent()

    flags.assertEndsOn()
  }

  /**
   * An optimistic flag store whose server rejects the first change that turns the flag on, and
   * whose truth fails its first write, so the commit of the first change that sends is owed.
   */
  private class FlagStore(scope: TestScope) {
    var server: Boolean? = null
    private var rejectFirst = true
    val data =
      scope.testDataScope(
        clock = scope.testClock(START),
        senders =
          setOf(
            Flags.sender { flag, _ ->
              if (flag.on && rejectFirst) {
                rejectFirst = false
                throw PermanentFailure("Rejected")
              }
              server = flag.on
            }
          ),
      )
    val truth = QueryStore.inMemory(Fetched(false, START - 1.hours))
    private var writes = 0
    private val diskFull =
      QueryStore.of(
        truth.data,
        write = { fetched, keepsStored ->
          if (++writes == 1) {
            error("Disk full")
          }
          truth.writeFetched(fetched, keepsStored)
        },
        clear = truth::clearFetched,
      )
    val own = TestFailures()
    val store = data.optimisticStore(Flags, base = diskFull, apply = set, onFailure = own)

    /**
     * Checks that the server and the truth both end on, every row settled, and only the one failed
     * write was reported.
     */
    suspend fun assertEndsOn() {
      assertThat(server).isEqualTo(true)
      assertThat(truth.data.first()?.value).isEqualTo(true)
      assertThat(data.outboxStore<Flag>(Flags.name).entries().first()).isEmpty()
      assertThat(own.take().map { it.message }).containsExactly("Disk full")
    }
  }

  private companion object {
    val START: Instant = Instant.parse("2026-06-10T12:00:00Z")
    val Flags = OutboxKey<Flag>("flags")

    /** Sets the flag, which is safe to apply again. */
    val set: (current: Boolean?, flag: Flag) -> Boolean = { _, flag -> flag.on }
  }
}
