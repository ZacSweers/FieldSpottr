// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import android.content.Context
import androidx.compose.runtime.Stable
import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

@Stable
class AndroidSqlDriverFactory(private val context: Context): SqlDriverFactory {
  override suspend fun create(
    schema: SqlSchema<QueryResult.AsyncValue<Unit>>,
    name: String,
  ) = AndroidSqliteDriver(schema.synchronous(), context, name)
}
