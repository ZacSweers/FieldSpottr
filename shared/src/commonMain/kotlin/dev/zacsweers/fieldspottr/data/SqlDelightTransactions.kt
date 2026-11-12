// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import app.cash.sqldelight.SuspendingTransacter
import app.cash.sqldelight.SuspendingTransactionWithoutReturn

/**
 * Runs [body] in a transaction, and runs it again from the start when SQLite refuses it because
 * another connection holds a lock it needs.
 *
 * A transaction that begins with a plain BEGIN reads under a read lock and takes the write lock at
 * its first write. SQLite refuses that write at once when another connection holds the write lock
 * or committed after the read. An immediate retry is refused again while the other connection still
 * holds the lock, so every driver here begins a transaction with the write lock, and a transaction
 * waits up to the busy timeout for it. The JVM driver can still report SQLITE_BUSY when a lock
 * stays held past that timeout. Then the transaction rolls back and [body] runs again. [body] must
 * be safe to run more than once: its database writes roll back, but anything else it did stays.
 *
 * After the last attempt, or for any other failure, the error reaches the caller.
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
 * Whether this is SQLite refusing a transaction because another connection holds a lock it needs,
 * as [transactionRetryingConflicts] describes.
 */
internal expect fun Throwable.isWriteConflict(): Boolean

/** Covers a lock that two other writers each hold past the busy timeout in turn. */
private const val CONFLICT_ATTEMPTS = 3
