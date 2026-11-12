// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow

/**
 * Decides whether a failed send may be retried and how long its row waits first. One policy owns
 * both decisions, so a policy can read the failure, like a `Retry-After` header on a rate limit.
 * The outbox fails a row at once on a [PermanentFailure], whatever the policy says.
 *
 * A throw from [delayAfter] goes to the outbox's failure handler, and the row retries after a short
 * pause, so a broken policy never drops a change. A throw from [maxDelay] is reported too, and the
 * outbox then sets no bound on its waits.
 *
 * A policy also sets how long a query's own fetches hold off after they fail, as [DataQuery.state]
 * describes. There `attempt` counts the failures in a row. A null wait stops the query's own
 * fetches until a fetch the caller requests succeeds. An [Exception] from either member goes to the
 * query's failure handler, and the query holds off for [maxDelay], or for 1 hour when that has no
 * bound or throws. An [Error] from either member records no hold-off. The fetch's own failure is
 * still reported as a failed fetch, and then the Error fails the fetch's handle and goes to the
 * failure handler.
 */
interface RetryPolicy {
  /**
   * How long the row waits after [failure] ended its [attempt]th attempt, counting from 1. Null
   * fails the row now, which runs its revert. A wait longer than [maxDelay] is cut to it. For a
   * query's fetches, null stops the fetches the query starts on its own until a requested fetch
   * succeeds.
   */
  fun delayAfter(attempt: Int, failure: Throwable): Duration?

  /**
   * The longest wait this policy asks for. The backoff deadline on a row is wall-clock time, so a
   * deadline written while the device clock was ahead can lie far in the future once the clock is
   * corrected. The outbox never waits on a row longer than this from when it starts waiting, so
   * such a row sends once this much time has passed. The default sets no bound.
   */
  val maxDelay: Duration
    get() = Duration.INFINITE

  companion object {
    /**
     * Retries every failure up to [maxAttempts] attempts in all, waiting [delayFor] the number of
     * the attempt that failed, counting from 1. [maxDelay] bounds the waits as
     * [RetryPolicy.maxDelay] describes.
     */
    operator fun invoke(
      maxAttempts: Int = Int.MAX_VALUE,
      maxDelay: Duration = Duration.INFINITE,
      delayFor: (attempt: Int) -> Duration,
    ): RetryPolicy = FixedRetryPolicy(maxAttempts, maxDelay, delayFor)

    /**
     * Doubles the wait from [initial] after each attempt, up to [max]. By default it never gives
     * up, so a row that keeps failing transiently retries until it sends or the app discards it.
     * Time offline uses no attempts, since drains wait for the network. A backoff deadline that
     * passes while offline sends the row on reconnect.
     *
     * [maxAttempts] limits the attempts in all, and [retryable] fails the row at once for a failure
     * that retrying cannot fix, like a 4xx response. Throw [PermanentFailure] from send for the
     * same effect. For a query's fetches, a failure that [retryable] rejects or that reaches
     * [maxAttempts] stops the fetches the query starts on its own until a requested fetch succeeds.
     */
    fun exponential(
      initial: Duration = 1.seconds,
      max: Duration = 5.minutes,
      maxAttempts: Int = Int.MAX_VALUE,
      retryable: (Throwable) -> Boolean = { true },
    ): RetryPolicy =
      object : RetryPolicy {
        override val maxDelay: Duration = max

        override fun delayAfter(attempt: Int, failure: Throwable): Duration? =
          if (attempt >= maxAttempts || !retryable(failure)) {
            null
          } else {
            // Past 2^30 the doubling only overflows, and max has long since capped it
            minOf(max, initial * 2.0.pow(minOf(attempt - 1, 30)))
          }
      }
  }
}

/** [RetryPolicy.invoke]'s policy: a wait per attempt number, and a limit on attempts. */
private class FixedRetryPolicy(
  private val maxAttempts: Int,
  override val maxDelay: Duration,
  private val delayFor: (attempt: Int) -> Duration,
) : RetryPolicy {
  override fun delayAfter(attempt: Int, failure: Throwable): Duration? =
    if (attempt >= maxAttempts) {
      null
    } else {
      delayFor(attempt)
    }
}

/** Thrown by send for a rejection that retrying cannot fix. The row fails and its key moves on. */
class PermanentFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
  constructor(cause: Throwable) : this(cause.toString(), cause)
}

/**
 * A row of the outbox [outbox] whose payload this app version cannot decode, like one stored before
 * its payload class changed. [cause] is the decode error. The outbox leaves such a row out, and its
 * store hands each one on once per store instance, as [OutboxStore.onUndecodable] describes. The
 * outbox reports one of these for each to its failure handler.
 */
class UnreadableEntryException(val outbox: String, val id: EntryId, cause: Throwable) :
  Exception("Outbox $outbox cannot decode the payload of row ${id.value}", cause)

/**
 * Writes that must reach the server, kept as rows in a store and sent in order per key. A send that
 * fails transiently backs off by its retry policy and tries again. One that fails for good, or that
 * the policy does not retry, fails its row and runs its revert, so the optimistic change behind it
 * is undone. The outbox reports that failure to its failure handler once. A handle on that row in
 * the same outbox carries the exception in its failure's cause chain, as long as the outbox still
 * remembers it: until the row is retried or discarded, for the newest 64 such rows. A handle from
 * another outbox, like one [operation] returns after a restart, knows only the stored text.
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
 *                         permanent or not retried: Failed, reported, then revert(payload)
 * ```
 *
 * Handles follow the row, and [operation] finds one again after a restart. A handle settles with
 * the attempt it follows, so after a retry an earlier handle keeps its own result. When the
 * outbox's scope stops, unsettled handles settle Cancelled and the rows stay for the next outbox.
 * Drains start on enqueue, on reconnect and at startup.
 */
@SubclassOptInRequired(DelicateDataLayerApi::class)
interface Outbox<E> {
  /**
   * Rows neither Sent nor Discarded, oldest first. A row that cannot be decoded is left out, never
   * sends and never blocks another row. The failure handler hears of it as an
   * [UnreadableEntryException], and every other call treats it as absent.
   */
  val entries: Flow<List<OutboxEntry<E>>>

  /**
   * Runs [apply], the optimistic local change, then inserts a row, in one command. Without a [key],
   * every row shares one order.
   *
   * If apply throws, nothing is inserted and the handle fails. If the insert throws, revert undoes
   * the change and the handle fails. Once apply returns, the insert finishes even if the outbox's
   * scope stops. [OutboxOperation.stored] settles once the row is in, before any send.
   */
  fun enqueue(payload: E, key: String? = null, apply: suspend () -> Unit = {}): OutboxOperation

  /**
   * Requeues a failed row with its attempts reset, or ends a backoff early. The handle follows the
   * row from here.
   */
  fun retry(id: EntryId): Operation

  /**
   * Discards a queued or failed row and reverts its optimistic change, unless that already
   * happened. Fails instead when the drainer has already taken the row, or when there is no row.
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
 * The handle [Outbox.enqueue] and [OptimisticQueryStore.mutate] return. As an [Operation] it
 * follows the row's send: Succeeded once sent, Failed once rejected for good or out of attempts,
 * Cancelled once discarded. [stored] settles earlier, once the row is in the store.
 */
@SubclassOptInRequired(DelicateDataLayerApi::class)
interface OutboxOperation : Operation {
  /**
   * Whether the change reached the store. It succeeds once the row is written, so the change will
   * send, and in a durable [OutboxStore] it outlives a restart. It fails when the store rejects the
   * row, and settles Cancelled when the owner stops first. Await it when keeping the change is
   * enough, like a composer that closes once the message is saved.
   */
  val stored: Operation
}
