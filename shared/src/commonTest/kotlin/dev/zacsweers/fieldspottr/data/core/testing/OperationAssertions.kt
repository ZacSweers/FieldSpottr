// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core.testing

import dev.zacsweers.fieldspottr.data.core.Operation
import dev.zacsweers.fieldspottr.data.core.OperationState
import kotlin.test.assertIs
import kotlin.test.fail

// The await functions wait for a handle to settle, then check how it ended, which a test needs
// unless its data scope is eager. The assert functions read a handle's state as it is now, for an
// eager data scope, where work that does not suspend has settled by the time its call returns, and
// for checking a handle that is still queued or running.

/** Fails the test unless this handle is still Queued. */
fun Operation.assertPending() {
  val current = state.value
  if (current != OperationState.Queued) {
    fail("Expected a queued operation, but it is $current", current.failureCause())
  }
}

/** Fails the test unless this handle is Running. */
fun Operation.assertRunning() {
  val current = state.value
  if (current != OperationState.Running) {
    fail("Expected a running operation, but it is $current", current.failureCause())
  }
}

/** Fails the test unless this handle succeeded, with the failure as the cause if it failed. */
fun Operation.assertSucceeded() {
  val current = state.value
  if (current != OperationState.Succeeded) {
    fail("Expected a succeeded operation, but it is $current", current.failureCause())
  }
}

/** Fails the test unless this handle failed with a [T]. Returns that failure. */
inline fun <reified T : Throwable> Operation.assertFailed(): T {
  val current = state.value
  if (current !is OperationState.Failed) {
    fail("Expected a failed operation, but it is $current")
  }
  return assertIs<T>(current.cause)
}

/** Fails the test unless this handle was cancelled. */
fun Operation.assertCancelled() {
  val current = state.value
  if (current != OperationState.Cancelled) {
    fail("Expected a cancelled operation, but it is $current", current.failureCause())
  }
}

/** Waits for this handle to settle, then fails the test unless it succeeded. */
suspend fun Operation.awaitSucceeded() {
  await()
  assertSucceeded()
}

/** Waits for this handle to settle, then fails the test unless it failed with a [T]. */
suspend inline fun <reified T : Throwable> Operation.awaitFailed(): T {
  await()
  return assertFailed<T>()
}

private fun OperationState.failureCause(): Throwable? = (this as? OperationState.Failed)?.cause
