// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.Companion.IN_MEMORY
import dev.zacsweers.fieldspottr.FSDatabase
import dev.zacsweers.metro.suspendLazy
import java.sql.SQLException
import org.junit.rules.ExternalResource

class TemporaryDatabase<T>(
  val databaseConstructor: (SqlDriver) -> T,
  val schema: SqlSchema<QueryResult.AsyncValue<Unit>>,
) : ExternalResource() {
  private var driver: SqlDriver? = null
  private var dbBacker: T? = null

  private val lazyDb = suspendLazy {
    dbBacker?.let {
      return@suspendLazy it
    }

    val driver =
      try {
        JdbcSqliteDriver(IN_MEMORY)
      } catch (_: SQLException) {
        JdbcSqliteDriver(IN_MEMORY)
      }
    schema.create(driver).await()
    val instance = databaseConstructor(driver)
    this.dbBacker = instance
    this.driver = driver
    instance
  }

  suspend fun db(): T = lazyDb.await()

  override fun after() {
    driver?.close()
    driver = null
  }
}

@JvmName("create")
fun TemporaryDatabase(): TemporaryDatabase<FSDatabase> {
  return TemporaryDatabase({ driver -> driver.createFSDatabase() }, FSDatabase.Schema)
}
