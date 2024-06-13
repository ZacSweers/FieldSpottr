package dev.zacsweers.fieldspottr

import androidx.compose.runtime.Stable
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema

@Stable
expect class SqlDriverFactory {
  fun create(schema: SqlSchema<QueryResult.Value<Unit>>, name: String): SqlDriver
}
