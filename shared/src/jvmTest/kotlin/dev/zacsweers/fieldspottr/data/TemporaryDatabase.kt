// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.zacsweers.fieldspottr.FSDatabase
import dev.zacsweers.fieldspottr.createFSDatabase
import dev.zacsweers.fieldspottr.sqliteDriverProperties
import dev.zacsweers.metro.suspendLazy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists
import org.junit.rules.ExternalResource

/**
 * A database in a temp file, with the desktop driver's settings. A file database gets a connection
 * per thread, so concurrent writes behave as they do in the app.
 */
class TemporaryDatabase<T>(
  val databaseConstructor: (SqlDriver) -> T,
  val schema: SqlSchema<QueryResult.AsyncValue<Unit>>,
) : ExternalResource() {
  private var driver: SqlDriver? = null
  private var dbBacker: T? = null
  private var file: Path? = null

  private val lazyDb = suspendLazy {
    dbBacker?.let {
      return@suspendLazy it
    }

    val file = Files.createTempFile("fs-test", ".db")
    val driver =
      JdbcSqliteDriver(
        url = "jdbc:sqlite:${file.absolutePathString()}",
        properties = sqliteDriverProperties(),
      )
    schema.create(driver).await()
    val instance = databaseConstructor(driver)
    this.dbBacker = instance
    this.driver = driver
    this.file = file
    instance
  }

  suspend fun db(): T = lazyDb.await()

  override fun after() {
    driver?.close()
    driver = null
    file?.let { path ->
      path.deleteIfExists()
      path.resolveSibling("${path.fileName}-journal").deleteIfExists()
    }
    file = null
  }
}

@JvmName("create")
fun TemporaryDatabase(): TemporaryDatabase<FSDatabase> {
  return TemporaryDatabase({ driver -> driver.createFSDatabase() }, FSDatabase.Schema)
}
