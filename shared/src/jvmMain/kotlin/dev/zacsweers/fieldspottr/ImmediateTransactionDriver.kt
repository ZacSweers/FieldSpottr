// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement

/**
 * A [SqlDriver] whose transactions take the write lock before their first statement, like BEGIN
 * IMMEDIATE.
 *
 * SQLDelight's JDBC driver begins each transaction with a plain BEGIN, which takes a read lock at
 * the first read and the write lock at the first write. SQLite refuses that upgrade at once,
 * without the busy timeout, while another connection holds the write lock. A transaction that
 * retries then reads again before the other connection commits and is refused again. With the write
 * lock taken first, a transaction waits up to the busy timeout for it and then reads what the other
 * connection committed.
 *
 * [delegate] keeps one connection for a transaction until it ends, so this driver ends the plain
 * BEGIN on that connection and runs BEGIN IMMEDIATE in its place, before the outermost
 * transaction's first statement. [delegate] still commits or rolls back. A transaction with no
 * statements takes no lock.
 */
internal class ImmediateTransactionDriver(private val delegate: SqlDriver) : SqlDriver by delegate {
  /** This thread's outermost transaction, until its first statement takes the write lock. */
  private val unlocked = ThreadLocal<Transacter.Transaction?>()

  override fun newTransaction(): QueryResult<Transacter.Transaction> {
    val outermost = delegate.currentTransaction() == null
    val transaction = delegate.newTransaction().value
    if (outermost) {
      unlocked.set(transaction)
      transaction.afterCommit { unlocked.remove() }
      transaction.afterRollback { unlocked.remove() }
    }
    return QueryResult.Value(transaction)
  }

  override fun <R> executeQuery(
    identifier: Int?,
    sql: String,
    mapper: (SqlCursor) -> QueryResult<R>,
    parameters: Int,
    binders: (SqlPreparedStatement.() -> Unit)?,
  ): QueryResult<R> {
    lockForWrite()
    return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
  }

  override fun execute(
    identifier: Int?,
    sql: String,
    parameters: Int,
    binders: (SqlPreparedStatement.() -> Unit)?,
  ): QueryResult<Long> {
    lockForWrite()
    return delegate.execute(identifier, sql, parameters, binders)
  }

  /**
   * Swaps the plain BEGIN for BEGIN IMMEDIATE when this thread's transaction has run no statement
   * yet. The plain BEGIN holds no locks, so ending it changes nothing. When BEGIN IMMEDIATE fails,
   * like after the busy timeout, a plain BEGIN takes its place again, so [delegate] can still roll
   * the transaction back.
   */
  private fun lockForWrite() {
    if (unlocked.get() == null || delegate.currentTransaction() == null) {
      return
    }
    unlocked.remove()
    delegate.execute(null, "ROLLBACK", 0)
    try {
      delegate.execute(null, "BEGIN IMMEDIATE", 0)
    } catch (e: Throwable) {
      runCatching { delegate.execute(null, "BEGIN", 0) }.exceptionOrNull()?.let(e::addSuppressed)
      throw e
    }
  }
}
