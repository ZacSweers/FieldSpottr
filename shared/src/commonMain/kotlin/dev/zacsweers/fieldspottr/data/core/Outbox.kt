// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow

/**
 * Decides whether a failed send may be retried and how long its entry waits first. One policy owns
 * both decisions, so a policy can read the failure, like a `Retry-After` header on a rate limit.
 * The outbox fails an entry at once on a [PermanentFailure], whatever the policy says.
 *
 * A throw from [delayAfter] goes to the outbox's failure handler, and the entry retries after a
 * short pause, so a broken policy never drops a change. A throw from [maxDelay] is reported too,
 * and the outbox then sets no bound on its waits.
 *
 * A policy also sets how long a query's own fetches hold off after they fail, as
 * [DataQuery.stateAutoFetch] describes. There `attempt` counts the failures in a row. A null wait
 * stops the query's own fetches until a fetch the caller requests succeeds. An [Exception] from
 * either member goes to the query's failure handler, and the query holds off for [maxDelay], or for
 * 1 hour when that has no bound or throws. An [Error] from either member records no hold-off. The
 * fetch's own failure is still reported as a failed fetch, and then the Error fails the fetch's
 * handle and goes to the failure handler.
 */
interface RetryPolicy {
  /**
   * How long the entry waits after [failure] ended its [attempt]th attempt, counting from 1. Null
   * fails the entry now, which runs its revert. A wait longer than [maxDelay] is cut to it. For a
   * query's fetches, null stops the fetches the query starts on its own until a requested fetch
   * succeeds.
   */
  fun delayAfter(attempt: Int, failure: Throwable): Duration?

  /**
   * The longest wait this policy asks for. The backoff deadline on an entry is wall-clock time, so
   * a deadline written while the device clock was ahead can lie far in the future once the clock is
   * corrected. The outbox never waits on an entry longer than this from when it starts waiting, so
   * such an entry sends once this much time has passed. The default sets no bound.
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
     * up, so an entry that keeps failing transiently retries until it sends or the app discards it.
     * Time offline uses no attempts, since drains wait for the network. A backoff deadline that
     * passes while offline sends the entry on reconnect.
     *
     * [maxAttempts] limits the attempts in all, and [retryable] fails the entry at once for a
     * failure that retrying cannot fix, like a 4xx response. Throw [PermanentFailure] from send for
     * the same effect. For a query's fetches, a failure that [retryable] rejects or that reaches
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

/**
 * Thrown by a sender's send for a rejection that retrying cannot fix. The entry fails and its key
 * moves on.
 */
class PermanentFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
  constructor(cause: Throwable) : this(cause.toString(), cause)
}

/**
 * An entry of the outbox [outbox] whose payload this app version cannot decode, like one stored
 * before its payload class changed. [cause] is the decode error. The outbox leaves such an entry
 * out, and its store hands each one on once per store instance, as [OutboxStore.onUndecodable]
 * describes. The outbox reports one of these for each to its sender's failure handler.
 */
class UnreadableEntryException(val outbox: String, val id: EntryId, cause: Throwable) :
  Exception("Outbox $outbox cannot decode the payload of row ${id.value}", cause)

/**
 * The handle on an outbox: writes that must reach the server, kept as entries in a store and sent
 * in order per key. [DataScope.outbox] builds it with an [OutboxKey], and the [OutboxSender] for
 * that key, which the data scope was created with, sends the entries.
 *
 * The two halves split the work. The sender drains entries from the data scope's creation, also
 * before any handle exists, like in a background drain after a restart. The handle keeps the local
 * state: its `apply` makes the optimistic change before the insert, its `revert` undoes the change
 * of an entry that fails for good, and an optimistic store's handle commits each sent change into
 * its truth.
 *
 * A send that fails transiently backs off by its retry policy and tries again. One that fails for
 * good, or that the policy does not retry, fails its entry. The sender's failure handler hears that
 * failure once. A handle on that entry in the same data scope carries the exception in its
 * failure's cause chain, as long as the outbox still remembers it: until the entry is retried or
 * discarded, for the newest 64 such entries. A handle from another data scope, like one [operation]
 * returns after a restart, knows only the stored text.
 *
 * The sender side, from the data scope's creation:
 * ```
 * stored entry --> drain(key), one per key: online, not backing off, sendable entry at the head
 *                      |
 *                      v
 *                   Sending --> send(payload, id)
 *                      |
 *   transient: Queued with retryAt, then a timer drains again
 *   sent, handle ready, no older entry on the key owed:
 *       the handle commits, then Sent and committed
 *       a commit that throws: Sent, not committed, owed, settled again after a pause
 *   sent, otherwise: Sent, not committed, the answer kept on the entry, owed
 *   permanent or not retried, handle ready, no older entry on the key owed:
 *       Failed and reverted, then revert(payload, id)
 *   permanent or not retried, otherwise: Failed, not reverted, owed
 * ```
 *
 * The handle side, from the repository:
 * ```
 * enqueue(payload, ordering) --> id --> apply(payload, id) --> store.insert(id) --> drain(key)
 *
 * attach --> read the owed entries once --> ready --> drain(key) for each key with owed entries
 *
 * a drain of a key with owed entries settles them, oldest first, online or not:
 *   Sent, not committed: commit with the stored answer, then mark committed
 *       a commit that throws: stays owed, later entries on the key wait, settled again after a
 *       pause that doubles while commits keep failing
 *   Failed, not reverted: mark reverted, then revert(payload, id)
 * ```
 *
 * Until a handle is ready, after its first read of the owed entries, a sent entry is written owed
 * and settled at once, so a commit never overtakes an older owed entry on its key.
 *
 * A write that records the end of an attempt, such as the one that marks an entry Sent, can fail on
 * a store error. The entry then stays Sending, the sender's handler hears the failure, and the
 * drain sends the entry again with the same id after a pause that doubles up to 1 minute while the
 * write keeps failing. That send can come before the retry policy's delay. So the server must take
 * the id as an idempotency key. Once a send has returned, that write still runs when the data
 * scope's scope stops.
 *
 * A sent entry that waits for its commit stays in the store, and an optimistic store's data keeps
 * showing its change until the commit lands. A failed entry that waits for its revert stays too.
 * Both wait until a handle attaches in a data scope with their sender, or until
 * [OutboxStores.clearAll]. An entry is done once it is Sent and committed, or Discarded, and only
 * then does the store prune it.
 *
 * Handles follow the entry, and [operation] finds one again after a restart. A handle settles with
 * the attempt it follows, so after a retry an earlier handle keeps its own result. When the data
 * scope's scope stops, unsettled handles settle Cancelled and the entries stay for the next data
 * scope with the sender. Drains start on enqueue, on reconnect, at the data scope's creation, when
 * a handle attaches, and after a pause for a key whose commit failed.
 */
@SubclassOptInRequired(DelicateDataLayerApi::class)
interface Outbox<E> {
  /**
   * Entries neither Sent nor Discarded, oldest first. An entry that cannot be decoded is left out,
   * never sends and never blocks another entry. The sender's failure handler hears of it as an
   * [UnreadableEntryException], and every other call treats it as absent.
   */
  val entries: Flow<List<OutboxEntry<E>>>

  /**
   * Runs the handle's `apply`, the optimistic local change, then inserts an entry, in one command.
   * The entry's id is made first, and `apply` gets it, so the handle knows the change by the id its
   * entry will have. The entry joins the line [ordering] picks, as [Ordering] describes.
   *
   * [ordering] must be [Ordering.Serial], [Ordering.Concurrent], or [Ordering.Keyed] with a
   * `String` key and [OnPrevious.Wait]. Any other throws [IllegalArgumentException], and nothing
   * runs or is stored.
   *
   * If apply throws, nothing is inserted and the handle fails. If the insert throws, the handle's
   * `revert` undoes the change and the handle fails. Once apply starts, it and the insert finish
   * even if the outbox's scope stops. [OutboxOperation.stored] settles once the entry is in, before
   * any send. The handle follows its entry from the moment the insert returns. So when the entry
   * was sent, failed and retried before then, the handle reports the retry's attempt.
   *
   * [admission] is [Admission.Queue], the default, or [Admission.Reject]. With `Reject(key)`, the
   * call stores nothing while an entry enqueued with an equal reject key is Queued, Sending or
   * Failed, on any line. The key is stored, so this holds across a restart. A rejected call runs no
   * `apply` or `revert`, no failure handler hears of it, and its handle and its
   * [OutboxOperation.stored] fail with a [CommandRejectedException] that holds `key`. A key that is
   * not a `String` throws [IllegalArgumentException], and nothing runs or is stored. Each `Reject`
   * call reads the outbox's entries once. The check is made inside one data scope, so a second data
   * scope over the same store can admit an equal key at the same moment. It takes no
   * [Admission.Join], since anyone with an entry's id can [discard] it for every caller that shares
   * it.
   */
  fun enqueue(
    payload: E,
    ordering: Ordering = Ordering.Serial,
    admission: CancellableAdmission = Admission.Queue,
  ): OutboxOperation

  /**
   * Requeues a failed entry with its attempts reset, or ends a backoff early. The handle follows
   * the entry from here. A retried entry sends before later entries on its key, except an entry the
   * drain has already read as the next to send. That entry goes first, also while it is still
   * stored as Queued, so a retried older change can reach the server after a newer one.
   */
  fun retry(id: EntryId): Operation

  /**
   * Discards a queued or failed entry and reverts its optimistic change, unless that already
   * happened. Fails instead when the drainer has already taken the entry, when the entry is sent,
   * or when there is no entry.
   */
  fun discard(id: EntryId): Operation

  /**
   * Re-attaches to an entry by id, for example after process death. An unknown or pruned id settles
   * Cancelled.
   */
  fun operation(id: EntryId): Operation

  companion object
}

/** [Outbox.enqueue] on the line of [key]. */
fun <E> Outbox<E>.enqueue(payload: E, key: String): OutboxOperation =
  enqueue(payload, Ordering.Keyed(key))

/**
 * The handle [Outbox.enqueue] and [OptimisticQueryStore.mutate] return. As an [Operation] it
 * follows the entry's send: Succeeded once sent, Failed once rejected for good or out of attempts,
 * Cancelled once discarded. [stored] settles earlier, once the entry is in the store.
 *
 * When the `apply` of [DataScope.outbox] or the insert throws, [stored] and the handle both settle
 * Failed with that failure. When the owner's scope stops before that `apply` starts, both settle
 * Cancelled. When it stops after the entry is stored, [stored] has succeeded, and the handle
 * settles Cancelled while the entry stays for the next data scope. When [Admission.Reject] rejects
 * the call, both settle Failed with a [CommandRejectedException].
 */
@SubclassOptInRequired(DelicateDataLayerApi::class)
interface OutboxOperation : Operation {
  /** The id of the entry this handle follows. The outbox makes it before the insert. */
  val id: EntryId

  /**
   * Whether the change reached the store. It succeeds once the entry is written, so the change will
   * send, and in a durable [OutboxStore] it outlives a restart. It fails when the store rejects the
   * entry, and settles Cancelled when the owner stops first. Await it when keeping the change is
   * enough, like a composer that closes once the message is saved.
   */
  val stored: Operation
}
