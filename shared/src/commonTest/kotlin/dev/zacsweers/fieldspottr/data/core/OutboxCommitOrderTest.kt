// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
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
    var server: Boolean? = null
    var rejectFirst = true
    val data =
      testDataScope(
        clock = testClock(START),
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
    var writes = 0
    // The first truth write fails, so the newer change's commit is owed
    val diskFull =
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
    runCurrent()

    val older = store.mutate(Flag(true))
    older.awaitFailed<PermanentFailure>()
    store.mutate(Flag(false)).awaitSucceeded()
    // The older change sends after the newer one, while the newer one's commit waits
    advanceTimeBy(500.milliseconds)
    store.retry(older.id).awaitSucceeded()
    advanceTimeBy(2.seconds)
    runCurrent()

    assertThat(server).isEqualTo(true)
    assertThat(truth.data.first()?.value).isEqualTo(true)
    assertThat(data.outboxStore<Flag>(Flags.name).entries().first()).isEmpty()
    assertThat(own.take().map { it.message }).containsExactly("Disk full")
  }

  private companion object {
    val START: Instant = Instant.parse("2026-06-10T12:00:00Z")
    val Flags = OutboxKey<Flag>("flags")

    /** Sets the flag, which is safe to apply again. */
    val set: (current: Boolean?, flag: Flag) -> Boolean = { _, flag -> flag.on }
  }
}
