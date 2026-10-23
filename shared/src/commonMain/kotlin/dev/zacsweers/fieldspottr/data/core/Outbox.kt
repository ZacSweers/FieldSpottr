// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr.data.core

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update
import kotlin.math.pow
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

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
 * Writes that must reach the server, kept as rows in a [store] and sent in order per key. A send
 * that fails transiently backs off by [retry] and tries again. One that fails for good, or runs out
 * of attempts, fails its row and runs [revert] so the optimistic change behind it is undone.
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
 * Handles follow the row, so they survive a restart through [operation]. Drains start on enqueue,
 * on reconnect and at startup.
 */
@OptIn(ExperimentalAtomicApi::class)
class Outbox<E>(
  /** Runs the row views and the backoff timers. */
  private val scope: CoroutineScope,
  private val actions: DataActions,
  /**
   * Delivers one payload. It runs on the outbox's runner, usually on `Dispatchers.Default`, so a
   * send that blocks moves that part with `withContext` onto a dispatcher it was given.
   */
  private val send: suspend (E) -> Unit,
  /**
   * Undoes the optimistic change when a row fails for good or is discarded. It reads only the
   * payload, so it works after a restart too.
   */
  private val revert: suspend (E) -> Unit = {},
  /** A durable store when rows must survive the process. */
  private val store: OutboxStore<E> = inMemoryOutboxStore(),
  retry: RetryPolicy = RetryPolicy.exponential(max = 5.minutes),
  private val online: StateFlow<Boolean> = MutableStateFlow(true),
  /** Backoff deadlines outlive the process, so they use wall time. */
  private val clock: Clock = Clock.System,
) {
  private val policy = retry
  // Wake-up timers for keys in backoff. The deadline itself is the row's retryAt.
  private val waking = AtomicReference(persistentMapOf<String, Job>())

  /** Rows neither Sent nor Discarded, oldest first. */
  val entries: Flow<List<OutboxEntry<E>>> = store.entries()

  init {
    scope.launch {
      combine(online, store.entries()) { up, rows ->
          if (up) {
            rows.filter { it.status.isSendable }.mapTo(HashSet()) { it.key }
          } else {
            emptySet()
          }
        }
        .collect { keys -> keys.forEach(::drain) }
    }
  }

  /**
   * Runs [apply], the optimistic local change, then inserts a row, in one command. If apply throws,
   * nothing is inserted and the handle fails. Without a [key], every row shares one order.
   */
  fun enqueue(payload: E, key: String? = null, apply: suspend () -> Unit = {}): Operation {
    val rowKey = key ?: WHOLE_QUEUE
    return tracked(Ordering.Keyed(Enqueue(this, rowKey))) {
      apply()
      store.insert(rowKey, payload)
    }
  }

  /**
   * Requeues a failed row with its attempts reset, or ends a backoff early. The handle follows the
   * row from here.
   */
  fun retry(id: EntryId): Operation =
    tracked(Ordering.Keyed(Row(this, id))) {
      store.entry(id).first()?.let { row ->
        when (row.status) {
          is OutboxStatus.Failed ->
            store.update(
              row.copy(
                status = OutboxStatus.Queued,
                attempts = 0,
                lastError = null,
                retryAt = null,
              ),
              expected = row.status,
            )
          // Backing off: drop the deadline, then the timer
          OutboxStatus.Queued if row.retryAt != null -> {
            store.update(row.copy(retryAt = null), expected = OutboxStatus.Queued)
            waking.load()[row.key]?.cancel()
          }
          else -> Unit
        }
        drain(row.key)
      }
      id
    }

  /**
   * Discards a queued or failed row and reverts its optimistic change, unless that already
   * happened. Fails instead when the drainer has already taken the row.
   */
  fun discard(id: EntryId): Operation =
    actions.run(Ordering.Keyed(Row(this, id))) {
      val row = checkNotNull(store.entry(id).first()) { "$id is gone" }
      val pending = row.status == OutboxStatus.Queued || row.status is OutboxStatus.Failed
      val discarded =
        pending &&
          store.update(
            row.copy(status = OutboxStatus.Discarded, reverted = true),
            expected = row.status,
          )
      check(discarded) { "$id is no longer pending" }
      // A throw fails this handle and reaches the failure handler. The row stays Discarded.
      if (!row.reverted) {
        revert(row.payload)
      }
    }

  /**
   * Re-attaches to a row by id, for example after process death. An unknown or pruned id settles
   * Cancelled.
   */
  fun operation(id: EntryId): Operation =
    Operation.of(store.entry(id).map { it.toOperationState() }.latched())

  /**
   * One drainer per key. Sends the oldest sendable row until none is left, the network drops or a
   * send fails. Repeated calls coalesce.
   */
  private fun drain(key: String): Operation =
    actions.run(Ordering.Keyed(Drain(this, key), OnPrevious.Supersede)) {
      while (online.value && waking.load()[key]?.isActive != true) {
        val head =
          store.entries().first().firstOrNull { it.key == key && it.status.isSendable } ?: break
        val remaining = head.retryAt?.let { it - clock.now() }
        // Still backing off, perhaps since before a restart
        if (remaining != null && remaining.isPositive()) {
          backOff(key, remaining)
          break
        }
        // Discarded first
        if (!store.update(head.copy(status = OutboxStatus.Sending), expected = head.status)) {
          continue
        }
        val failure = runCatchingCancellable { send(head.payload) }.exceptionOrNull()
        val attempts = head.attempts + 1
        when {
          failure == null ->
            store.update(head.copy(status = OutboxStatus.Sent), expected = OutboxStatus.Sending)
          // Poison pill: fail it, undo its local change, and let the key move on
          failure is PermanentFailure || attempts >= policy.maxAttempts -> {
            store.update(
              head.copy(
                status = OutboxStatus.Failed(failure.toString()),
                attempts = attempts,
                reverted = true,
              ),
              expected = OutboxStatus.Sending,
            )
            // At most once per row. A throw ends this drain and reaches the failure handler.
            if (!head.reverted) {
              revert(head.payload)
            }
          }
          else -> {
            val backoff = policy.delayFor(attempts)
            store.update(
              head.copy(
                status = OutboxStatus.Queued,
                attempts = attempts,
                lastError = failure.toString(),
                retryAt = clock.now() + backoff,
              ),
              expected = OutboxStatus.Sending,
            )
            backOff(key, backoff)
          }
        }
      }
    }

  private fun backOff(key: String, wait: Duration) {
    val timer = scope.launch(start = CoroutineStart.LAZY) { delay(wait) }
    waking.update { it.putting(key, timer) }
    timer.invokeOnCompletion { cause ->
      waking.update { current ->
        if (current[key] === timer) {
          current.removing(key)
        } else {
          current
        }
      }
      // Cancelled by retry(), which drains itself
      if (cause == null) {
        drain(key)
      }
    }
    timer.start()
  }

  /** Queued until [write] lands, then a view over the row it names. */
  private fun tracked(ordering: Ordering, write: suspend () -> EntryId): Operation {
    val id = CompletableDeferred<EntryId>()
    val written = actions.run(ordering) { id.complete(write()) }
    return Operation.of(
      flow {
        when (val done = written.await()) {
          OperationState.Succeeded -> emitAll(store.entry(id.await()).map { it.toOperationState() })
          // The write itself failed or was cancelled
          else -> emit(done)
        }
      }
        .latched()
    )
  }

  /**
   * Collected in the outbox's scope until the first terminal state, then frozen. A retried row gets
   * a new handle.
   */
  private fun Flow<OperationState>.latched(): StateFlow<OperationState> = transformWhile {
    emit(it)
    it !is OperationState.Terminal
  }
    .stateIn(scope, SharingStarted.Eagerly, OperationState.Queued)

  /** Builds outboxes that share one scope, failure handler, payload format and store choice. */
  interface Factory {
    fun <E> create(
      name: String,
      serializer: KSerializer<E>,
      send: suspend (E) -> Unit,
      revert: suspend (E) -> Unit = {},
      retry: RetryPolicy = RetryPolicy.exponential(max = 5.minutes),
    ): Outbox<E>

    companion object {
      /**
       * Outboxes run in [scope] and failures go to [onFailure]. Each payload is stored in
       * [payloadFormat], and each outbox opens its rows on [stores] under its name.
       */
      operator fun invoke(
        scope: CoroutineScope,
        onFailure: (Throwable) -> Unit,
        online: StateFlow<Boolean> = MutableStateFlow(true),
        clock: Clock = Clock.System,
        payloadFormat: SerialFormat = Json,
        stores: OutboxStores = InMemoryOutboxStores,
      ): Factory =
        object : Factory {
          override fun <E> create(
            name: String,
            serializer: KSerializer<E>,
            send: suspend (E) -> Unit,
            revert: suspend (E) -> Unit,
            retry: RetryPolicy,
          ): Outbox<E> =
            Outbox(
              scope = scope,
              actions = DataActions(scope, onFailure),
              send = send,
              revert = revert,
              store = stores.open(name, serializer.asCodec(payloadFormat)),
              retry = retry,
              online = online,
              clock = clock,
            )
        }
    }
  }

  // Keys this outbox owns on its DataActions. They carry the instance, so two outboxes on one
  // runner never chain on each other.
  private data class Enqueue(val owner: Outbox<*>, val key: String)

  private data class Drain(val owner: Outbox<*>, val key: String)

  private data class Row(val owner: Outbox<*>, val id: EntryId)
}

/** [Outbox.Factory.create] with the serializer for a `@Serializable` payload. */
inline fun <reified E> Outbox.Factory.create(
  name: String,
  noinline send: suspend (E) -> Unit,
  noinline revert: suspend (E) -> Unit = {},
): Outbox<E> = create(name, serializer<E>(), send, revert)

private const val WHOLE_QUEUE = ""

private val OutboxStatus.isSendable: Boolean
  get() = this == OutboxStatus.Queued || this == OutboxStatus.Sending

private fun OutboxEntry<*>?.toOperationState(): OperationState =
  when (val status = this?.status) {
    OutboxStatus.Queued -> OperationState.Queued
    OutboxStatus.Sending -> OperationState.Running
    OutboxStatus.Sent -> OperationState.Succeeded
    is OutboxStatus.Failed -> OperationState.Failed(PermanentFailure(status.cause))
    // Discarded, or pruned before this view saw it
    OutboxStatus.Discarded,
    null -> OperationState.Cancelled
  }
