// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import app.cash.sqldelight.db.SqlDriver
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import dev.zacsweers.fieldspottr.FSDatabase
import dev.zacsweers.fieldspottr.createFSDatabase
import dev.zacsweers.fieldspottr.data.core.Fetched
import dev.zacsweers.fieldspottr.fileSqliteDriver
import dev.zacsweers.fieldspottr.sqliteDriverProperties
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS
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
 * Writes through [asQueryStore] on separate connections, where the second one starts while the
 * first sits inside its transaction. The first holds the write lock from its start, so the second
 * waits for it to commit and then reads what it stored. Neither is refused, so neither runs twice.
 */
class SqlDelightQueryStoreConflictTest {
  @get:Rule val folder = TemporaryFolder()

  @Test
  fun `a newer fetch waits for an older one between its read and its write in WAL mode`() {
    val race = race(wal = true, paused = NEWER, during = OLDER)

    assertThat(race.secondReadEarly).isFalse()
    assertThat(race.firstSaw).containsExactly(STALE)
    assertThat(race.secondSaw).containsExactly(NEWER)
    assertThat(race.stored).isEqualTo(Fetched("newer", NEWER))
  }

  @Test
  fun `an older fetch waits for a newer one between its read and its write in WAL mode`() {
    val race = race(wal = true, paused = OLDER, during = NEWER)

    assertThat(race.secondReadEarly).isFalse()
    assertThat(race.firstSaw).containsExactly(STALE)
    assertThat(race.secondSaw).containsExactly(OLDER)
    assertThat(race.stored).isEqualTo(Fetched("newer", NEWER))
  }

  @Test
  fun `a newer fetch waits for an older one between its read and its write in rollback mode`() {
    val race = race(wal = false, paused = NEWER, during = OLDER)

    assertThat(race.secondReadEarly).isFalse()
    assertThat(race.firstSaw).containsExactly(STALE)
    assertThat(race.secondSaw).containsExactly(NEWER)
    assertThat(race.stored).isEqualTo(Fetched("newer", NEWER))
  }

  @Test
  fun `an older fetch waits for a newer one between its read and its write in rollback mode`() {
    val race = race(wal = false, paused = OLDER, during = NEWER)

    assertThat(race.secondReadEarly).isFalse()
    assertThat(race.firstSaw).containsExactly(STALE)
    assertThat(race.secondSaw).containsExactly(OLDER)
    assertThat(race.stored).isEqualTo(Fetched("newer", NEWER))
  }

  @Test
  fun `a fetch write waits for a write that holds the lock in WAL mode`() {
    val saw = writeWhileLocked(wal = true)

    assertThat(saw).containsExactly(OLDER)
  }

  @Test
  fun `a fetch write waits for a write that holds the lock in rollback mode`() {
    val saw = writeWhileLocked(wal = false)

    assertThat(saw).containsExactly(OLDER)
  }

  /**
   * Stores a stale row, then holds a write of a value fetched at [OLDER] inside its transaction
   * after it changed the row, like a long write of many rows. Meanwhile a fetch write fetched at
   * [NEWER] starts on another connection. The held write resumes once the fetch write finishes, or
   * after [SECOND_READ_WAIT]. A fetch write that reads before the held write commits is refused
   * each time it tries to write, and fails once it runs out of attempts. Returns the stored fetch
   * time that each attempt of the fetch write read, and checks that the newer value landed.
   */
  private fun writeWhileLocked(wal: Boolean): List<Instant> = runBlocking {
    val driver = openDriver(wal)
    try {
      FSDatabase.Schema.create(driver).await()
      val database = driver.createFSDatabase()
      val store = database.areaFeedQueryStore("locked")
      store.write("stale", STALE)

      val wrote = CountDownLatch(1)
      val resumeHeld = CountDownLatch(1)
      val held =
        database.areaFeedQueryStore("locked") {
          wrote.countDown()
          check(resumeHeld.await(10, SECONDS)) { "The held write was never resumed" }
        }
      val holder = async(Dispatchers.IO) { held.write("older", OLDER) }
      check(wrote.await(10, SECONDS)) { "The held write never changed the row" }

      val finished = CountDownLatch(1)
      val saw = Collections.synchronizedList(mutableListOf<Instant>())
      val fetch =
        async(Dispatchers.IO) {
          store.writeFetched("newer", NEWER) { storedAt ->
            saw += storedAt
            storedAt > NEWER
          }
        }
      fetch.invokeOnCompletion { finished.countDown() }
      finished.await(SECOND_READ_WAIT, MILLISECONDS)
      resumeHeld.countDown()
      holder.await()
      fetch.await()
      assertThat(store.data.first()).isEqualTo(Fetched("newer", NEWER))
      saw.toList()
    } finally {
      driver.close()
    }
  }

  /**
   * What one [race] saw. [firstSaw] and [secondSaw] list the stored fetch time that each attempt of
   * each write read. [secondReadEarly] is whether the second write read while the first one held
   * its transaction. [stored] is what the row holds once both finish.
   */
  private class Race(
    val firstSaw: List<Instant>,
    val secondSaw: List<Instant>,
    val secondReadEarly: Boolean,
    val stored: Fetched<String>?,
  )

  /**
   * Stores a stale row, then starts a fetch write fetched at [paused] and holds it after its first
   * read. Meanwhile a fetch write fetched at [during] starts on another connection. The first one
   * resumes once the second has read, or after [SECOND_READ_WAIT] when the second does not read. A
   * second write that reads before the first one resumes makes [Race.secondReadEarly] true and
   * reads the stale row. Both use the query's rule, which keeps a stored value fetched later.
   */
  private fun race(wal: Boolean, paused: Instant, during: Instant): Race = runBlocking {
    val driver = openDriver(wal)
    try {
      FSDatabase.Schema.create(driver).await()
      val store = driver.createFSDatabase().areaFeedQueryStore("race")
      store.write("stale", STALE)

      val firstRead = CountDownLatch(1)
      val resumeFirst = CountDownLatch(1)
      val firstSaw = Collections.synchronizedList(mutableListOf<Instant>())
      val first =
        async(Dispatchers.IO) {
          store.writeFetched(nameOf(paused), paused) { storedAt ->
            firstSaw += storedAt
            // Only the first attempt waits
            if (firstSaw.size == 1) {
              firstRead.countDown()
              check(resumeFirst.await(10, SECONDS)) { "The first write was never resumed" }
            }
            storedAt > paused
          }
        }
      check(firstRead.await(10, SECONDS)) { "The first write never read the row" }

      val secondRead = CountDownLatch(1)
      val secondSaw = Collections.synchronizedList(mutableListOf<Instant>())
      val second =
        async(Dispatchers.IO) {
          store.writeFetched(nameOf(during), during) { storedAt ->
            secondSaw += storedAt
            secondRead.countDown()
            storedAt > during
          }
        }
      val secondReadEarly = secondRead.await(SECOND_READ_WAIT, MILLISECONDS)
      resumeFirst.countDown()
      first.await()
      second.await()
      Race(firstSaw.toList(), secondSaw.toList(), secondReadEarly, store.data.first())
    } finally {
      driver.close()
    }
  }

  /** The desktop driver over a new database file, in WAL mode when [wal] is true. */
  private fun openDriver(wal: Boolean): SqlDriver =
    fileSqliteDriver(
      path = folder.newFile().absolutePath,
      properties =
        sqliteDriverProperties().apply {
          if (wal) {
            setProperty("journal_mode", "WAL")
          }
        },
    )

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

    /**
     * How long the first write stays paused while the second one could read. A second write that
     * waits for the first one never reads in this time, so the wait only bounds how long a wrong
     * read can take to show.
     */
    const val SECOND_READ_WAIT = 200L
  }
}
