// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Runs [block] and, if it throws or is cancelled, runs [rollback] before rethrowing. Use it around
 * an optimistic write so the local change is undone whenever the command does not complete.
 */
suspend fun <T> rollbackOnFailure(rollback: suspend () -> Unit, block: suspend () -> T): T {
  try {
    val result = block()
    // A block that ran NonCancellable can return normally after the scope was cancelled. The
    // command does not complete then either, so the change is undone.
    currentCoroutineContext().ensureActive()
    return result
  } catch (t: Throwable) {
    try {
      // The rollback must run even while the scope is being cancelled, or the change would stay
      withContext(NonCancellable) { rollback() }
    } catch (rollbackFailure: Throwable) {
      // The caller and the failure reporter still see the original cause
      t.addSuppressed(rollbackFailure)
    }
    throw t
  }
}

/** [runCatching] that never swallows its own coroutine's cancellation. */
suspend inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
  try {
    Result.success(block())
  } catch (t: Throwable) {
    currentCoroutineContext().ensureActive()
    Result.failure(t)
  }
