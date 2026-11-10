// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data

import java.sql.SQLException

/**
 * The JDBC driver gives each thread its own connection to a file database and begins each
 * transaction with a plain BEGIN, which takes no lock until the first statement. So two
 * transactions can each read and then both try to write. sqlite-jdbc reports SQLITE_BUSY and its
 * extended forms, like SQLITE_BUSY_SNAPSHOT, with the primary code as the error code. SQLITE_BUSY
 * also covers a lock held past the busy timeout, which a retry waits for once more.
 */
internal actual fun Throwable.isWriteConflict(): Boolean =
  this is SQLException && errorCode == SQLITE_BUSY

private const val SQLITE_BUSY = 5
