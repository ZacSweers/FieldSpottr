// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** For optimistic writes. Undoes on any failure, cancellation included, then rethrows. */
suspend fun <T> rollbackOnFailure(rollback: suspend () -> Unit, block: suspend () -> T): T {
  try {
    return block()
  } catch (t: Throwable) {
    try {
      // A dying scope must not leave the row pending
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
