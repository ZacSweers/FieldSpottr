// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import app.cash.sqldelight.Query
import app.cash.sqldelight.SuspendingTransacter
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import dev.zacsweers.fieldspottr.data.core.Fetched
import dev.zacsweers.fieldspottr.data.core.KeepsStored
import dev.zacsweers.fieldspottr.data.core.QueryStore
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * A [QueryStore] over a query that selects at most one row, like a table with a row per cached
 * response. The adapter builds each [Fetched] from the row, with [value], [fetchedAt] and
 * [invalidated]. A row with no usable fetch time returns null from [fetchedAt], which reads as
 * never fetched, so the first collect fetches.
 *
 * [write], [clear] and [invalidate] change the row. [write] stores the whole [Fetched]: the value,
 * its fetch time or null, and its invalidated flag. Every read and every call runs on [dispatcher],
 * since a driver's calls block. A write or clear runs in a transaction on [database] that reads the
 * row first, so it checks the stored fetch time and flag and changes the row in one step, as
 * [QueryStore.writeFetched] and [QueryStore.clearFetched] require, and [write] or [clear] runs
 * inside that transaction. When SQLite refuses the transaction because another connection holds a
 * lock, the transaction runs again, as [transactionRetryingConflicts] describes, so [write] and
 * [clear] may run more than once. Keep [invalidate] to one update, like an UPDATE that sets the
 * row's invalidated column, so it never loses a write that lands between a read and a write.
 *
 * Like [SqlDelightOutboxStores], this depends on SQLDelight and the core API only, so it can move
 * to an artifact of its own.
 */
fun <Row : Any, T> Query<Row>.asQueryStore(
  database: SuspendingTransacter,
  dispatcher: CoroutineDispatcher,
  value: (Row) -> T,
  fetchedAt: (Row) -> Instant?,
  invalidated: (Row) -> Boolean,
  write: suspend (fetched: Fetched<T>) -> Unit,
  clear: suspend () -> Unit,
  invalidate: suspend () -> Unit,
): QueryStore<T> {
  val query = this
  // Whether the stored row stays, from its fetch time and flag alone, so the value is never decoded
  fun Row.keeps(keepsStored: KeepsStored) = keepsStored(fetchedAt(this), invalidated(this))
  return QueryStore.of(
    data =
      asFlow().mapToOneOrNull(dispatcher).map { row ->
        row?.let { Fetched(value(it), fetchedAt(it), invalidated(it)) }
      },
    write = { fetched, keepsStored ->
      withContext(dispatcher) {
        database.transactionRetryingConflicts {
          val stored = query.awaitAsOneOrNull()
          if (stored == null || !stored.keeps(keepsStored)) {
            write(fetched)
          }
        }
      }
    },
    clear = { keepsStored ->
      withContext(dispatcher) {
        database.transactionRetryingConflicts {
          val stored = query.awaitAsOneOrNull()
          if (stored == null || !stored.keeps(keepsStored)) {
            clear()
          }
        }
      }
    },
    invalidate = { withContext(dispatcher) { invalidate() } },
  )
}
