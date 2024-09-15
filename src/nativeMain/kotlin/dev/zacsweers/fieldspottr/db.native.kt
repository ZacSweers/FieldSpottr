// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.runtime.Stable
import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.QueryResult.AsyncValue
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@Stable
@Inject
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class NativeSqlDriverFactory : SqlDriverFactory {
  override suspend fun create(schema: SqlSchema<AsyncValue<Unit>>, name: String): SqlDriver {
    return NativeSqliteDriver(schema.synchronous(), name)
  }
}
