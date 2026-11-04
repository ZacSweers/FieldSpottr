// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow

/** How often and how long a row waits after a transient failure. */
class RetryPolicy(val maxAttempts: Int, val delayFor: (attempt: Int) -> Duration) {
  companion object {
    /**
     * Doubles the wait from [initial] after each attempt, up to [max]. With the defaults a row that
     * keeps failing is given up after about an hour of attempts. Time spent offline does not count,
     * since drains wait for the network. Throw [PermanentFailure] from send to give up sooner.
     */
    fun exponential(
      initial: Duration = 1.seconds,
      max: Duration = 5.minutes,
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
 *                                       Sending --> send(payload, id) --> Sent
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
   * stops. Without a [key], every row shares one order. The handle's [OutboxOperation.stored]
   * settles once the row is in, before any send.
   */
  fun enqueue(payload: E, key: String? = null, apply: suspend () -> Unit = {}): OutboxOperation

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

/**
 * The handle [Outbox.enqueue] and [MutableQueryStore.mutate] return. As an [Operation] it follows
 * the row's send, as [Outbox] describes: Succeeded once sent, Failed once rejected for good or out
 * of attempts, Cancelled once discarded. [stored] settles earlier, once the row is in the store.
 */
@SubclassOptInRequired(DelicateDataLayerApi::class)
interface OutboxOperation : Operation {
  /**
   * Whether the change reached the store. Succeeded once its row is written, so it sends when it
   * can and, in a durable [OutboxStore], outlives a restart. Failed when the store rejected it, so
   * nothing sends, and Cancelled when the owner stopped before the row was written. Await it when
   * keeping the change is enough, like a composer that closes once the message is saved and shows
   * its delivery in the list.
   */
  val stored: Operation
}
