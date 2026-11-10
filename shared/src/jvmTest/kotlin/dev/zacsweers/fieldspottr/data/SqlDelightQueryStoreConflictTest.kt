// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import assertk.assertThat
import assertk.assertions.isEqualTo
import dev.zacsweers.fieldspottr.FSDatabase
import dev.zacsweers.fieldspottr.createFSDatabase
import dev.zacsweers.fieldspottr.data.core.Fetched
import dev.zacsweers.fieldspottr.sqliteDriverProperties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * Two fetch writes through [asQueryStore] on separate connections, where the second one runs while
 * the first sits between its read and its write. SQLite refuses the first one's write because its
 * read is stale, and the store runs that write again over what the second one stored.
 */
class SqlDelightQueryStoreConflictTest {
  @get:Rule val folder = TemporaryFolder()

  @Test
  fun `a newer fetch lands after an older one commits during its read in WAL mode`() {
    val stored = race(wal = true, paused = NEWER, during = OLDER)

    assertThat(stored).isEqualTo(Fetched("newer", NEWER))
  }

  @Test
  fun `an older fetch keeps a newer one that commits during its read in WAL mode`() {
    val stored = race(wal = true, paused = OLDER, during = NEWER)

    assertThat(stored).isEqualTo(Fetched("newer", NEWER))
  }

  @Test
  fun `a newer fetch lands after an older one writes during its read in rollback mode`() {
    val stored = race(wal = false, paused = NEWER, during = OLDER)

    assertThat(stored).isEqualTo(Fetched("newer", NEWER))
  }

  @Test
  fun `an older fetch keeps a newer one that writes during its read in rollback mode`() {
    val stored = race(wal = false, paused = OLDER, during = NEWER)

    assertThat(stored).isEqualTo(Fetched("newer", NEWER))
  }

  /**
   * Stores a stale row, then starts a fetch write fetched at [paused] and holds it after its first
   * read. Meanwhile a fetch write fetched at [during] runs on another connection. In WAL mode it
   * commits before the first one resumes, since a reader never blocks a writer there. In rollback
   * mode it cannot commit while the first one holds its read lock, so the first one resumes once
   * the second has read. Both use the query's rule, which keeps a stored value fetched later.
   * Returns what the row holds once both finish.
   */
  private fun race(wal: Boolean, paused: Instant, during: Instant): Fetched<String>? = runBlocking {
    val driver =
      JdbcSqliteDriver(
        url = "jdbc:sqlite:${folder.newFile("race.db").absolutePath}",
        properties =
          sqliteDriverProperties().apply {
            if (wal) {
              setProperty("journal_mode", "WAL")
            }
          },
      )
    try {
      FSDatabase.Schema.create(driver).await()
      val store = driver.createFSDatabase().areaFeedQueryStore("race")
      store.write("stale", STALE)

      val firstRead = CountDownLatch(1)
      val resumeFirst = CountDownLatch(1)
      val firstAsks = AtomicInteger()
      val first =
        async(Dispatchers.IO) {
          store.writeFetched(nameOf(paused), paused) { storedAt ->
            // Only the first attempt waits. A retry reads again and goes straight on.
            if (firstAsks.getAndIncrement() == 0) {
              firstRead.countDown()
              check(resumeFirst.await(10, SECONDS)) { "The first write was never resumed" }
            }
            storedAt > paused
          }
        }
      check(firstRead.await(10, SECONDS)) { "The first write never read the row" }

      val secondRead = CountDownLatch(1)
      val second =
        async(Dispatchers.IO) {
          store.writeFetched(nameOf(during), during) { storedAt ->
            secondRead.countDown()
            storedAt > during
          }
        }
      check(secondRead.await(10, SECONDS)) { "The second write never read the row" }
      if (wal) {
        second.await()
      }
      resumeFirst.countDown()
      first.await()
      second.await()
      store.data.first()
    } finally {
      driver.close()
    }
  }

  private fun nameOf(fetchedAt: Instant): String =
    if (fetchedAt == NEWER) {
      "newer"
    } else {
      "older"
    }

  private companion object {
    val STALE = Instant.parse("2026-06-10T12:00:00Z")
    val OLDER = STALE + 10.minutes
    val NEWER = STALE + 20.minutes
  }
}
