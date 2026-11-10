// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import dev.zacsweers.fieldspottr.DbAreaFeed
import dev.zacsweers.fieldspottr.FSDatabase
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
    return db.areaFeedQueryStore(NAME)
  }

  private companion object {
    const val NAME = "contract"
  }
}

/**
 * A store through [asQueryStore] over the area feed row called [name], whose hash is the value.
 * [afterWrite] runs inside each write's transaction once the row has changed, like a later
 * statement that fails.
 */
internal fun FSDatabase.areaFeedQueryStore(
  name: String,
  afterWrite: () -> Unit = {},
): QueryStore<String> {
  val queries = fsdbQueries
  return queries
    .getAreaFeed(name)
    .asQueryStore(
      database = this,
      // Real threads, the way the app's stores run their blocking calls
      dispatcher = Dispatchers.IO,
      value = { it.hash },
      fetchedAt = { Instant.fromEpochMilliseconds(it.feedFetchedAt) },
      write = { value, fetchedAt ->
        queries.upsertAreaFeed(
          DbAreaFeed(
            name = name,
            areaName = name,
            feedGeneratedAt = null,
            feedFetchedAt = fetchedAt.toEpochMilliseconds(),
            hash = value,
          )
        )
        afterWrite()
      },
      clear = { queries.deleteAreaFeed(name) },
      invalidate = {
        transaction {
          val row = queries.getAreaFeed(name).executeAsOneOrNull() ?: return@transaction
          queries.upsertAreaFeed(
            row.copy(feedFetchedAt = QueryStore.INVALIDATED.toEpochMilliseconds())
          )
        }
      },
    )
}
