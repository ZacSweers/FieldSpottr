// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import dev.zacsweers.fieldspottr.FSDatabase
import dev.zacsweers.fieldspottr.data.core.QueryStore
import dev.zacsweers.fieldspottr.data.core.testing.QueryStoreContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import org.junit.Rule

/**
 * The store contract over SQLite through [asQueryStore]. It borrows one row of the area feed table,
 * whose hash column holds the value, through the same row encoding as the feed store.
 */
class SqlDelightQueryStoreContractTest : QueryStoreContract() {
  @get:Rule val temporaryDatabase = TemporaryDatabase()

  override fun TestScope.newStore(): QueryStore<String> {
    // The contract builds its store without suspending, and the database opens at once
    val db = runBlocking { temporaryDatabase.db() }
    return db.areaFeedQueryStore(NAME)
  }

  // A new store over the same row, as after a restart
  override suspend fun TestScope.reopen(store: QueryStore<String>): QueryStore<String> =
    temporaryDatabase.db().areaFeedQueryStore(NAME)

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
      // The feed store's own row encoding, so the contract covers it
      fetchedAt = { it.storedFetchedAt },
      invalidated = { it.feedInvalidated != 0L },
      write = { fetched ->
        queries.upsertAreaFeed(
          areaFeedRow(
            name = name,
            areaName = name,
            generatedAt = null,
            hash = fetched.value,
            fetchedAt = fetched.fetchedAt,
            invalidated = fetched.invalidated,
          )
        )
        afterWrite()
      },
      clear = { queries.deleteAreaFeed(name) },
      // One UPDATE, so it never loses a write that lands meanwhile
      invalidate = { queries.invalidateAreaFeed(name) },
    )
}
