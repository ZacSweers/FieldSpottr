// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.runtime.Stable
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.io.path.absolutePathString
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@Stable
@Inject
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class JvmSqlDriverFactory(private val appDirs: FSAppDirs) : SqlDriverFactory {
  override suspend fun create(
    schema: SqlSchema<QueryResult.AsyncValue<Unit>>,
    name: String,
  ): SqlDriver {
    val driver =
      if (name.isEmpty()) {
          JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        } else {
          val dbPath = appDirs.userConfig.resolve("$name.db")
          JdbcSqliteDriver(url = "jdbc:sqlite:${dbPath.toNioPath().absolutePathString()}")
        }
        .also { schema.create(it).await() }
    return driver
  }
}
