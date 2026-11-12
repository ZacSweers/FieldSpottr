// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isSameInstanceAs
import dev.zacsweers.fieldspottr.data.core.Fetched
import dev.zacsweers.fieldspottr.data.core.write
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule

/**
 * How [asQueryStore] retries a write that SQLite refuses, and what a write that throws leaves in
 * the row. The write changes the row and then throws, so a row that kept its first contents shows
 * that every attempt rolled back.
 */
class SqlDelightQueryStoreRetryTest {
  @get:Rule val temporaryDatabase = TemporaryDatabase()

  @Test
  fun `a write refused as busy every time runs three times and rolls back each one`() = runTest {
    // sqlite-jdbc reports SQLITE_BUSY and its extended forms with the primary code 5
    val busy = SQLException("database is locked", null, 5)

    val attempts = writeThatThrows(busy)

    assertThat(attempts).isEqualTo(3)
  }

  @Test
  fun `a write that breaks a constraint fails on its first attempt and rolls back`() = runTest {
    // SQLITE_CONSTRAINT, which another attempt cannot fix
    val constraint = SQLException("constraint failed", null, 19)

    val attempts = writeThatThrows(constraint)

    assertThat(attempts).isEqualTo(1)
  }

  /**
   * Seeds the row, then writes through a store whose write changes the row and throws [failure].
   * Checks that the caller gets [failure] and the row keeps what was seeded, and returns how many
   * times the write ran.
   */
  private suspend fun writeThatThrows(failure: SQLException): Int {
    val db = temporaryDatabase.db()
    db.areaFeedQueryStore(NAME).write("original", SEEDED)
    var attempts = 0
    val store =
      db.areaFeedQueryStore(NAME) {
        attempts++
        throw failure
      }

    val thrown = assertFailsWith<SQLException> { store.write("changed", LATER) }

    // A copy that coroutines made to add the caller's stack trace carries the failure as its cause
    assertThat(generateSequence<Throwable>(thrown) { it.cause }.last()).isSameInstanceAs(failure)
    assertThat(db.areaFeedQueryStore(NAME).data.first()).isEqualTo(Fetched("original", SEEDED))
    return attempts
  }

  private companion object {
    const val NAME = "retry"
    val SEEDED = Instant.parse("2026-06-10T12:00:00Z")
    val LATER = Instant.parse("2026-06-10T13:00:00Z")
  }
}
