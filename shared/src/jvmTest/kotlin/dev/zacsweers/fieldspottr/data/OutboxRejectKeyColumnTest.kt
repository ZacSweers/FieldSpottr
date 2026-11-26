// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import dev.zacsweers.fieldspottr.FSDatabase
import dev.zacsweers.fieldspottr.createFSDatabase
import dev.zacsweers.fieldspottr.data.core.EntryId
import dev.zacsweers.fieldspottr.data.core.asCodec
import dev.zacsweers.fieldspottr.ensureOutboxRejectKeyColumn
import dev.zacsweers.fieldspottr.fileSqliteDriver
import java.nio.file.Files
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.After

/** How a database from before the `rejectKey` column of `outboxRow` gets the column. */
class OutboxRejectKeyColumnTest {
  private val file = Files.createTempFile("fs-outbox-columns", ".db")
  private val driver = fileSqliteDriver(file.absolutePathString())

  @After
  fun closeDatabase() {
    driver.close()
    file.deleteIfExists()
    file.resolveSibling("${file.fileName}-journal").deleteIfExists()
  }

  @Test
  fun `a row stored before the column reads with no reject key after the migration`() = runTest {
    driver.execute(null, OUTBOX_ROW_BEFORE_REJECT_KEYS, 0).await()
    driver
      .execute(
        null,
        "INSERT INTO outboxRow(id, outbox, orderingKey, payload, status) " +
          "VALUES ('old', 'test', 'k', '\"a\"', 'queued')",
        0,
      )
      .await()

    // 5.sqm migrates from version 5
    FSDatabase.Schema.migrate(driver, oldVersion = 5, newVersion = FSDatabase.Schema.version)
      .await()

    val queries = driver.createFSDatabase().outboxRowQueries
    val store =
      SqlDelightOutboxStore(
        { queries },
        "test",
        String.serializer().asCodec(Json),
        Dispatchers.IO,
      )
    val old = store.entry(EntryId("old")).first()
    assertThat(old?.payload).isEqualTo("a")
    assertThat(old?.rejectKey).isNull()
    store.insert(EntryId("new"), "k", "b", rejectKey = "r")
    assertThat(store.entry(EntryId("new")).first()?.rejectKey).isEqualTo("r")
  }

  @Test
  fun `the desktop driver adds the column to an older table once`() = runTest {
    driver.execute(null, OUTBOX_ROW_BEFORE_REJECT_KEYS, 0).await()
    assertThat(driver.columns()).doesNotContain("rejectKey")

    driver.ensureOutboxRejectKeyColumn()
    val columns = driver.columns()
    assertThat(columns).contains("rejectKey")

    // The column is there already, so the second call changes nothing
    driver.ensureOutboxRejectKeyColumn()
    assertThat(driver.columns()).isEqualTo(columns)
  }

  /** The column names of `outboxRow`, in table order. */
  private fun SqlDriver.columns(): List<String> =
    executeQuery(
        null,
        "PRAGMA table_info(outboxRow)",
        { cursor ->
          val names = ArrayList<String>()
          while (cursor.next().value) {
            names += checkNotNull(cursor.getString(1))
          }
          QueryResult.Value(names)
        },
        0,
      )
      .value
}

/** The `outboxRow` table as 4.sqm creates it. */
private const val OUTBOX_ROW_BEFORE_REJECT_KEYS =
  """
  CREATE TABLE outboxRow (
    seq INTEGER PRIMARY KEY AUTOINCREMENT,
    id TEXT NOT NULL UNIQUE,
    outbox TEXT NOT NULL,
    orderingKey TEXT NOT NULL,
    payload TEXT NOT NULL,
    status TEXT NOT NULL,
    cause TEXT,
    attempts INTEGER NOT NULL DEFAULT 0,
    lastError TEXT,
    reverted INTEGER NOT NULL DEFAULT 0,
    retryAt INTEGER,
    settledAt INTEGER,
    committed INTEGER NOT NULL DEFAULT 0,
    sentAt INTEGER,
    reply TEXT
  )
  """
