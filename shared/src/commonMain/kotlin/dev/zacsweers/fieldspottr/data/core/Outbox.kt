// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow

/** How often and how long a row waits after a transient failure. */
class RetryPolicy(val maxAttempts: Int, val delayFor: (attempt: Int) -> Duration) {
  companion object {
    /** Doubles the wait from [initial] after each attempt, up to [max]. */
    fun exponential(
      initial: Duration = 1.seconds,
      max: Duration,
      maxAttempts: Int = 20,
    ): RetryPolicy =
      RetryPolicy(maxAttempts) { attempt -> minOf(max, initial * 2.0.pow(attempt - 1)) }
  }
}

/** Thrown by send for a rejection that retrying cannot fix. The row fails and its key moves on. */
class PermanentFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
  constructor(cause: Throwable) : this(cause.toString(), cause)
}

/**
 * Writes that must reach the server, kept as rows in a store and sent in order per key. A send that
 * fails transiently backs off by its retry policy and tries again. One that fails for good, or runs
 * out of attempts, fails its row and runs its revert, so the optimistic change behind it is undone.
 *
 * ```
 * enqueue(payload, key) --> apply() --> store.insert --> drain(key), one per key
 *                                                             |
 *                         online, not backing off, sendable row at the head
 *                                                             |
 *                                                             v
 *                                       Sending --> send(payload) --> Sent
 *                                                             |
 *                         transient: Queued with retryAt, then a timer drains again
 *                         permanent or out of attempts: Failed, then revert(payload)
 * ```
 *
 * Handles follow the row, so they survive a restart through [operation]. A handle settles with the
 * outcome of the attempt it follows, so after a retry an earlier handle keeps its own result. When
 * the outbox's scope stops, its unsettled handles settle Cancelled and the rows stay for the next
 * outbox. Drains start on enqueue, on reconnect and at startup.
 */
@SubclassOptInRequired(DelicateDataLayerApi::class)
interface Outbox<E> {
  /** Rows neither Sent nor Discarded, oldest first. */
  val entries: Flow<List<OutboxEntry<E>>>

  /**
   * Runs [apply], the optimistic local change, then inserts a row, in one command. If apply throws,
   * nothing is inserted and the handle fails. If the insert throws, revert undoes the change and
   * the handle fails. Once apply returns, the insert runs to the end even if the outbox's scope
   * stops. Without a [key], every row shares one order.
   */
  fun enqueue(payload: E, key: String? = null, apply: suspend () -> Unit = {}): Operation

  /**
   * Requeues a failed row with its attempts reset, or ends a backoff early. The handle follows the
   * row from here.
   */
  fun retry(id: EntryId): Operation

  /**
   * Discards a queued or failed row and reverts its optimistic change, unless that already
   * happened. Fails instead when the drainer has already taken the row.
   */
  fun discard(id: EntryId): Operation

  /**
   * Re-attaches to a row by id, for example after process death. An unknown or pruned id settles
   * Cancelled.
   */
  fun operation(id: EntryId): Operation

  companion object
}
