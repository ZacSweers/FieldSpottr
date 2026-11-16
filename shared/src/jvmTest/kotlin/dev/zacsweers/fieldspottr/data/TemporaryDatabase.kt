// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import dev.zacsweers.fieldspottr.FSDatabase
import dev.zacsweers.fieldspottr.createFSDatabase
import dev.zacsweers.fieldspottr.fileSqliteDriver
import dev.zacsweers.metro.suspendLazy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists
import org.junit.rules.ExternalResource

/**
 * A database in a temp file, with the desktop driver. A file database gets a connection per thread,
 * so concurrent writes behave as they do in the app. [reopen] closes the driver and opens the same
 * file again, like a process restart.
 */
class TemporaryDatabase<T>(
  val databaseConstructor: (SqlDriver) -> T,
  val schema: SqlSchema<QueryResult.AsyncValue<Unit>>,
) : ExternalResource() {
  @Volatile private var driver: SqlDriver? = null
  @Volatile private var dbBacker: T? = null
  private var file: Path? = null

  private val lazyDb = suspendLazy {
    val file = Files.createTempFile("fs-test", ".db")
    val driver = fileSqliteDriver(file.absolutePathString())
    schema.create(driver).await()
    val instance = databaseConstructor(driver)
    this.dbBacker = instance
    this.driver = driver
    this.file = file
    instance
  }

  /** The database over the current driver. After [reopen], this is the reopened one. */
  suspend fun db(): T {
    lazyDb.await()
    return checkNotNull(dbBacker)
  }

  /**
   * Closes the current driver and opens a new one over the same file, then returns the database
   * over it. Everything the new driver reads comes from the file. It does not run the schema again,
   * so the tables and rows stay as the last driver left them. Stop all work on the old database
   * first, since its driver is closed.
   */
  suspend fun reopen(): T {
    lazyDb.await()
    val path = checkNotNull(file)
    checkNotNull(driver).close()
    val driver = fileSqliteDriver(path.absolutePathString())
    val instance = databaseConstructor(driver)
    this.driver = driver
    this.dbBacker = instance
    return instance
  }

  override fun after() {
    driver?.close()
    driver = null
    dbBacker = null
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
