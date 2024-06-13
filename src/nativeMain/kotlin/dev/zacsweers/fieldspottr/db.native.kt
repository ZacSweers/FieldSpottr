// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.runtime.Stable
import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.QueryResult.AsyncValue
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.native.NativeSqliteDriver

@Stable
actual class SqlDriverFactory {
  actual suspend fun create(schema: SqlSchema<AsyncValue<Unit>>, name: String): SqlDriver {
    return NativeSqliteDriver(schema.synchronous(), name)
  }
}
