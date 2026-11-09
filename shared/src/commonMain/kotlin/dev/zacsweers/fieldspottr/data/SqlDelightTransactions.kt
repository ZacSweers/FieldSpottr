// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import app.cash.sqldelight.SuspendingTransacter
import app.cash.sqldelight.SuspendingTransactionWithoutReturn

/**
 * Runs [body] in a transaction, and runs it again from the start when SQLite refuses it because
 * another connection wrote after its first read.
 *
 * A transaction that reads before it writes works from what it read. When another connection
 * commits first, SQLite fails the transaction's first write at once: with SQLITE_BUSY_SNAPSHOT in
 * WAL mode, or with SQLITE_BUSY in rollback mode, where the two would otherwise wait on each other.
 * Waiting cannot help, since the read is stale. So the transaction rolls back and [body] runs again
 * over what the other connection wrote. [body] must be safe to run more than once. A refused
 * attempt's database writes roll back, and anything else it did stays.
 *
 * Each refusal means another connection committed, so a few attempts are enough. After the last
 * one, or for any other failure, the error reaches the caller.
 */
internal suspend fun SuspendingTransacter.transactionRetryingConflicts(
  body: suspend SuspendingTransactionWithoutReturn.() -> Unit
) {
  var attempt = 1
  while (true) {
    try {
      transaction(body = body)
      return
    } catch (e: Throwable) {
      if (attempt >= CONFLICT_ATTEMPTS || !e.isWriteConflict()) {
        throw e
      }
      attempt++
    }
  }
}

/**
 * Whether this is SQLite refusing a transaction's write because another connection wrote after the
 * transaction read, as [transactionRetryingConflicts] describes. Only a driver that lets two
 * connections in one process each start a transaction with a read can see it.
 */
internal expect fun Throwable.isWriteConflict(): Boolean

/** Covers a race with two other writers that each commit between this one's read and its write. */
private const val CONFLICT_ATTEMPTS = 3
