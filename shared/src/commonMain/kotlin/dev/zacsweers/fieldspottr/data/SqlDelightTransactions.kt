// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import app.cash.sqldelight.SuspendingTransacter
import app.cash.sqldelight.SuspendingTransactionWithoutReturn

/**
 * Runs [body] in a transaction, and runs it again from the start when SQLite refuses it because
 * another connection wrote after its first read.
 *
 * When another connection commits between this transaction's read and its first write, SQLite fails
 * that write at once: SQLITE_BUSY_SNAPSHOT in WAL mode, or SQLITE_BUSY in rollback mode. Waiting
 * cannot help, since the read is stale. So the transaction rolls back and [body] runs again. [body]
 * must be safe to run more than once: its database writes roll back, but anything else it did
 * stays.
 *
 * Each refusal means another connection committed, so a few attempts are enough. After the last, or
 * for any other failure, the error reaches the caller.
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
