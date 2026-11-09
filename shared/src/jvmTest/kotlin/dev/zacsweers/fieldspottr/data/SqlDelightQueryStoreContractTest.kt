// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import dev.zacsweers.fieldspottr.DbAreaFeed
import dev.zacsweers.fieldspottr.data.core.QueryStore
import dev.zacsweers.fieldspottr.data.core.testing.QueryStoreContract
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import org.junit.Rule

/**
 * The store contract over SQLite through [asQueryStore]. It borrows one row of the area feed table,
 * whose hash column holds the value.
 */
class SqlDelightQueryStoreContractTest : QueryStoreContract() {
  @get:Rule val temporaryDatabase = TemporaryDatabase()

  override fun TestScope.newStore(): QueryStore<String> {
    // The contract builds its store without suspending, and the database opens at once
    val db = runBlocking { temporaryDatabase.db() }
    val queries = db.fsdbQueries
    return queries
      .getAreaFeed(NAME)
      .asQueryStore(
        database = db,
        // Real threads, the way the app's stores run their blocking calls
        dispatcher = Dispatchers.IO,
        value = { it.hash },
        fetchedAt = { Instant.fromEpochMilliseconds(it.feedFetchedAt) },
        write = { value, fetchedAt ->
          queries.upsertAreaFeed(
            DbAreaFeed(
              name = NAME,
              areaName = NAME,
              feedGeneratedAt = null,
              feedFetchedAt = fetchedAt.toEpochMilliseconds(),
              hash = value,
            )
          )
        },
        clear = { queries.deleteAreaFeed(NAME) },
        invalidate = {
          db.transaction {
            val row = queries.getAreaFeed(NAME).executeAsOneOrNull() ?: return@transaction
            queries.upsertAreaFeed(
              row.copy(feedFetchedAt = QueryStore.INVALIDATED.toEpochMilliseconds())
            )
          }
        },
      )
  }

  private companion object {
    const val NAME = "contract"
  }
}
