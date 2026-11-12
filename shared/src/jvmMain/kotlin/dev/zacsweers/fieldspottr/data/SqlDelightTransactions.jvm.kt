// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import java.sql.SQLException

/**
 * The desktop driver gives each thread its own connection to a file database and begins each
 * transaction with BEGIN IMMEDIATE, as [dev.zacsweers.fieldspottr.ImmediateTransactionDriver]
 * describes. So a transaction holds the write lock before its first read, and SQLite never refuses
 * its write for a stale read. SQLITE_BUSY then means another connection held a lock past the busy
 * timeout, which a retry waits for once more. sqlite-jdbc reports SQLITE_BUSY and its extended
 * forms with the primary code as the error code.
 */
internal actual fun Throwable.isWriteConflict(): Boolean =
  this is SQLException && errorCode == SQLITE_BUSY

private const val SQLITE_BUSY = 5
