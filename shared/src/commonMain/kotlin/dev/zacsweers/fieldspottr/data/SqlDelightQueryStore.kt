// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import app.cash.sqldelight.Query
import app.cash.sqldelight.SuspendingTransacter
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import dev.zacsweers.fieldspottr.data.core.Fetched
import dev.zacsweers.fieldspottr.data.core.QueryStore
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * A [QueryStore] over a query that selects at most one row, like a table with a row per cached
 * response. The adapter builds each [Fetched] from the row, with [value] and [fetchedAt]. A table
 * with no usable fetch time returns [QueryStore.INVALIDATED] from [fetchedAt], which reads as
 * stale, so the first collect fetches.
 *
 * [write], [clear] and [invalidate] change the row. Every read and every call runs on [dispatcher],
 * since a driver's calls block. A fetch's write runs in a transaction on [database] that reads the
 * row first, so it checks the stored fetch time and stores in one step, as
 * [QueryStore.writeFetched] requires, and [write] runs inside that transaction. Keep [invalidate]
 * to one update, like an UPDATE that sets the fetch time to [QueryStore.INVALIDATED], so it never
 * loses a write that lands between a read and a write.
 *
 * Like [SqlDelightOutboxStores], this depends on SQLDelight and the core API only, so it can move
 * to an artifact of its own.
 */
fun <Row : Any, T> Query<Row>.asQueryStore(
  database: SuspendingTransacter,
  dispatcher: CoroutineDispatcher,
  value: (Row) -> T,
  fetchedAt: (Row) -> Instant,
  write: suspend (value: T, fetchedAt: Instant) -> Unit,
  clear: suspend () -> Unit,
  invalidate: suspend () -> Unit,
): QueryStore<T> {
  val query = this
  return QueryStore.of(
    data =
      asFlow().mapToOneOrNull(dispatcher).map { row ->
        row?.let { Fetched(value(it), fetchedAt(it)) }
      },
    clear = { withContext(dispatcher) { clear() } },
    invalidate = { withContext(dispatcher) { invalidate() } },
  ) { newValue, newFetchedAt, keepsStored ->
    withContext(dispatcher) {
      database.transaction {
        val stored = query.awaitAsOneOrNull()
        if (stored == null || !keepsStored(fetchedAt(stored))) {
          write(newValue, newFetchedAt)
        }
      }
    }
  }
}
