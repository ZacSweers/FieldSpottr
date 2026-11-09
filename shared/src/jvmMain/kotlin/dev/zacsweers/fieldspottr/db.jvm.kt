// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.runtime.Stable
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.util.Properties
import kotlin.io.path.absolutePathString

@Stable
@Inject
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class JvmSqlDriverFactory(private val appDirs: FSAppDirs) : SqlDriverFactory {
  override suspend fun create(
    schema: SqlSchema<QueryResult.AsyncValue<Unit>>,
    name: String,
  ): SqlDriver {
    val dbPath = appDirs.userConfig.resolve("$name.db")
    val driver =
      JdbcSqliteDriver(
        url = "jdbc:sqlite:${dbPath.toNioPath().absolutePathString()}",
        properties = sqliteDriverProperties(),
      )
    schema.create(driver).await()
    driver.ensureDbPermitOverlayColumns()
    return driver
  }
}

private suspend fun SqlDriver.ensureDbPermitOverlayColumns() {
  runCatching {
    execute(null, "ALTER TABLE dbPermit ADD COLUMN isOverlap INTEGER NOT NULL DEFAULT 0", 0).await()
  }
  runCatching { execute(null, "ALTER TABLE dbPermit ADD COLUMN advisory TEXT", 0).await() }
}

/**
 * Settings for a file database, where the driver opens one connection per thread. A connection
 * waits up to the busy timeout for a lock. IMMEDIATE applies only to transactions that JDBC begins.
 * SQLDelight's driver begins its own with a plain BEGIN, which takes no lock until the first
 * statement. So two SQLDelight transactions that both read before they write can still deadlock,
 * and SQLite fails one with SQLITE_BUSY at once. Such a transaction runs through
 * [dev.zacsweers.fieldspottr.data.transactionRetryingConflicts].
 */
internal fun sqliteDriverProperties(): Properties =
  Properties().apply {
    setProperty("transaction_mode", "IMMEDIATE")
    setProperty("busy_timeout", "5000")
  }
