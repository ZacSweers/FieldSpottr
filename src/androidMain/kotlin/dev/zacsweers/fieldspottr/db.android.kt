// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import android.content.Context
import androidx.compose.runtime.Stable
import app.cash.sqldelight.db.QueryResult.Value
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

@Stable
actual class SqlDriverFactory(private val context: Context) {
  actual fun create(schema: SqlSchema<Value<Unit>>, name: String): SqlDriver {
    return AndroidSqliteDriver(schema, context, name)
  }
}
